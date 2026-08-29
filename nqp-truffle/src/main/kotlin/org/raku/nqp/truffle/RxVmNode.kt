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
        run(cursor, cursor.target(), cursor.eos(), startPos, null, null)

    /**
     * Matches, and on success leaves the live choice state in [stateOut]
     * slot 0 when there is anything left to resume -- which is what makes
     * a rule that passed with :backtrack re-enterable.
     */
    fun match(cursor: RxCursor, startPos: Int, stateOut: Array<EngineState?>): Int =
        run(cursor, cursor.target(), cursor.eos(), startPos, null, stateOut)

    /**
     * Resumes a previous match from its saved choice state: the engine
     * side of `!cursor_next`. Enters the loop failing, which pops the
     * most recent choice point exactly as an in-match failure would.
     */
    fun resume(cursor: RxCursor, state: EngineState, stateOut: Array<EngineState?>): Int =
        run(cursor, cursor.target(), cursor.eos(), 0, state, stateOut)

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

        while (true) {
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
                    /* A literal whose last character is a bare CR does not
                     * match into a CR LF pair: on an NFG backend the pair is
                     * one grapheme and a lone CR is not it. A literal that
                     * carries the "\r\n" itself compares both characters and
                     * is unaffected. */
                    val compared = if ((flags and RxProgram.F_IGNOREMARK) != 0) {
                        /* The mark-insensitive comparisons live in the
                         * runtime, same as the bytecode path's eqatim. */
                        pos + text.length <= eos &&
                            literalIgnoreMark(target, text, pos, ignoreCase)
                    } else {
                        pos + text.length <= eos &&
                            target.regionMatches(pos, text, 0, text.length, ignoreCase)
                    }
                    val hit = compared &&
                        !(text.isNotEmpty() && text[text.length - 1] == '\r' &&
                            pos + text.length < eos && target[pos + text.length] == '\n')
                    if (hit == ((flags and RxProgram.F_NEGATE) != 0)) {
                        failed = true
                    } else {
                        if ((flags and RxProgram.F_ZEROWIDTH) == 0) pos += text.length
                        pc += 3
                    }
                }

                RxProgram.ONE -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val pred = pool[code[pc + 1]] as RxProgram.CharPred
                        val cp = atomAt(target, pos, eos)
                        if (!pred.holds(cp)) {
                            failed = true
                        } else {
                            pos += atomWidth(cp)
                            pc += 2
                        }
                    }
                }

                RxProgram.ANY -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        pos += atomWidth(atomAt(target, pos, eos))
                        pc += 1
                    }
                }

                RxProgram.NL -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val cp = target.codePointAt(pos)
                        if (cp != '\n'.code && cp != '\r'.code) {
                            failed = true
                        } else {
                            pos += 1
                            if (cp == '\r'.code && pos < eos && target[pos] == '\n')
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
                        val cp = atomAt(target, pos, eos)
                        if ((cp == code[pc + 1]) == (code[pc + 2] != 0)) {
                            failed = true
                        } else {
                            pos += atomWidth(cp)
                            pc += 3
                        }
                    }
                }

                RxProgram.DIGIT -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val cp = atomAt(target, pos, eos)
                        if (Character.isDigit(cp) == (code[pc + 1] != 0)) {
                            failed = true
                        } else {
                            pos += atomWidth(cp)
                            pc += 2
                        }
                    }
                }

                RxProgram.WORD -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val cp = atomAt(target, pos, eos)
                        val inClass = Character.isLetterOrDigit(cp) || cp == '_'.code
                        if (inClass == (code[pc + 1] != 0)) {
                            failed = true
                        } else {
                            pos += atomWidth(cp)
                            pc += 2
                        }
                    }
                }

                RxProgram.SPACE -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val cp = atomAt(target, pos, eos)
                        val inClass = cp == RxProgram.CRLF || Character.isWhitespace(cp)
                        if (inClass == (code[pc + 1] != 0)) {
                            failed = true
                        } else {
                            pos += atomWidth(cp)
                            pc += 2
                        }
                    }
                }

                RxProgram.RANGE1 -> {
                    if (pos >= eos) {
                        failed = true
                    } else {
                        val cp = atomAt(target, pos, eos)
                        val inRange = cp >= code[pc + 1] && cp <= code[pc + 2]
                        if (inRange == (code[pc + 3] != 0)) {
                            failed = true
                        } else {
                            pos += atomWidth(cp)
                            pc += 4
                        }
                    }
                }

                RxProgram.ANCHOR -> {
                    if (!anchorHolds(code[pc + 1], target, pos, eos)) {
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
                    choiceTop += CHOICE_WIDTH
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
                        pos += atomWidth(atomAt(target, pos, eos))
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
                        pred.holds(atomAt(target, pos, eos)) != negate
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
                        /* The fused pair carries no single codepoint's
                         * property: on MoarVM not even <:Space> holds of the
                         * CR LF grapheme. So the pair fails every positive
                         * property test without asking the runtime. */
                        val cp = atomAt(target, pos, eos)
                        val holds = cp != RxProgram.CRLF &&
                            charProp(cursor, pool[code[pc + 1]] as String, pos)
                        if (holds == ((flags and RxProgram.F_NEGATE) != 0)) {
                            failed = true
                        } else {
                            if ((flags and RxProgram.F_ZEROWIDTH) == 0) {
                                pos += atomWidth(cp)
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

                else -> throw IllegalStateException("bad opcode " + code[pc])
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
                    pendingFail = true
                    continue
                }
                choiceTop -= CHOICE_WIDTH
                pc = choices[choiceTop]
                pos = choices[choiceTop + 1]
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

        /** No match. */
        const val NO_MATCH = -1

        private const val CHOICE_WIDTH = 3   // pc, pos, pending-capture height
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

        private fun anchorHolds(kind: Int, target: String, pos: Int, eos: Int): Boolean =
            when (ANCHOR_KINDS[kind]) {
                RxTree.Anchor.Kind.BOS -> pos == 0
                RxTree.Anchor.Kind.EOS -> pos == eos
                RxTree.Anchor.Kind.BOL -> pos == 0 || target[pos - 1] == '\n'
                RxTree.Anchor.Kind.EOL -> pos == eos || target[pos] == '\n'
                RxTree.Anchor.Kind.LWB ->
                    pos < eos && isWord(target, pos) && (pos == 0 || !isWord(target, pos - 1))
                RxTree.Anchor.Kind.RWB ->
                    pos > 0 && isWord(target, pos - 1) && (pos == eos || !isWord(target, pos))
                /* The two constant assertions: `<?>` always holds, and the
                 * fail anchor is how QAST::Compiler spells a branch that is
                 * dead. */
                RxTree.Anchor.Kind.PASS -> true
                RxTree.Anchor.Kind.FAIL -> false
            }

        private fun isWord(target: String, pos: Int): Boolean {
            val c = target[pos]
            return Character.isLetterOrDigit(c) || c == '_'
        }

        /*
         * The atom at a position: the codepoint there, except that a CR
         * directly followed by LF reads as the fused pair. See
         * RxProgram.CRLF for why the fusion is synthesized here.
         */
        private fun atomAt(target: String, pos: Int, eos: Int): Int {
            val c = target[pos]
            return if (c == '\r' && pos + 1 < eos && target[pos + 1] == '\n') RxProgram.CRLF
            else target.codePointAt(pos)
        }

        /** How many chars the atom covers -- two for the fused pair. */
        private fun atomWidth(cp: Int): Int =
            if (cp == RxProgram.CRLF) 2 else Character.charCount(cp)

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
