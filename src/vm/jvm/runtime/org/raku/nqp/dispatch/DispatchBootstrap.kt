package org.raku.nqp.dispatch

import java.lang.invoke.CallSite
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodHandles.Lookup
import java.lang.invoke.MethodType
import java.lang.invoke.MutableCallSite

import org.raku.nqp.runtime.CallSiteDescriptor
import org.raku.nqp.runtime.ThreadContext

/** A dispatcher found for this site's name, valid for one registry epoch;
 *  one volatile reference so a reader never pairs a fresh epoch with a
 *  stale dispatcher. */
class CachedDispatcher(@JvmField val dispatcher: Dispatcher, @JvmField val epoch: Int)

/**
 * A dispatch callsite, which doubles as its inline cache: the programs recorded
 * here, tried in turn. Each dispatch instruction in the bytecode gets one of
 * these, which is what gives a callsite its own cache without any bookkeeping.
 *
 * A site that runs hot compiles its programs into a MethodHandle guard chain
 * ([DispatchCompiler]); a site born from an invokedynamic instruction also
 * makes the chain its target, so settled dispatches bypass the interpreted
 * program loop entirely. Compilation waits for the heat threshold because
 * building a chain costs real time (MethodHandle combinators spin classes),
 * and most sites never run often enough to earn one back.
 */
class DispatchCallSite @JvmOverloads constructor(type: MethodType,
                       @JvmField val fromIndy: Boolean = false) : MutableCallSite(type) {
    @Volatile @JvmField var programs: Array<DispatchProgram> = emptyArray()

    /**
     * The dispatcher name and callsite shape of the instruction this site
     * belongs to, noted on the first dispatch through it. Both are constants
     * of the instruction, which is what lets a compiled target bind them.
     */
    @JvmField var linkedName: String? = null
    @JvmField var staticDescriptor: CallSiteDescriptor? = null

    /** "<unit id>#<program index>#<ordinal>" for a site of a store-backed
     *  unit's program; null for an anonymous site (an in-memory unit, the
     *  helper sites in Ops, Rakudo's rv-decont site, the indy road). Phase
     *  C keys unit.dispatch by it. Set once at construction. */
    @JvmField var identity: String? = null

    /** The dispatcher this site's instruction names, found once per
     *  registry epoch rather than on every miss. Dispatcher and epoch
     *  travel together in one immutable holder, published volatile. */
    @Volatile @JvmField var cachedDispatcher: CachedDispatcher? = null

    /**
     * The compiled guard chain, as (ThreadContext, Object[])void, once the
     * site has proven hot. Helper-made sites run it straight from the
     * dispatch entry; indy-born sites also install its adapted form as the
     * invokedynamic target. The volatile store publishes the fields the
     * chain was compiled from.
     */
    @Volatile @JvmField var chain: MethodHandle? = null

    /** Interpreted dispatches seen so far; crossing the threshold compiles. */
    @JvmField var heat: Int = 0

    /**
     * Run after [reset] has emptied the site: a cache layered over this one
     * (the code engine's folded replay prefix) must forget with it.
     */
    @JvmField var onReset: Runnable? = null

    /**
     * The target this site was linked with, kept so the site can be made cold
     * again. See [DispatchBootstrap.resetAll].
     */
    @JvmField var coldTarget: MethodHandle? = null

    /**
     * Forgets everything recorded here, returning the site to the state it was
     * linked in. The programs cached at a callsite guard on the types of the
     * run that recorded them, and those belong to one GlobalContext; a process
     * that runs unrelated programs in turn must not carry them over.
     */
    fun reset() {
        programs = emptyArray()
        chain = null
        heat = 0
        linkedName = null
        staticDescriptor = null
        cachedDispatcher = null
        coldTarget?.let { if (fromIndy) setTarget(it) }
        onReset?.run()
    }

    /**
     * Adds a program to the cache. Past the limit the callsite is megamorphic
     * and we stop growing: dispatches still work by recording each time.
     */
    fun install(program: DispatchProgram) {
        val current = programs
        if (current.size < Dispatch.MAX_PROGRAMS) {
            programs = current + program
            /* A hot site stays compiled as its cache grows; a cold one waits
             * for the dispatch entry to see it cross the threshold. */
            if (chain != null || heat >= DispatchCompiler.threshold)
                recompile()
        }
    }

    /** Compiles the current programs into a chain, if they compile. */
    fun recompile() {
        val compiled = DispatchCompiler.compileChain(this) ?: return
        chain = compiled
        if (fromIndy)
            setTarget(DispatchCompiler.adaptToIndy(compiled, type()))
    }
}

/** Links dispatch instructions to the dispatch machinery. */
object DispatchBootstrap {
    /**
     * Every callsite linked in this process. A long-lived process that runs
     * unrelated programs in turn -- the eval server -- loads the compilation
     * unit once, so its dispatch instructions, and the inline caches they
     * carry, are shared by every run. Each run builds its own GlobalContext
     * and so its own type universe, which a program recorded by an earlier run
     * can never match; the re-recording that follows re-enters the same
     * callsite and does not terminate. Handing each run cold caches keeps the
     * loaded class -- which is the expensive part -- without carrying the
     * recordings across.
     */
    private val linked = java.util.concurrent.ConcurrentLinkedQueue<DispatchCallSite>()

    /**
     * Per-run caches owned by layers above this one. The HLL runtime keeps
     * dispatch state of its own that must go cold with everything else --
     * rakudo's per-routine rv-decont sites held every run's routines, and
     * through them each run's whole serialization-context graph, ~180MB a
     * run -- but it cannot be named from here: nqp does not see rakudo.
     * Whoever owns such a cache registers its clearing instead.
     */
    private val resettables = java.util.concurrent.CopyOnWriteArrayList<Runnable>()

    @JvmStatic
    fun registerResettable(action: Runnable) {
        resettables.add(action)
    }

    /**
     * Registers a helper-made callsite for the per-run reset -- the code
     * engine's per-instruction sites use this: their programs record
     * against one GlobalContext's types and the program objects survive
     * eval-server runs (NqpLanguage keeps parsed call targets by source).
     */
    @JvmStatic
    fun registerSite(site: DispatchCallSite) {
        linked.add(site)
    }

    /** Makes every callsite in this process cold again. */
    @JvmStatic
    fun resetAll() {
        for (site in linked) site.reset()
        org.raku.nqp.runtime.Ops.resetHelperDispatchSites()
        org.raku.nqp.runtime.GrammarEngines.clearProgramCache()
        org.raku.nqp.runtime.CodeEngines.clearProgramCache()
        for (action in resettables) action.run()
    }

    @JvmStatic
    fun dispatch_noa(caller: Lookup, indyName: String, type: MethodType): CallSite {
        try {
            val handlerType = MethodType.methodType(Void.TYPE, DispatchCallSite::class.java,
                Class::class.java, String::class.java, Integer.TYPE, ThreadContext::class.java,
                Array<Any>::class.java)
            val handler = caller.findStatic(Dispatch::class.java, "dispatch", handlerType)

            /* Curry the callsite and the linking class in -- the class named
             * the callsite-descriptor table csIdx pointed into on the class
             * road; that table is gone, so Dispatch.dispatch refuses any
             * non-negative index -- and gather the dispatch arguments (which
             * follow the dispatcher name, callsite index and thread context)
             * into an array. */
            val site = DispatchCallSite(type, fromIndy = true)
            val cold = MethodHandles
                .insertArguments(handler, 0, site, caller.lookupClass())
                .asCollector(Array<Any>::class.java, type.parameterCount() - 3)
                .asType(type)
            site.coldTarget = cold
            site.setTarget(cold)
            linked.add(site)
            return site
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
