package org.raku.nqp.dispatch

import java.io.File
import java.io.PrintWriter
import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.CodeRef
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationContext
import org.raku.nqp.sixmodel.SixModelObject

/**
 * `NQP_DISPATCH_DUMP=<path>`: at exit, every registered callsite's installed
 * programs in a normalised text form -- one `site` line per callsite, one
 * `prog` line per program. Every object, STable and code reference is named
 * by its serialization context (handle and root-set index) when it has one,
 * and marked `NP(...)` with the reason when it has none, so two dumps of the
 * same build compare textually and the unpersistable programs show their
 * cause. Milestone 7 Phase C's spike (C0) sizes the persisted miss from
 * these dumps; `tools/build/dispatch-dump-diff.raku` in Rakudo reads them.
 */
object DispatchDump {
    private val path: String? = System.getenv("NQP_DISPATCH_DUMP")

    @JvmStatic
    fun installIfRequested() {
        val p = path ?: return
        Runtime.getRuntime().addShutdownHook(Thread { write(p) })
    }

    private fun write(p: String) {
        PrintWriter(File(p).bufferedWriter()).use { out ->
            for (site in DispatchBootstrap.sites()) {
                val programs = site.programs
                out.println("site ${site.identity ?: "anon"} ${site.linkedName ?: "?"} " +
                    "${programs.size}${if (site.fromIndy) " indy" else ""}")
                for ((i, prog) in programs.withIndex())
                    out.println("prog $i ${describe(prog)}")
            }
        }
    }

    /* ----- references ----- */

    private fun typeName(obj: SixModelObject): String =
        if (obj.stInitialized) obj.st.debugName ?: "?" else "?"

    private fun objectIndex(sc: SerializationContext, obj: SixModelObject): Int {
        val i = sc.getObjectIndex(obj)
        return if (i >= 0 && i < sc.objectCount() && sc.getObject(i) === obj) i else -1
    }

    private fun codeIndex(sc: SerializationContext, obj: SixModelObject): Int {
        if (obj !is CodeRef) return -1
        val i = try { sc.getCodeIndex(obj) } catch (_: NullPointerException) { -1 }
        return if (i >= 0 && i < sc.coderefCount() && sc.getCodeRef(i) === obj) i else -1
    }

    private fun ref(obj: SixModelObject?): String {
        if (obj == null) return "null"
        val sc = obj.sc
        if (sc != null) {
            val oi = objectIndex(sc, obj)
            if (oi >= 0) return "obj:${sc.handle}:$oi"
            val ci = codeIndex(sc, obj)
            if (ci >= 0) return "code:${sc.handle}:$ci"
        }
        return "NP(obj:${obj.javaClass.simpleName}:${typeName(obj)}:${if (sc == null) "nosc" else "notroot"})"
    }

    private fun ref(st: STable?): String {
        if (st == null) return "null"
        val sc = st.sc
        if (sc != null) {
            val i = try { sc.getSTableIndex(st) } catch (_: NullPointerException) { -1 }
            if (i >= 0 && i < sc.stableCount() && sc.getSTable(i) === st)
                return "st:${sc.handle}:$i"
        }
        return "NP(st:${st.debugName}:${if (sc == null) "nosc" else "notroot"})"
    }

    private fun literal(kind: ArgKind, value: Any?): String = when (kind) {
        ArgKind.OBJ -> ref(value as SixModelObject?)
        ArgKind.STR -> "str:" + (value as String?)?.let { quote(it) }
        else -> "${kind.name.lowercase()}:$value"
    }

    private fun quote(s: String): String {
        val cut = if (s.length > 60) s.substring(0, 60) + "..." else s
        return "\"" + cut.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""
    }

    /* ----- the model ----- */

    private fun descriptor(csd: CallSiteDescriptor): String =
        "csd[" + csd.argFlags.joinToString(",") +
            (csd.names?.let { "|" + it.joinToString(",") } ?: "") + "]"

    private fun source(s: ValueSource): String = when (s) {
        is ValueSource.Arg -> "arg(${s.index})"
        is ValueSource.ResumeInitArg -> "rinit(${s.level},${s.index})"
        is ValueSource.Literal -> "lit(${literal(s.kind, s.value)})"
        is ValueSource.Attribute -> "attr(${source(s.from)},${ref(s.classHandle)},${s.name},${s.kind})"
        is ValueSource.How -> "how(${source(s.from)})"
        is ValueSource.Unbox -> "unbox(${source(s.from)},${s.kind})"
        is ValueSource.Lookup -> "lookup(${source(s.table)},${source(s.key)})"
        is ValueSource.ResumeState -> "rstate(${s.level})"
    }

    private fun guard(g: Guard): String = when (g) {
        is Guard.OfType -> "type(${source(g.on)},${ref(g.type)})"
        is Guard.Concreteness -> "conc(${source(g.on)},${g.concrete})"
        is Guard.Literal -> "lit(${source(g.on)},${literal(g.expected.kind, g.expected.value)})"
        is Guard.NotLiteralObj -> "notlit(${source(g.on)},${ref(g.rejected)})"
        is Guard.OfHll -> "hll(${source(g.on)},${g.hll?.name})"
    }

    private fun shape(c: CaptureShape): String =
        "shape(" + c.sources.joinToString(",") { source(it) } + ";" + descriptor(c.descriptor) + ")"

    private fun outcome(o: Outcome): String = when (o) {
        is Outcome.Value -> "value(${source(o.source)})"
        is Outcome.InvokeCode -> "invoke(${source(o.callee)},${shape(o.args)})"
        is Outcome.InvokeSyscall -> "syscall(${o.syscall.name},${shape(o.args)})"
    }

    fun describe(p: DispatchProgram): String {
        val sb = StringBuilder()
        sb.append(descriptor(p.descriptor))
        sb.append(" guards=[").append(p.guards.joinToString(";") { guard(it) }).append(']')
        sb.append(" outcome=").append(outcome(p.outcome))
        if (p.resumptions.isNotEmpty())
            sb.append(" resumptions=[").append(p.resumptions.joinToString(";") {
                "${it.dispatcher.id}:${shape(it.initArgs)}" }).append(']')
        if (p.resumeKind != ResumeKind.NONE) {
            sb.append(" resume=").append(p.resumeKind.name)
            sb.append(" levels=[").append(p.resumeLevels.joinToString(";") { l ->
                "${l.dispatcher.id}:${descriptor(l.initDescriptor)}:[" +
                    l.guards.joinToString(";") { guard(it) } + "]:" +
                    (l.newState?.let { source(it) } ?: "-") + ":" + l.requireNoFurther
            }).append(']')
        }
        p.bindControl?.let {
            sb.append(" bind=(${it.failureFlag},${it.successFlag},${it.onSuccessToo})")
        }
        p.bindFailureProgram?.let { sb.append(" bindfail=(").append(describe(it)).append(')') }
        return sb.toString()
    }
}
