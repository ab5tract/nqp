package org.raku.nqp.truffle

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node

/**
 * Runs an [RxProgram] against a target.
 *
 * One loop, and a stack of choice points. A `SPLIT` pushes the arm it did not
 * take together with the position to resume at; a failure pops the most
 * recent one and carries on from there. That is the same discipline as the
 * bytecode backend's mark stack -- and unlike the recursive matcher that
 * preceded it, partial evaluation can compile it, because there is a loop
 * here rather than an unbounded recursion for PE to inline into.
 *
 * The program is compilation-final, so PE specializes this loop to one
 * particular program: the dispatch on opcode folds away and what remains is
 * code for that pattern alone. A matcher for a grammar that only exists at
 * run time, which is the whole point.
 *
 * Hand-written rather than built with Truffle's `@Specialization` DSL: the
 * specialization that matters here is the program itself becoming constant,
 * which `@CompilationFinal` already gives, and the DSL would only add an
 * annotation processor between this and the bytecode.
 */
class RxVmNode(@CompilationFinal private val program: RxProgram) : Node() {

    fun match(cursor: RxCursor, startPos: Int): Int =
        run(cursor, cursor.atoms(), cursor.target(), cursor.eos(), startPos, null, null)

    /**
     * Matches, and on success leaves the live choice state in [stateOut]
     * slot 0 when there is anything left to resume -- which is what makes
     * a rule that passed with :backtrack re-enterable.
     */
    fun match(cursor: RxCursor, startPos: Int, stateOut: Array<EngineState?>): Int =
        run(cursor, cursor.atoms(), cursor.target(), cursor.eos(), startPos, null, stateOut)

    /**
     * Resumes a previous match from its saved choice state: the engine
     * side of `!cursor_next`. Enters the loop failing, which pops the
     * most recent choice point exactly as an in-match failure would.
     */
    fun resume(cursor: RxCursor, state: EngineState, stateOut: Array<EngineState?>): Int =
        run(cursor, cursor.atoms(), cursor.target(), cursor.eos(), 0, state, stateOut)

    /*
     * Deliberately not @ExplodeLoop. Exploding along the program's control
     * flow is the usual move for a bytecode interpreter, but it only
     * terminates for an acyclic program, and a quantifier compiles to a jump
     * back -- MERGE_EXPLODE bails with "too many loop explosion iterations".
     * Left as an ordinary loop, partial evaluation still specializes it to
     * the program, since the code array is compilation-final; it just keeps a
     * loop rather than unrolling one.
     */
    private fun run(
        cursor: RxCursor,
        atoms: IntArray,
        target: String,
        eos: Int,
        startPos: Int,
        resumeFrom: EngineState?,
        stateOut: Array<EngineState?>?,
    ): Int {
        val code = program.code
        val pool = program.pool

        /* Sized to what this program can actually use, and skipped altogether
         * when it uses none: a scan enters here once per position, so an
         * array allocated per match is one per character of the target. */
        val regs = resumeFrom?.regs
            ?: if (program.registers == 0) EMPTY else IntArray(program.registers)
        var choices = resumeFrom?.choices ?: IntArray(program.choiceDepth * CHOICE_WIDTH)
        /* One slot per choice record: a non-null entry is a subrule the
         * engine can ask for its next match instead of merely re-running. */
        var retries = resumeFrom?.retries ?: arrayOfNulls<SubRetry?>(choices.size / CHOICE_WIDTH)
        var choiceTop = resumeFrom?.choiceTop ?: 0
        /* Captures are recorded as they are passed, and taken back when a
         * choice point before them is resumed; that is what the undo log is
         * for. The bytecode engine unwinds its capture stack for the same
         * reason. */
        val capStart = if (program.captures == 0) EMPTY else IntArray(program.registers)
        /*
         * Captures are held back until the whole match has succeeded, and the
         * pending list is cut back to a choice point's height when one is
         * resumed -- so a path that was tried and abandoned captures nothing.
         * The bytecode engine unwinds its cstack against its marks for the
         * same reason. Held as (name, from, to) with a null name meaning the
         * cursor at that slot is the capture instead.
         */
        var pending = resumeFrom?.pending
            ?: if (program.captures == 0) NO_PENDING else arrayOfNulls<Any?>(16 * PENDING_WIDTH)
        var pendingTop = resumeFrom?.pendingTop ?: 0
        /* How much of the pending list has been synced to the cursor. A
         * rule's own code (`{ ... }`) reads the captures made so far
         * through `$/`, so pending captures are handed over before a
         * callback runs; a backtrack past them takes them back through
         * truncateCaptures. Zero for the great many rules whose callbacks
         * never fire. */
        var syncedTop = resumeFrom?.syncedTop ?: 0

        var pc = 0
        var pos = startPos
        /* A resumed match starts by failing: that pops the most recent
         * choice point, which is exactly what "the next match" means. */
        var pendingFail = resumeFrom != null

        var debugSteps = 0L
        while (true) {
            if (MAX_STEPS > 0 && ++debugSteps > MAX_STEPS)
                throw RuntimeException(
                    "rx step budget exceeded: pc=$pc pos=$pos choiceTop=$choiceTop pendingTop=$pendingTop")
            var failed = pendingFail
            pendingFail = false
            if (!failed) when (code[pc]) {
                RxProgram.MATCH -> {
                    /* Whatever a callback sync already handed over stays;
                     * only the remainder is flushed. */
                    if (pendingTop > syncedTop) {
                        sync(cursor, pending, syncedTop, pendingTop)
                        syncedTop = pendingTop
                    }
                    /* A resumable rule with live choice points leaves them
                     * for !cursor_next; everything the arrays hold below the
                     * tops is exactly the state a resumed run needs. */
                    if (stateOut != null && choiceTop > 0) {
                        stateOut[0] = EngineState(
                            choices, retries, choiceTop, regs,
                            pending, pendingTop, syncedTop)
                    }
                    return pos
                }

                RxProgram.CHAR -> {
                    val text = pool[code[pc + 1]] as String
                    val flags = code[pc + 2]
                    val ignoreCase = (flags and RxProgram.F_IGNORECASE) != 0
                    /* NFG: compare grapheme atoms. A bare CR literal is atom 13
                     * and a CR LF grapheme in the target is a synthetic, so the
                     * "don't match into a fused pair" rule falls out of the atom
                     * comparison -- no special case needed. */
                    val lit = literalAtoms(text)
                    val fits = pos + lit.size <= eos
                    val hit = if ((flags and RxProgram.F_IGNOREMARK) != 0) {
                        /* The mark-insensitive comparisons live in the runtime,
                         * same as the bytecode path's eqatim (grapheme-indexed). */
                        fits && literalIgnoreMark(target, text, pos, ignoreCase)
                    } else {
                        fits && atomsEqualAt(atoms, pos, lit, ignoreCase)
                    }
                    if (hit == ((flags and RxProgram.F_NEGATE) != 0)) {
                        failed = true
                    } else {
                        if ((flags and RxProgram.F_ZEROWIDTH) == 0) pos += lit.size
                        pc += 3
                    }
                }

                RxProgram.ONE -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val pred = pool[code[pc + 1]] as RxProgram.CharPred
                        val cp = atoms[pos]
                        if (!pred.holds(cp)) {
                            failed = true
                        } else {
                            pos += 1
                            pc += 2
                        }
                    }
                }

                RxProgram.ANY -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        pos += 1
                        pc += 1
                    }
                }

                RxProgram.NL -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        /* NFG: one newline grapheme. A CR LF pair is a single
                         * grapheme (a synthetic whose base is CR), so isNl
                         * accepts it and it advances by one, no LF fixup. */
                        val cp = atoms[pos]
                        val b = baseOf(cp)
                        if (b != '\n'.code && b != '\r'.code) {
                            failed = true
                        } else {
                            pos += 1
                            pc += 1
                        }
                    }
                }

                RxProgram.CHAR1 -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        /* The fused pair equals no single codepoint, so a
                         * bare CR or LF literal fails on it -- and its
                         * negation matches, consuming both characters. */
                        val cp = atoms[pos]
                        if ((cp == code[pc + 1]) == (code[pc + 2] != 0)) {
                            failed = true
                        } else {
                            pos += 1
                            pc += 3
                        }
                    }
                }

                RxProgram.DIGIT -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val cp = atoms[pos]
                        if (Character.isDigit(baseOf(cp)) == (code[pc + 1] != 0)) {
                            failed = true
                        } else {
                            pos += 1
                            pc += 2
                        }
                    }
                }

                RxProgram.WORD -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val cp = atoms[pos]
                        val inClass = Character.isLetterOrDigit(baseOf(cp)) || baseOf(cp) == '_'.code
                        if (inClass == (code[pc + 1] != 0)) {
                            failed = true
                        } else {
                            pos += 1
                            pc += 2
                        }
                    }
                }

                RxProgram.SPACE -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val cp = atoms[pos]
                        /* CCLASS_WHITESPACE, not Character.isWhitespace: the
                         * latter excludes NBSP and NEL, which nqp's \s
                         * includes (see Ops.iscclass). NFG: on the base, so the
                         * CR LF grapheme (base CR) is whitespace. */
                        val sb = baseOf(cp)
                        val inClass = sb in 9..13 ||
                            sb == 0x85 || Character.isSpaceChar(sb)
                        if (inClass == (code[pc + 1] != 0)) {
                            failed = true
                        } else {
                            pos += 1
                            pc += 2
                        }
                    }
                }

                RxProgram.RANGE1 -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val cp = atoms[pos]
                        val inRange = cp >= code[pc + 1] && cp <= code[pc + 2]
                        if (inRange == (code[pc + 3] != 0)) {
                            failed = true
                        } else {
                            pos += 1
                            pc += 4
                        }
                    }
                }

                RxProgram.ANCHOR -> {
                    if (!anchorHolds(code[pc + 1], atoms, pos, eos)) {
                        failed = true
                    } else {
                        pc += 2
                    }
                }

                RxProgram.SPLIT -> {
                    if (choiceTop + CHOICE_WIDTH > choices.size) {
                        choices = grow(choices)
                        retries = retries.copyOf(choices.size / CHOICE_WIDTH)
                    }
                    retries[choiceTop / CHOICE_WIDTH] = null
                    choices[choiceTop] = code[pc + 2]
                    choices[choiceTop + 1] = pos
                    choices[choiceTop + 2] = pendingTop
                    choices[choiceTop + 3] = 0
                    choiceTop += CHOICE_WIDTH
                    pc = code[pc + 1]
                }

                RxProgram.LOOP_SPLIT -> {
                    /* A SPLIT that also marks the iteration entry: the old
                     * mark rides in the choice point, and popping it puts the
                     * mark back -- so EMPTY_CHECK always compares against the
                     * entry of the iteration actually being run, even after
                     * backtracking into an earlier one. */
                    val r = code[pc + 3]
                    if (choiceTop + CHOICE_WIDTH > choices.size) {
                        choices = grow(choices)
                        retries = retries.copyOf(choices.size / CHOICE_WIDTH)
                    }
                    retries[choiceTop / CHOICE_WIDTH] = null
                    choices[choiceTop] = code[pc + 2]
                    choices[choiceTop + 1] = pos
                    choices[choiceTop + 2] = pendingTop
                    choices[choiceTop + 3] = r + 1
                    choices[choiceTop + 4] = regs[r]
                    choiceTop += CHOICE_WIDTH
                    regs[r] = pos
                    pc = code[pc + 1]
                }

                RxProgram.JMP -> pc = code[pc + 1]

                RxProgram.ADVANCE -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        /* A scan steps by atom, so it never offers a match a
                         * position inside a CR LF pair -- an NFG backend has
                         * no such position to offer. */
                        pos += 1
                        pc += 1
                    }
                }

                RxProgram.MARK -> {
                    regs[code[pc + 1]] = pos
                    pc += 2
                }

                RxProgram.CUT_MARK -> {
                    regs[code[pc + 1]] = choiceTop
                    pc += 2
                }

                RxProgram.ONE_ZW -> {
                    val negate = code[pc + 2] != 0
                    val ok = if (pos >= eos) {
                        /* Nothing here. A negated look-ahead is satisfied by
                         * that -- there is no character to be the wrong one --
                         * and a positive one cannot be. */
                        negate
                    } else {
                        val pred = pool[code[pc + 1]] as RxProgram.CharPred
                        pred.holds(atoms[pos]) != negate
                    }
                    if (ok) pc += 3 else failed = true
                    /* pos deliberately untouched: that is what zero-width is. */
                }

                RxProgram.QASTNODE -> {
                    val flags = code[pc + 2]
                    /* The captures made so far become visible on the cursor
                     * first: the code is entitled to read them through $/,
                     * which it builds from the cursor's own capture stack. */
                    if (pendingTop > syncedTop) {
                        sync(cursor, pending, syncedTop, pendingTop)
                        syncedTop = pendingTop
                    }
                    /* The code runs either way -- a `{ ... }` is there for its
                     * effect -- and only a zero-width one is allowed to decide
                     * anything by its answer. */
                    val held = callbackHolds(cursor, code[pc + 1], pos)
                    if ((flags and RxProgram.F_ZEROWIDTH) != 0 &&
                        held == ((flags and RxProgram.F_NEGATE) != 0)
                    ) {
                        failed = true
                    } else {
                        /* pos is untouched: this looks, it does not consume. */
                        pc += 3
                    }
                }

                RxProgram.UNIPROP -> {
                    /* Out of characters fails whatever the negation says:
                     * there is nothing here to carry a property, which is what
                     * QAST::Compiler's own eos check decides too. */
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val flags = code[pc + 2]
                        /* A synthetic grapheme (a fused pair, or base+combiners)
                         * carries no single codepoint's property: on MoarVM not
                         * even <:Space> holds of the CR LF grapheme. So a
                         * synthetic (atom < 0) fails every positive property test
                         * without asking the runtime. */
                        val cp = atoms[pos]
                        val holds = cp >= 0 &&
                            charProp(cursor, pool[code[pc + 1]] as String, pos)
                        if (holds == ((flags and RxProgram.F_NEGATE) != 0)) {
                            failed = true
                        } else {
                            if ((flags and RxProgram.F_ZEROWIDTH) == 0) {
                                pos += 1
                            }
                            pc += 3
                        }
                    }
                }

                RxProgram.ALT_LTM -> {
                    val name = pool[code[pc + 1]] as String
                    val n = code[pc + 2]
                    val order = altOrder(cursor, name, pos, n)
                    if (order.isEmpty()) {
                        failed = true
                    } else {
                        /* Everything after the best branch becomes a choice
                         * point, pushed worst first so the best is resumed
                         * last -- the stack is LIFO and the first branch is
                         * taken now rather than pushed. */
                        for (i in order.size - 1 downTo 1) {
                            if (choiceTop + CHOICE_WIDTH > choices.size) {
                                choices = grow(choices)
                                retries = retries.copyOf(choices.size / CHOICE_WIDTH)
                            }
                            retries[choiceTop / CHOICE_WIDTH] = null
                            choices[choiceTop] = code[pc + 3 + order[i]]
                            choices[choiceTop + 1] = pos
                            choices[choiceTop + 2] = pendingTop
                            choices[choiceTop + 3] = 0
                            choiceTop += CHOICE_WIDTH
                        }
                        pc = code[pc + 3 + order[0]]
                    }
                }

                RxProgram.CUT -> {
                    /* Everything the quantifier could still have tried is
                     * discarded, so a later failure backtracks past it. */
                    choiceTop = regs[code[pc + 1]]
                    pc += 2
                }

                RxProgram.EMPTY_CHECK -> {
                    /* A repetition that consumed nothing would spin here. */
                    if (pos == regs[code[pc + 1]]) {
                        failed = true
                    } else {
                        pc += 2
                    }
                }

                RxProgram.CAP_START -> {
                    capStart[code[pc + 1]] = pos
                    pc += 2
                }

                RxProgram.CAP_END -> {
                    val r = code[pc + 1]
                    if (pendingTop + PENDING_WIDTH > pending.size) pending = grow(pending)
                    pending[pendingTop] = pool[code[pc + 2]]
                    pending[pendingTop + 1] = capStart[r]
                    pending[pendingTop + 2] = pos
                    pending[pendingTop + 3] = SPAN
                    pendingTop += PENDING_WIDTH
                    pc += 3
                }

                RxProgram.DYNQ_BOUNDS -> {
                    val r = code[pc + 1]
                    /* Same as SUB_CB: the bounds code may read $/ and with it
                     * the captures made so far. */
                    if (pendingTop > syncedTop) {
                        sync(cursor, pending, syncedTop, pendingTop)
                        syncedTop = pendingTop
                    }
                    val b = callbackBounds(cursor, code[pc + 2], pos)
                    regs[r] = b[0]
                    regs[r + 1] = b[1]
                    regs[r + 2] = 0
                    pc = if (b[0] == 0 && b[1] == 0) code[pc + 3] else pc + 4
                }

                RxProgram.DYNQ_STEP -> {
                    val r = code[pc + 1]
                    val min = regs[r]
                    val max = regs[r + 1]
                    val rep = regs[r + 2]
                    val bodyPc = if (rep == 0) code[pc + 2] else code[pc + 3]
                    if (rep < min) {
                        pc = bodyPc
                    } else if (max != -1 && rep >= max) {
                        pc = code[pc + 4]
                    } else {
                        val greedy = code[pc + 5] != 0
                        if (choiceTop + CHOICE_WIDTH > choices.size) {
                            choices = grow(choices)
                            retries = retries.copyOf(choices.size / CHOICE_WIDTH)
                        }
                        retries[choiceTop / CHOICE_WIDTH] = null
                        choices[choiceTop] = if (greedy) code[pc + 4] else bodyPc
                        choices[choiceTop + 1] = pos
                        choices[choiceTop + 2] = pendingTop
                        /* No displaced mark -- and a stale value here would
                         * make the pop restore a register it never saved. */
                        choices[choiceTop + 3] = 0
                        choiceTop += CHOICE_WIDTH
                        pc = if (greedy) bodyPc else code[pc + 4]
                    }
                }

                RxProgram.REG_TO_POS -> {
                    pos = regs[code[pc + 1]]
                    pc += 2
                }

                RxProgram.POS_EQ_REG -> {
                    if (pos != regs[code[pc + 1]]) {
                        failed = true
                    } else {
                        pc += 2
                    }
                }

                RxProgram.DYNQ_NEXT -> {
                    val r = code[pc + 1]
                    if (pos == regs[code[pc + 2]] && regs[r + 2] >= regs[r]) {
                        failed = true
                    } else {
                        regs[r + 2]++
                        pc = code[pc + 3]
                    }
                }

                RxProgram.SUB_CB -> {
                    val flags = code[pc + 2]
                    /* The captures made so far become visible on the cursor
                     * first, as for QASTNODE: the called code may read them --
                     * !BACKREF walks the cursor's capture stack to find what
                     * $<name> matched, and an unsynced stack made every
                     * backreference in an engine rule silently fail. */
                    if (pendingTop > syncedTop) {
                        sync(cursor, pending, syncedTop, pendingTop)
                        syncedTop = pendingTop
                    }
                    val sub = callbackCursor(cursor, code[pc + 1], pos)
                    val r = reached(cursor, sub)
                    val matched = r != NO_MATCH
                    if (matched == ((flags and RxProgram.F_NEGATE) != 0)) {
                        failed = true
                    } else {
                        if (SUBRETRY && matched && (flags and RxProgram.F_NEGATE) == 0 &&
                            (flags and RxProgram.F_SUBRATCHET) == 0
                        ) {
                            if (choiceTop + CHOICE_WIDTH > choices.size) {
                                choices = grow(choices)
                                retries = retries.copyOf(choices.size / CHOICE_WIDTH)
                            }
                            retries[choiceTop / CHOICE_WIDTH] = SubRetry(
                                sub,
                                if (code[pc + 3] == 0) null else pool[code[pc + 3] - 1] as String,
                                pc + 4, (flags and RxProgram.F_ZEROWIDTH) != 0)
                            choices[choiceTop] = -1
                            choices[choiceTop + 1] = pos
                            choices[choiceTop + 2] = pendingTop
                            choices[choiceTop + 3] = 0
                            choiceTop += CHOICE_WIDTH
                        }
                        if (matched && (flags and RxProgram.F_ZEROWIDTH) == 0) pos = r
                        if (matched && code[pc + 3] != 0) {
                            if (pendingTop + PENDING_WIDTH > pending.size) pending = grow(pending)
                            pending[pendingTop] = pool[code[pc + 3] - 1]
                            pending[pendingTop + 1] = sub
                            pending[pendingTop + 2] = null
                            pending[pendingTop + 3] = null
                            pendingTop += PENDING_WIDTH
                        }
                        pc += 4
                    }
                }

                RxProgram.SUB -> {
                    val name = pool[code[pc + 1]] as String
                    val flags = code[pc + 2]
                    val args = if (code[pc + 4] == 0) null else pool[code[pc + 4] - 1] as RxArgs
                    /* Flagged at encode time: the callee (!BACKREF) walks the
                     * caller cursor's capture stack, so the captures made so
                     * far must be on it. Ordinary subrule calls skip this. */
                    if (TRACE_SYNC) System.err.println(
                        "rx SUB $name flags=$flags pending=$pendingTop synced=$syncedTop")
                    if ((flags and RxProgram.F_CSTACK) != 0 && pendingTop > syncedTop) {
                        sync(cursor, pending, syncedTop, pendingTop)
                        syncedTop = pendingTop
                    }
                    val sub = callSubrule(cursor, name, pos, args)
                    val r = reached(cursor, sub)
                    val matched = r != NO_MATCH
                    if (matched == ((flags and RxProgram.F_NEGATE) != 0)) {
                        failed = true
                    } else {
                        /* A backtrackable subrule that matched is a choice
                         * point of its own kind: backtracking past it asks
                         * the SUBCURSOR for its next match rather than
                         * re-running the call, which is the bstack contract.
                         * Recorded before pos moves so the choice restores
                         * the pre-call position when the retries run out. */
                        if (SUBRETRY && matched && (flags and RxProgram.F_NEGATE) == 0 &&
                            (flags and RxProgram.F_SUBRATCHET) == 0
                        ) {
                            if (choiceTop + CHOICE_WIDTH > choices.size) {
                                choices = grow(choices)
                                retries = retries.copyOf(choices.size / CHOICE_WIDTH)
                            }
                            retries[choiceTop / CHOICE_WIDTH] = SubRetry(
                                sub,
                                if (code[pc + 3] == 0) null else pool[code[pc + 3] - 1] as String,
                                pc + 5, (flags and RxProgram.F_ZEROWIDTH) != 0)
                            choices[choiceTop] = -1
                            choices[choiceTop + 1] = pos
                            choices[choiceTop + 2] = pendingTop
                            choices[choiceTop + 3] = 0
                            choiceTop += CHOICE_WIDTH
                        }
                        if (matched && (flags and RxProgram.F_ZEROWIDTH) == 0) pos = r
                        /* A capturing subrule keeps the cursor it made; that
                         * cursor is the capture, not the span it covered. */
                        if (matched && code[pc + 3] != 0) {
                            if (pendingTop + PENDING_WIDTH > pending.size) pending = grow(pending)
                            pending[pendingTop] = pool[code[pc + 3] - 1]
                            pending[pendingTop + 1] = sub
                            pending[pendingTop + 2] = null
                            pending[pendingTop + 3] = null
                            pendingTop += PENDING_WIDTH
                        }
                        pc += 5
                    }
                }

                else -> throw IllegalStateException(
                    "bad opcode " + code[pc] + " at pc=" + pc
                    + " pos=" + pos + " choiceTop=" + choiceTop
                    + " window=" + java.util.Arrays.toString(
                        code.copyOfRange(maxOf(0, pc - 6), minOf(code.size, pc + 6)))
                    + " choices=" + java.util.Arrays.toString(
                        choices.copyOfRange(0, minOf(choices.size, choiceTop + CHOICE_WIDTH))))
            }

            if (failed) {
                if (choiceTop == 0) {
                    /* Anything synced to the cursor was made on a path that
                     * has now failed whole. */
                    if (syncedTop > 0) truncate(cursor, 0)
                    return NO_MATCH
                }
                val slot = choiceTop / CHOICE_WIDTH - 1
                val retry = retries[slot]
                if (retry != null) {
                    /* Backtracking into a subrule: take back what the call
                     * contributed, then ask the subcursor for its NEXT
                     * match. When it has one, the choice point stays -- with
                     * the new cursor -- and the match continues after the
                     * call; when it has none, the choice point goes and the
                     * failure keeps walking. */
                    pendingTop = choices[choiceTop - CHOICE_WIDTH + 2]
                    if (syncedTop > pendingTop) {
                        truncate(cursor, pendingTop / PENDING_WIDTH)
                        syncedTop = pendingTop
                    }
                    val next = nextMatch(cursor, retry.cursor)
                    val r = reached(cursor, next)
                    if (r != NO_MATCH) {
                        retries[slot] = SubRetry(next, retry.capName, retry.contPc, retry.zeroWidth)
                        pos = if (retry.zeroWidth) choices[choiceTop - CHOICE_WIDTH + 1] else r
                        if (retry.capName != null) {
                            if (pendingTop + PENDING_WIDTH > pending.size) pending = grow(pending)
                            pending[pendingTop] = retry.capName
                            pending[pendingTop + 1] = next
                            pending[pendingTop + 2] = null
                            pending[pendingTop + 3] = null
                            pendingTop += PENDING_WIDTH
                        }
                        pc = retry.contPc
                        continue
                    }
                    retries[slot] = null
                    choiceTop -= CHOICE_WIDTH
                    pos = choices[choiceTop + 1]
                    if (choices[choiceTop + 3] != 0)
                        regs[choices[choiceTop + 3] - 1] = choices[choiceTop + 4]
                    pendingFail = true
                    continue
                }
                choiceTop -= CHOICE_WIDTH
                pc = choices[choiceTop]
                pos = choices[choiceTop + 1]
                /* A LOOP_SPLIT's choice carries the empty-check mark it
                 * displaced; put it back so the mark always describes the
                 * iteration actually being run. */
                if (choices[choiceTop + 3] != 0)
                    regs[choices[choiceTop + 3] - 1] = choices[choiceTop + 4]
                /* Anything captured on the abandoned path goes with it --
                 * including its record on the cursor, when a callback had
                 * the captures synced across. */
                pendingTop = choices[choiceTop + 2]
                if (syncedTop > pendingTop) {
                    truncate(cursor, pendingTop / PENDING_WIDTH)
                    syncedTop = pendingTop
                }
            }
        }
    }

    /**
     * A choice point that is a subrule's own next match: [cursor] is the
     * subcursor that matched last, [capName] the capture it lands under (or
     * null), [contPc] where the program continues after the call, and
     * [zeroWidth] whether the call consumed nothing.
     */
    class SubRetry(
        @JvmField val cursor: Any?,
        @JvmField val capName: String?,
        @JvmField val contPc: Int,
        @JvmField val zeroWidth: Boolean,
    )

    /**
     * Everything a passed match needs to answer `!cursor_next`: the live
     * choice stack and its subrule retries, the registers, and the capture
     * bookkeeping. Held per passed cursor by the engine; resuming enters
     * the loop failing, which pops the most recent choice point.
     */
    class EngineState(
        @JvmField val choices: IntArray,
        @JvmField val retries: Array<SubRetry?>,
        @JvmField val choiceTop: Int,
        @JvmField val regs: IntArray,
        @JvmField val pending: Array<Any?>,
        @JvmField val pendingTop: Int,
        @JvmField val syncedTop: Int,
    ) {
        companion object {
            /** A state with nothing to resume but the scan itself. */
            @JvmStatic
            fun scanOnly(at: Int): EngineState {
                val s = EngineState(
                    IntArray(0), arrayOfNulls(0), 0, IntArray(0),
                    arrayOfNulls(0), 0, 0)
                s.scanAt = at
                return s
            }
        }

        /* Where the engine's captures started on the passed cursor's
         * stacks, carried across so a resumed run truncates correctly. */
        @JvmField var captureBaseC: Int = -1
        @JvmField var captureBaseB: Int = -1

        /* The position a scanning top-level match succeeded at, or -1 when
         * the rule was not scanning. When every choice point of a resumed
         * match is exhausted, the scan itself is the next choice: the
         * engine retries whole matches from the following position, which
         * is what the bytecode path's scan marks on the bstack amount to. */
        @JvmField var scanAt: Int = -1
    }

    companion object {
        /* Kill-switch for the newest of the backtracking machinery: set
         * NQP_RX_NO_SUBRETRY to stop subrule calls becoming re-enterable
         * choice points, for bisecting a wrong parse. Runtime-side because
         * the feature is; the NQP_RX_NO knobs govern only what the encoder
         * emits. */
        @JvmField val SUBRETRY: Boolean = System.getenv("NQP_RX_NO_SUBRETRY") == null

        /* Debug: trace subrule calls' capture-sync decisions. */
        @JvmField val TRACE_SYNC: Boolean = System.getenv("NQP_RX_TRACE_SYNC") != null

        /* Debug: abort a run after this many steps with a state summary. */
        @JvmField val MAX_STEPS: Long = System.getenv("NQP_RX_MAXSTEPS")?.toLongOrNull() ?: 0L

        /** No match. */
        const val NO_MATCH = -1

        /* pc, pos, pending-capture height, mark-register + 1 (0 for none),
         * saved mark. The last two carry a LOOP_SPLIT's empty-check mark so
         * a backtrack into an earlier iteration restores it; see LOOP_SPLIT. */
        private const val CHOICE_WIDTH = 5
        private const val PENDING_WIDTH = 4  // name, from|cursor, to, kind

        private val SPAN = Any()
        private val EMPTY = IntArray(0)
        private val NO_PENDING = arrayOfNulls<Any?>(0)

        /*
         * The kinds are read out of a cached array rather than from values(),
         * which copies its array on every call -- and this runs once per
         * anchor per position, inside the loop PE is trying to specialize.
         */
        @CompilationFinal(dimensions = 1)
        private val ANCHOR_KINDS = RxTree.Anchor.Kind.entries.toTypedArray()

        /* NFG: positions are grapheme indices, so anchors read the atom array. */
        private fun anchorHolds(kind: Int, atoms: IntArray, pos: Int, eos: Int): Boolean =
            when (ANCHOR_KINDS[kind]) {
                RxTree.Anchor.Kind.BOS -> pos == 0
                RxTree.Anchor.Kind.EOS -> pos == eos
                /* The reference semantics are the bytecode path's bol/eol
                 * emission (QAST::Compiler), which tests CCLASS_NEWLINE --
                 * not just '\n' -- and refuses the position past a trailing
                 * newline: a line neither starts at end-of-string after a
                 * final newline (bol) nor ends right after one (eol). */
                RxTree.Anchor.Kind.BOL ->
                    pos == 0 || (pos < eos && isNl(atoms[pos - 1]))
                RxTree.Anchor.Kind.EOL ->
                    (pos < eos && isNl(atoms[pos]))
                        || (pos == eos && (pos == 0 || !isNl(atoms[pos - 1])))
                RxTree.Anchor.Kind.LWB ->
                    pos < eos && isWord(atoms, pos) && (pos == 0 || !isWord(atoms, pos - 1))
                RxTree.Anchor.Kind.RWB ->
                    pos > 0 && isWord(atoms, pos - 1) && (pos == eos || !isWord(atoms, pos))
                /* The two constant assertions: `<?>` always holds, and the
                 * fail anchor is how QAST::Compiler spells a branch that is
                 * dead. */
                RxTree.Anchor.Kind.PASS -> true
                RxTree.Anchor.Kind.FAIL -> false
            }

        private fun isWord(atoms: IntArray, pos: Int): Boolean {
            val c = baseOf(atoms[pos])
            return Character.isLetterOrDigit(c) || c == '_'.code
        }

        /* The base codepoint of a grapheme atom: itself if a codepoint, else the
         * synthetic's first codepoint. Used by the \w/\s/\d/anchor tests. */
        private fun baseOf(atom: Int): Int =
            if (atom >= 0) atom else org.raku.nqp.runtime.NFGSynthetics.baseOf(atom)

        /* A CHAR literal's grapheme atoms. Off the PE fast path (allocates), but
         * a literal is a pool constant so this is stable per program site. */
        @TruffleBoundary
        private fun literalAtoms(text: String): IntArray =
            NFGString.atomsOf(text)

        /* Does the target's atoms at [pos] equal [lit] grapheme-for-grapheme?
         * Case-insensitive comparison only folds plain codepoint atoms. */
        private fun atomsEqualAt(atoms: IntArray, pos: Int, lit: IntArray, ignoreCase: Boolean): Boolean {
            var i = 0
            while (i < lit.size) {
                val a = atoms[pos + i]
                val b = lit[i]
                if (a != b) {
                    if (!ignoreCase || a < 0 || b < 0) return false
                    if (Character.toLowerCase(a) != Character.toLowerCase(b) &&
                        Character.toUpperCase(a) != Character.toUpperCase(b)) return false
                }
                i++
            }
            return true
        }

        /* CCLASS_NEWLINE, inlined from Ops.iscclass. NFG: tested on the atom's
         * base codepoint, so the CR LF grapheme (base CR) counts as a newline. */
        private fun isNl(atom: Int): Boolean {
            val c = baseOf(atom)
            return c == '\n'.code || c == 0x0B || c == 0x0C || c == '\r'.code ||
                c == 0x85 || c == 0x2029 ||
                Character.getType(c) == Character.LINE_SEPARATOR.toInt()
        }

        private fun grow(array: IntArray): IntArray = array.copyOf(array.size * 2)

        private fun grow(array: Array<Any?>): Array<Any?> = array.copyOf(array.size * 2)

        /* The ways out of the engine and into NQP: a subrule is compiled
         * bytecode, a capture lands on the cursor, and rule code runs in the
         * rule's own frame. All of them are boundaries. */

        @TruffleBoundary
        private fun callSubrule(cursor: RxCursor, name: String, pos: Int, args: RxArgs?): Any? =
            cursor.callSubrule(name, pos, args)

        @TruffleBoundary
        private fun callbackCursor(cursor: RxCursor, index: Int, pos: Int): Any? =
            cursor.callbackCursor(index, pos)

        @TruffleBoundary
        private fun callbackBounds(cursor: RxCursor, index: Int, pos: Int): IntArray =
            cursor.callbackBounds(index, pos)

        @TruffleBoundary
        private fun nextMatch(cursor: RxCursor, subCursor: Any?): Any? =
            cursor.nextMatch(subCursor)

        @TruffleBoundary
        private fun literalIgnoreMark(
            target: String, text: String, pos: Int, alsoCase: Boolean,
        ): Boolean =
            (if (alsoCase) org.raku.nqp.runtime.Ops.eqaticim(target, text, pos.toLong())
             else org.raku.nqp.runtime.Ops.eqatim(target, text, pos.toLong())) != 0L

        @TruffleBoundary
        private fun reached(cursor: RxCursor, subCursor: Any?): Int = cursor.reached(subCursor)

        @TruffleBoundary
        private fun callbackHolds(cursor: RxCursor, index: Int, pos: Int): Boolean =
            cursor.callbackHolds(index, pos)

        @TruffleBoundary
        private fun charProp(cursor: RxCursor, property: String, pos: Int): Boolean =
            cursor.charProp(property, pos)

        @TruffleBoundary
        private fun altOrder(cursor: RxCursor, name: String, pos: Int, branches: Int): IntArray =
            cursor.altOrder(name, pos, branches)

        @TruffleBoundary
        private fun sync(cursor: RxCursor, pending: Array<Any?>, from: Int, top: Int) {
            var i = from
            while (i < top) {
                val name = pending[i] as String
                if (pending[i + 3] === SPAN) {
                    cursor.captureSpan(name, pending[i + 1] as Int, pending[i + 2] as Int)
                } else {
                    cursor.captureCursor(name, pending[i + 1])
                }
                i += PENDING_WIDTH
            }
        }

        @TruffleBoundary
        private fun truncate(cursor: RxCursor, entries: Int) {
            cursor.truncateCaptures(entries)
        }
    }
}
