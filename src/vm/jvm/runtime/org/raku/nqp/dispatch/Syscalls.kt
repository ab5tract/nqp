package org.raku.nqp.dispatch

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.CallCaptureInstance

/** The arguments a syscall was invoked with. */
class SyscallArgs(val tc: ThreadContext, val descriptor: CallSiteDescriptor,
                  val args: Array<Any?>) {
    val count: Int
        get() = args.size

    fun obj(index: Int): SixModelObject? = args[index] as SixModelObject?
    fun int(index: Int): Long = args[index] as Long
    fun num(index: Int): Double = args[index] as Double
    fun str(index: Int): String? = args[index] as String?

    fun capture(index: Int): CallCaptureInstance = Captures.asCapture(tc, obj(index))

    /** The dispatch whose callback is running, which these operate upon. */
    val recording: DispatchRecord
        get() = Dispatch.currentRecording(tc)
}

/**
 * A function the VM provides, reachable through the boot-syscall dispatcher.
 * The expected argument kinds are declared so that a wrong call is reported
 * where it is made rather than going wrong inside.
 */
class Syscall(
    val name: String,
    private val kinds: Array<ArgKind>,
    private val minArgs: Int,
    private val impl: (SyscallArgs) -> DispatchValue,
) {
    val maxArgs: Int
        get() = kinds.size

    /** Checks a callsite is one this syscall can be invoked with. */
    fun checkArgs(tc: ThreadContext, descriptor: CallSiteDescriptor) {
        if (descriptor.names != null)
            throw ExceptionHandling.dieInternal(tc,
                "Cannot pass named arguments to the '$name' syscall")
        val got = descriptor.argFlags.size
        if (got < minArgs || got > maxArgs)
            throw ExceptionHandling.dieInternal(tc,
                "Wrong number of arguments to the '$name' syscall; got $got, need " +
                (if (minArgs == maxArgs) "$minArgs" else "$minArgs..$maxArgs"))
        for (i in 0 until got) {
            val kind = ArgKind.ofFlag(descriptor.argFlags[i])
            if (kind != kinds[i])
                throw ExceptionHandling.dieInternal(tc,
                    "Argument $i to the '$name' syscall is a ${kind.name.lowercase()}, " +
                    "but should be a ${kinds[i].name.lowercase()}")
        }
    }

    fun call(tc: ThreadContext, descriptor: CallSiteDescriptor,
             args: Array<Any?>): DispatchValue =
        impl(SyscallArgs(tc, descriptor, args))
}

/**
 * The syscall table. These are what a dispatcher uses to look at the arguments
 * it was given, to say what it relied upon, and to say what should happen.
 */
object Syscalls {
    private val table = HashMap<String, Syscall>()

    @JvmStatic
    fun find(tc: ThreadContext, name: String?): Syscall =
        table.get(name)
            ?: throw ExceptionHandling.dieInternal(tc, "No VM syscall with name '$name'")

    private fun define(name: String, vararg kinds: ArgKind, min: Int = -1,
                       impl: (SyscallArgs) -> DispatchValue) {
        table.put(name, Syscall(name, arrayOf(*kinds),
            if (min < 0) kinds.size else min, impl))
    }

    /* Result shorthands; a syscall with nothing to say produces a null. */
    private val void = DispatchValue(ArgKind.OBJ, null)
    private fun obj(value: SixModelObject?) = DispatchValue(ArgKind.OBJ, value)
    private fun int(value: Long) = DispatchValue(ArgKind.INT, value)
    private fun bool(value: Boolean) = int(if (value) 1L else 0L)

    /* The file-stat handle and its lazy queries (see the syscalls below). */
    private class StatHandle(@JvmField val path: String, @JvmField val follow: Boolean)
    private fun statHandle(a: SyscallArgs, i: Int): StatHandle =
        (a.obj(i) as org.raku.nqp.sixmodel.reprs.JavaObjectWrapper).theObject as StatHandle
    private fun linkOpts(h: StatHandle): Array<java.nio.file.LinkOption> =
        if (h.follow) arrayOf() else arrayOf(java.nio.file.LinkOption.NOFOLLOW_LINKS)
    /* uid/gid/mode/inode/dev/islnk through the unix attribute view; 0 when
     * the file is missing or the view is unsupported, as a failed stat. */
    private fun unixStatAttr(h: StatHandle, flag: Long): Long {
        val p = java.nio.file.Paths.get(h.path); val o = linkOpts(h)
        return try {
            when (flag.toInt()) {
                Ops.STAT_ISLNK -> if (java.nio.file.Files.isSymbolicLink(p)) 1L else 0L
                Ops.STAT_UID -> (java.nio.file.Files.getAttribute(p, "unix:uid", *o) as Number).toLong()
                Ops.STAT_GID -> (java.nio.file.Files.getAttribute(p, "unix:gid", *o) as Number).toLong()
                Ops.STAT_PLATFORM_MODE -> (java.nio.file.Files.getAttribute(p, "unix:mode", *o) as Number).toLong()
                Ops.STAT_PLATFORM_INODE -> (java.nio.file.Files.getAttribute(p, "unix:ino", *o) as Number).toLong()
                Ops.STAT_PLATFORM_DEV -> (java.nio.file.Files.getAttribute(p, "unix:dev", *o) as Number).toLong()
                else -> 0L
            }
        } catch (e: Exception) { 0L }
    }
    private fun statTimeNanos(h: StatHandle, flag: Long): Long {
        val p = java.nio.file.Paths.get(h.path); val o = linkOpts(h)
        return try {
            val ft = when (flag.toInt()) {
                Ops.STAT_MODIFYTIME -> java.nio.file.Files.getLastModifiedTime(p, *o)
                Ops.STAT_ACCESSTIME -> java.nio.file.Files.getAttribute(p, "lastAccessTime", *o) as java.nio.file.attribute.FileTime
                Ops.STAT_CREATETIME -> java.nio.file.Files.getAttribute(p, "creationTime", *o) as java.nio.file.attribute.FileTime
                Ops.STAT_CHANGETIME -> java.nio.file.Files.getAttribute(p, "unix:ctime", *o) as java.nio.file.attribute.FileTime
                else -> return 0L
            }
            val i = ft.toInstant(); i.epochSecond * 1_000_000_000L + i.nano
        } catch (e: Exception) { 0L }
    }

    private val OBJ = ArgKind.OBJ
    private val INT = ArgKind.INT
    private val NUM = ArgKind.NUM
    private val STR = ArgKind.STR

    init {
        /* ----- looking at captures ----- */

        define("capture-num-args", OBJ) {
            int(it.capture(0).descriptor!!.argFlags.size.toLong())
        }
        define("capture-arg-value", OBJ, INT) {
            obj(Ops.captureposarg(it.obj(0), it.int(1), it.tc))
        }
        define("capture-arg-prim-spec", OBJ, INT) {
            int(Ops.captureposprimspec(it.obj(0), it.int(1), it.tc))
        }
        define("capture-pos-args", OBJ) { args ->
            val capture = args.capture(0)
            val list = newList(args.tc)
            for (i in 0 until capture.descriptor!!.numPositionals)
                Ops.push(list, Ops.captureposarg(capture, i.toLong(), args.tc), args.tc)
            obj(list)
        }
        define("capture-named-args", OBJ) {
            obj(Ops.capturenamedshash(it.obj(0), it.tc))
        }
        define("capture-names-list", OBJ) { args ->
            /* A native str array, as on MoarVM: the dispatchers read the
             * names back with nqp::atpos_s. */
            val tc = args.tc
            val strArrayType = tc.gc.BOOTStrArray!!
            val list = strArrayType.st.REPR.allocate(tc, strArrayType.st)
            val names = args.capture(0).descriptor!!.names
            if (names != null)
                for (name in names)
                    Ops.push_s(list, name, tc)
            obj(list)
        }
        define("capture-is-literal-arg", OBJ, INT) { isLiteralArg(it) }

        /* ----- registering dispatchers ----- */

        define("dispatcher-register", STR, OBJ, OBJ, min = 2) { args ->
            args.tc.gc.dispatchers.register(args.tc, args.str(0)!!, args.obj(1),
                if (args.count > 2) args.obj(2) else null)
            void
        }

        /* ----- what a dispatch should do ----- */

        define("dispatcher-delegate", STR, OBJ) { args ->
            val record = args.recording
            record.delegate(args.tc.gc.dispatchers.find(args.tc, args.str(0)), args.obj(1))
            void
        }

        /* ----- tracking values ----- */

        define("dispatcher-track-arg", OBJ, INT) {
            obj(it.recording.trackArg(it.obj(0), it.int(1).toInt()))
        }
        define("dispatcher-track-attr", OBJ, OBJ, STR) {
            obj(it.recording.trackAttr(it.obj(0), it.obj(1), it.str(2)!!))
        }
        define("dispatcher-track-how", OBJ) {
            obj(it.recording.trackHow(it.obj(0)))
        }
        define("dispatcher-track-unbox-int", OBJ) {
            obj(it.recording.trackUnbox(it.obj(0), ArgKind.INT))
        }
        define("dispatcher-track-unbox-num", OBJ) {
            obj(it.recording.trackUnbox(it.obj(0), ArgKind.NUM))
        }
        define("dispatcher-track-unbox-str", OBJ) {
            obj(it.recording.trackUnbox(it.obj(0), ArgKind.STR))
        }
        define("dispatcher-track-resume-state") {
            obj(it.recording.trackResumeState())
        }
        define("dispatcher-index-lookup-table", OBJ, OBJ) { args ->
            val record = args.recording
            obj(record.trackLookup(ValueSource.Literal(OBJ, args.obj(0)), args.obj(1)))
        }
        define("dispatcher-index-tracked-lookup-table", OBJ, OBJ) { args ->
            val record = args.recording
            /* Type and concreteness guards on the table itself, as MoarVM
             * enforces: the table is read anew each run (an attribute of the
             * invocant's HOW, typically), and a run that reads a null or
             * not-yet-built table must be a guard miss, not a crash inside
             * the lookup. */
            val table = record.sourceOf(args.obj(0))
            record.guardType(table)
            record.guardConcreteness(table)
            obj(record.trackLookup(table, args.obj(1)))
        }

        /* ----- what a dispatch relied upon ----- */

        define("dispatcher-guard-type", OBJ) { args ->
            val record = args.recording
            record.guardType(record.sourceOf(args.obj(0)))
            void
        }
        define("dispatcher-guard-concreteness", OBJ) { args ->
            val record = args.recording
            record.guardConcreteness(record.sourceOf(args.obj(0)))
            void
        }
        define("dispatcher-guard-literal", OBJ) { args ->
            val record = args.recording
            record.guardLiteral(record.sourceOf(args.obj(0)))
            void
        }
        define("dispatcher-guard-not-literal-obj", OBJ, OBJ) { args ->
            val record = args.recording
            record.guardNotLiteralObj(record.sourceOf(args.obj(0)), args.obj(1))
            void
        }
        define("dispatcher-guard-hll", OBJ) { args ->
            val record = args.recording
            record.guardHll(record.sourceOf(args.obj(0)))
            void
        }

        /* ----- deriving captures ----- */

        define("dispatcher-drop-arg", OBJ, INT) { args ->
            val record = args.recording
            obj(record.derive(record.shapeOf(args.obj(0)).drop(args.tc, args.int(1).toInt())))
        }
        define("dispatcher-drop-n-args", OBJ, INT, INT) { args ->
            val record = args.recording
            var shape = record.shapeOf(args.obj(0))
            val index = args.int(1).toInt()
            for (i in 0 until args.int(2).toInt())
                shape = shape.drop(args.tc, index)
            obj(record.derive(shape))
        }
        define("dispatcher-insert-arg", OBJ, INT, OBJ) { args ->
            val record = args.recording
            val tracked = args.obj(2)
            obj(record.derive(record.shapeOf(args.obj(0)).insert(args.tc, args.int(1).toInt(),
                record.sourceOf(tracked), record.valueOf(tracked).kind)))
        }
        define("dispatcher-insert-arg-literal-obj", OBJ, INT, OBJ) { args ->
            obj(insertLiteral(args, DispatchValue(OBJ, args.obj(2))))
        }
        define("dispatcher-insert-arg-literal-int", OBJ, INT, INT) { args ->
            obj(insertLiteral(args, DispatchValue(INT, args.int(2))))
        }
        define("dispatcher-insert-arg-literal-num", OBJ, INT, NUM) { args ->
            obj(insertLiteral(args, DispatchValue(NUM, args.num(2))))
        }
        define("dispatcher-insert-arg-literal-str", OBJ, INT, STR) { args ->
            obj(insertLiteral(args, DispatchValue(STR, args.str(2))))
        }
        define("dispatcher-replace-arg", OBJ, INT, OBJ) { args ->
            val record = args.recording
            val tracked = args.obj(2)
            obj(record.derive(record.shapeOf(args.obj(0)).replace(args.tc, args.int(1).toInt(),
                record.sourceOf(tracked), record.valueOf(tracked).kind)))
        }
        define("dispatcher-replace-arg-literal-obj", OBJ, INT, OBJ) { args ->
            val record = args.recording
            obj(record.derive(record.shapeOf(args.obj(0)).replace(args.tc, args.int(1).toInt(),
                ValueSource.Literal(OBJ, args.obj(2)), OBJ)))
        }
        define("dispatcher-is-arg-literal", OBJ, INT) { isLiteralArg(it) }

        /* ----- resumption ----- */

        define("dispatcher-set-resume-init-args", OBJ) { args ->
            args.recording.setResumeInitArgs(args.obj(0))
            void
        }
        define("dispatcher-get-resume-init-args") { args ->
            obj(args.recording.currentLevel().initCapture)
        }
        define("dispatcher-set-resume-state", OBJ) { args ->
            val record = args.recording
            val tracked = args.obj(0)
            val value = record.valueOf(tracked)
            if (value.kind != OBJ)
                throw ExceptionHandling.dieInternal(args.tc,
                    "Can only set an object resume state")
            record.setResumeState(record.sourceOf(tracked), value.obj)
            void
        }
        define("dispatcher-set-resume-state-literal", OBJ) { args ->
            args.recording.setResumeStateLiteral(args.obj(0))
            void
        }
        define("dispatcher-get-resume-state") { args ->
            val record = args.recording
            obj(record.resumeState(record.levels.size - 1))
        }
        define("dispatcher-next-resumption", OBJ, min = 0) { args ->
            val record = args.recording
            val capture = if (args.count > 0) args.obj(0) else null
            if (capture != null) record.shapeOf(capture)
            val found = Dispatch.findResumption(args.tc, record.resumeKindForNext(),
                record.levels.size)
            if (found == null) {
                record.currentLevel().noNextResumption = true
                bool(false)
            }
            else {
                record.outcome = RecordedOutcome.NextResumption(capture)
                bool(true)
            }
        }
        /**
         * Will a bind failure in the frame we are in be turned into a
         * resumption of the dispatch that invoked it? A routine asks this to
         * decide whether to report a bind failure or leave it to the dispatch.
         */
        define("bind-will-resume-on-failure") { args ->
            val frame = args.tc.frame
            val record = frame.dispatchRecord
            val control = if (record != null) record.program?.bindControl ?: record.bindControl
                          else frame.dispatchProgram?.bindControl
            bool(control != null)
        }
        define("dispatcher-resume-on-bind-failure", INT) { args ->
            args.recording.setBindControl(BindControl(args.int(0), null, false))
            void
        }
        define("dispatcher-resume-after-bind", INT, INT) { args ->
            args.recording.setBindControl(BindControl(args.int(0), args.int(1), true))
            void
        }

        /* ----- callsite state ----- */

        define("dispatcher-inline-cache-size") { int(it.recording.cacheSize.toLong()) }
        define("dispatcher-do-not-install") {
            it.recording.setDoNotInstall()
            void
        }

        /* ----- odds and ends the languages need ----- */

        define("has-type-check-cache", OBJ) { args ->
            val type = args.obj(0)
            bool(type != null && type.stInitialized && type.st.TypeCheckCache != null)
        }
        define("type-check-mode-flags", OBJ) { args ->
            val type = args.obj(0)
            int(if (type == null || !type.stInitialized) 0L
                else (type.st.ModeFlags and STable.TYPE_CHECK_CACHE_FLAG_MASK).toLong())
        }
        define("code-is-stub", OBJ) { args ->
            val code = args.obj(0)
            bool(code is CodeRef && code.isCompilerStub)
        }
        /* The capture-lex family works on the bare code handle, the way the
         * raku-capture-lex(-callers) dispatchers hand it over after they
         * unwrap the Code object. Frames are matched the way CallFrame's
         * own outer hunt does: by method handle and compilation unit, which
         * holds across CodeRef clones. */
        define("try-capture-lex", OBJ) { args ->
            val code = args.obj(0) as? CodeRef
                ?: throw ExceptionHandling.dieInternal(args.tc,
                    "try-capture-lex requires a code handle")
            val wanted = code.staticInfo.outerStaticInfo
            val cur = args.tc.curFrame
            if (wanted != null && cur != null
                    && cur.codeRef.staticInfo.mh === wanted.mh
                    && cur.codeRef.staticInfo.compUnit === wanted.compUnit)
                code.outer = cur
            obj(null)
        }
        define("try-capture-lex-callers", OBJ) { args ->
            val code = args.obj(0) as? CodeRef
                ?: throw ExceptionHandling.dieInternal(args.tc,
                    "try-capture-lex-callers requires a code handle")
            val wanted = code.staticInfo.outerStaticInfo
            if (wanted != null) {
                var frame = args.tc.curFrame
                while (frame != null) {
                    if (frame.codeRef.staticInfo.mh === wanted.mh
                            && frame.codeRef.staticInfo.compUnit === wanted.compUnit) {
                        code.outer = frame
                        break
                    }
                    frame = frame.caller
                }
            }
            obj(null)
        }
        define("get-code-outer-ctx", OBJ) { args ->
            val code = args.obj(0) as? CodeRef
                ?: throw ExceptionHandling.dieInternal(args.tc,
                    "get-code-outer-ctx requires a code handle")
            val outer = code.outer
                ?: throw ExceptionHandling.dieInternal(args.tc,
                    "Specified code ref has no outer")
            val contextRef = args.tc.gc.ContextRef!!
            val wrap = contextRef.st.REPR.allocate(args.tc, contextRef.st)
            (wrap as org.raku.nqp.sixmodel.reprs.ContextRefInstance).context = outer
            obj(wrap)
        }
        define("set-cur-hll-config-key", STR, OBJ) { args ->
            val config = args.tc.gc.getHLLConfigFor(
                args.tc.frame.codeRef.staticInfo.compUnit.hllName())
            when (args.str(0)) {
                "uint_box" -> config.uintBoxType = args.obj(1)
                else -> throw ExceptionHandling.dieInternal(args.tc,
                    "Unsupported config key '${args.str(0)}' for set-cur-hll-config-key")
            }
            void
        }

        /* ----- file stat: MoarVM's file-stat / stat-flags family -----
         * An opaque stat handle carries the path and follow-symlinks flag
         * and is queried lazily through the existing stat ops, so a missing
         * file yields EXISTS=0 rather than throwing (IO::Path relies on
         * that). Backing the moar-side IO::Path code with these lets its
         * `#?if moar` branches run here unchanged. */
        define("file-stat", STR, INT) { args ->
            obj(org.raku.nqp.runtime.BootJavaInterop.RuntimeSupport.boxJava(
                StatHandle(args.str(0)!!, args.int(1) != 0L), args.tc.gc.BOOTJava!!.st))
        }
        define("stat-flags", OBJ, INT) { args ->
            val h = statHandle(args, 0); val flag = args.int(1)
            int(when (flag.toInt()) {
                Ops.STAT_EXISTS, Ops.STAT_FILESIZE, Ops.STAT_ISDIR,
                Ops.STAT_ISREG, Ops.STAT_ISDEV -> Ops.stat(h.path, flag)
                else -> unixStatAttr(h, flag)
            })
        }
        define("stat-time-nanos", OBJ, INT) { args ->
            val h = statHandle(args, 0); val flag = args.int(1)
            int(statTimeNanos(h, flag))
        }
        define("stat-is-readable", OBJ) { int(Ops.filereadable(statHandle(it, 0).path, it.tc)) }
        define("stat-is-writable", OBJ) { int(Ops.filewritable(statHandle(it, 0).path, it.tc)) }
        define("stat-is-executable", OBJ) { int(Ops.fileexecutable(statHandle(it, 0).path, it.tc)) }

        /* ----- JVM nested compilation units ----- */

        /* The class an in-memory compiled block belongs to, when one was
         * retained for nested-unit persistence; empty string otherwise.
         * Syscalls rather than ops so the compiler itself can call them
         * without a bootstrap-breaking new op. */
        define("jvm-class-of-cuid", STR) { args ->
            DispatchValue(ArgKind.STR, Ops.jvmclassofcuid(args.str(0), args.tc))
        }

        /* Writes the compiling unit as a unit artifact (programs +
         * serialized context + block table, no class file). A syscall so
         * HLL::Backend::JVM, compiled by the stage0 compiler, can reach it. */
        define("jvm-write-unit", OBJ, OBJ, STR) { args ->
            org.raku.nqp.runtime.unit.UnitWriter.write(args.obj(0), args.obj(1), args.str(2), args.tc)
            void
        }

        /* Builds the compiling unit's record in memory -- the record road
         * for a script, an EVAL, a BEGIN-time unit, or a --target=jar with
         * no --output under NQP_UNIT -- for nqp::loadcompunit to turn into
         * a ProgramUnit: no zip, no class. */
        define("jvm-build-unit", OBJ, OBJ) { args ->
            val res = org.raku.nqp.runtime.EvalResult()
            res.record = org.raku.nqp.runtime.unit.UnitWriter.record(args.obj(0), args.obj(1), args.tc)
            obj(res)
        }

        /* Loads a nested unit embedded in the current unit's jar and
         * installs its code refs into the current unit's qbid table. */
        define("jvm-claim-nested", STR, OBJ, OBJ) { args ->
            Ops.jvmclaimnested(args.str(0), args.obj(1), args.obj(2), args.tc)
            void
        }

        /* Runs a claimed nested unit's deserialization code once the
         * enclosing deserialization has populated the SC. */
        define("jvm-finish-nested", STR) { args ->
            Ops.jvmfinishnested(args.str(0), args.tc)
            void
        }

        /* Re-points code objects that a BEGIN-time dynamic compilation
         * bound to its nested unit's code refs at the invoking unit's own
         * emission of the same cuids. The registry maps cuid to a list of
         * [original-code-object-or-null, clone...]; the original takes the
         * unit's code ref itself, each clone a fresh clone of it whose
         * outer is then captured from the live frames at invocation. Run
         * from a unit's post-deserialize fixups, so the current frame
         * belongs to the unit whose code refs are wanted. */
        define("jvm-repoint-dynamic-code", OBJ, OBJ) { args ->
            val registry = args.obj(0)
            val codeType = args.obj(1)
            val tc = args.tc
            val cu = tc.curFrame!!.codeRef.staticInfo.compUnit
            val trace = Ops.REPOINT_TRACE
            if (trace)
                System.err.println("nqp repoint: unit=" + cu.unitId()
                    + " from frame '" + tc.curFrame!!.codeRef.name
                    + "' cuid=" + tc.curFrame!!.codeRef.staticInfo.uniqueId)
            if (registry is org.raku.nqp.sixmodel.reprs.VMHashInstance) {
                for ((cuid, entries) in registry.storage) {
                    val target = cu.lookupCodeRef(cuid)
                    if (trace)
                        System.err.println("nqp repoint:   cuid=" + cuid + " -> " +
                            (if (target == null) "MISS (left alone)"
                             else "'" + target.name + "' cuid=" +
                                  target.staticInfo.uniqueId))
                    if (target == null) continue
                    if (entries == null) continue
                    val n = entries.elems(tc)
                    var i = 0L
                    while (i < n) {
                        val codeObj = entries.at_pos_boxed(tc, i)
                        if (codeObj != null && Ops.isnull(codeObj) == 0L) {
                            val newDo = if (i == 0L) target else target.clone(tc)
                            codeObj.bind_attribute_boxed(tc, codeType, "${'$'}!do",
                                org.raku.nqp.sixmodel.STable.NO_HINT, newDo)
                            if (codeObj.sc != null)
                                Ops.scwbObject(tc, codeObj)
                            Ops.setcodeobj(newDo, codeObj, tc)
                        }
                        i++
                    }
                }
            }
            void
        }
    }

    private fun insertLiteral(args: SyscallArgs, value: DispatchValue): SixModelObject {
        val record = args.recording
        return record.derive(record.shapeOf(args.obj(0)).insert(args.tc, args.int(1).toInt(),
            ValueSource.Literal(value.kind, value.value), value.kind))
    }

    /**
     * Is an argument one the dispatch can treat as fixed? On this backend a
     * callsite does not record which of its arguments were literals in the
     * source, so the answer is yes exactly for the ones a dispatcher inserted.
     */
    private fun isLiteralArg(args: SyscallArgs): DispatchValue {
        val shape = args.recording.shapeOf(args.obj(0))
        val index = args.int(1).toInt()
        return bool(index < shape.sources.size && shape.sources[index] is ValueSource.Literal)
    }

    private fun newList(tc: ThreadContext): SixModelObject {
        val type = tc.frame.codeRef.staticInfo.compUnit.hllConfig.listType!!
        return type.st.REPR.allocate(tc, type.st)
    }
}