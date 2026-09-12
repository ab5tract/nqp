package org.raku.nqp.truffle

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.Truffle

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source

/**
 * The one polyglot context of the process, shared by the code engine and
 * the grammar engine.
 *
 * It has to be a context rather than loose RootNodes: outside one, a root
 * node runs correctly and is never queued for compilation, so the partial
 * evaluation the engines exist for silently does not happen. And it has
 * to be ONE context. A context owns its Engine, and the Engine is the unit
 * of compilation -- its own compiler threads, its own options, its own
 * cache of compiled code. Until the two languages were merged (2026-09-11)
 * each engine built its own context, so `engine.CompilerThreads` applied
 * twice and a matcher and the block calling it lived in different
 * compilation worlds.
 *
 * Built on first use rather than at class load: the runtime looks the
 * engines up while deciding whether there are any, and starting a polyglot
 * context is far too much to do just to answer that.
 */
object NqpPolyglot {

    private val context: Context by lazy { build() }

    /**
     * Parses one encoded source -- a code program or a regex descriptor;
     * the language tells them apart by their wire magic -- and answers its
     * call target.
     *
     * The eval's own result is a polyglot Value, and calling through one
     * boxes every argument of every call, so the bare CallTarget is
     * collected from where parse left it instead. [name] is what the
     * Source is called in compilation traces and statistics.
     */
    @JvmStatic
    fun compile(encoded: String, name: String): CallTarget {
        context.eval(Source.newBuilder(NqpLanguage.ID, encoded, name).buildLiteral())
        return NqpLanguage.PARSED[encoded]
            ?: throw IllegalStateException("the language parsed nothing for: " + encoded.take(60))
    }

    private fun build(): Context {
        val ctx = Context.newBuilder(NqpLanguage.ID)
            .allowExperimentalOptions(true)
            .build()
        /* NQP_CODE_CLOSE_AT_EXIT=1: close the context at JVM exit so
         * engine-close reports (engine.CompilationStatistics) print. */
        if (System.getenv("NQP_CODE_CLOSE_AT_EXIT") != null) {
            Runtime.getRuntime().addShutdownHook(Thread {
                try { ctx.close(true) } catch (t: Throwable) { t.printStackTrace() }
            })
        }
        val runtime = Truffle.getRuntime().name
        if (!runtime.contains("GraalVM")) {
            /* The fallback interpreter does no partial evaluation, so the
             * engines would be a slower bytecode path with extra steps.
             * Saying so beats measuring it later and blaming Truffle. */
            System.err.println(
                "nqp: Truffle runtime is '$runtime', not an optimizing one;" +
                    " engine-run code and grammar matching will be slow." +
                    " Check --module-path and --add-modules on the runner.",
            )
        }
        return ctx
    }
}
