package org.raku.nqp.runtime

import java.lang.invoke.MethodHandle

import org.raku.nqp.sixmodel.SixModelObject

/**
 * Object representing a reference to a piece of code (may later become a
 * REPR).
 */
class CodeRef : SixModelObject {
    /**
     * The static data about this code reference. lateinit for the sake of
     * the private clone constructor; every other path assigns it.
     */
    lateinit var staticInfo: StaticCodeInfo

    /**
     * The captured outer frame, if any.
     */
    @JvmField var outer: CallFrame? = null

    /**
     * High level code object, if any.
     */
    @JvmField var codeObject: SixModelObject? = null

    /**
     * Is this flagged as a static code ref?
     */
    @JvmField var isStaticCodeRef = false

    /**
     * This code ref's index in [sc]'s code root set; -1 while in none. A
     * code ref can sit in the object root set too (scIdx), so this is
     * its own field.
     */
    @JvmField var scCodeIdx: Int = -1

    /**
     * Is this flagged as a compiler stub?
     */
    @JvmField var isCompilerStub = false

    /**
     * State variable storage, if needed.
     */
    @JvmField var oLexState: Array<SixModelObject?>? = null

    /**
     * The (human-readable) name of the code-ref (not in staticInfo as a
     * number of places want to tweak it per closure clone).
     */
    @JvmField var name: String?

    /**
     * Sets up the code-ref data structure.
     */
    constructor(compUnit: CompilationUnit, mh: MethodHandle, name: String?, uniqueId: String?,
            oLexicalNames: Array<String>?, iLexicalNames: Array<String>?,
            nLexicalNames: Array<String>?, sLexicalNames: Array<String>?,
            handlers: Array<LongArray>?, argsExpectation: Short) {
        this.staticInfo = StaticCodeInfo(compUnit, mh, uniqueId,
                oLexicalNames, iLexicalNames, nLexicalNames, sLexicalNames,
                handlers, this, argsExpectation)
        this.name = name
    }

    /** A shell (the artifact road): identity now, the body from [source]
     *  on first need; see StaticCodeInfo. */
    constructor(compUnit: CompilationUnit, mh: MethodHandle, name: String?, uniqueId: String?,
                argsExpectation: Short, source: StaticBodySource) {
        this.staticInfo = StaticCodeInfo(compUnit, mh, uniqueId, argsExpectation, this, source)
        this.name = name
    }

    /**
     * Clones the object.
     */
    override fun clone(tc: ThreadContext): SixModelObject {
        val clone = CodeRef()
        clone.st = this.st
        clone.staticInfo = this.staticInfo
        if (this.outer != null)
            clone.outer = this.outer
        else
            clone.outer = staticInfo.outerStaticInfo!!.priorInvocation
        clone.name = this.name
        return clone
    }

    /**
     * Private constructor for the sake of clone.
     */
    private constructor() {
        this.name = null
    }
}
