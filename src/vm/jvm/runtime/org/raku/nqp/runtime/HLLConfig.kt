package org.raku.nqp.runtime

import org.raku.nqp.sixmodel.SixModelObject

/**
 * Contains configuration specific to a given HLL.
 *
 * All fields are @JvmField to keep the exact class-file shape of the
 * original Java (raw public fields, read by Java code across the runtime
 * and by rakudo's org.raku.rakudo layer).
 */
class HLLConfig {
    companion object {
        /* HLL type roles. */
        const val ROLE_NONE = 0
        const val ROLE_INT = 1
        const val ROLE_NUM = 2
        const val ROLE_STR = 3
        const val ROLE_ARRAY = 4
        const val ROLE_HASH = 5
        const val ROLE_CODE = 6
    }

    /** HLL name. */
    @JvmField var name: String? = null

    /** The types the languages wish to get things boxed as. */
    @JvmField var intBoxType: SixModelObject? = null
    @JvmField var uintBoxType: SixModelObject? = null
    @JvmField var numBoxType: SixModelObject? = null
    @JvmField var strBoxType: SixModelObject? = null

    /** The type to use for nqp::list(...) */
    @JvmField var listType: SixModelObject? = null

    /** The type to use for nqp::hash(...) */
    @JvmField var hashType: SixModelObject? = null

    /** The type to use for slurpy arrays. */
    @JvmField var slurpyArrayType: SixModelObject? = null

    /** The type to use for slurpy hashes. */
    @JvmField var slurpyHashType: SixModelObject? = null

    /** The type to use for array iteration (should have VMIter REPR). */
    @JvmField var arrayIteratorType: SixModelObject? = null

    /** The type to use for hash iteration (should have VMIter REPR). */
    @JvmField var hashIteratorType: SixModelObject? = null

    /** The type to construct for exceptions (should have VMException REPR). */
    @JvmField var exceptionType: SixModelObject? = null

    /** The type to construct for IO handles. */
    @JvmField var ioType: SixModelObject? = null

    /** HLL interop types. */
    @JvmField var foreignTypeInt: SixModelObject? = null
    @JvmField var foreignTypeNum: SixModelObject? = null
    @JvmField var foreignTypeStr: SixModelObject? = null
    @JvmField var nullValue: SixModelObject? = null

    @JvmField var trueValue: SixModelObject? = null
    @JvmField var falseValue: SixModelObject? = null

    /** HLL interop mappers. */
    @JvmField var foreignTransformInt: SixModelObject? = null
    @JvmField var foreignTransformNum: SixModelObject? = null
    @JvmField var foreignTransformStr: SixModelObject? = null
    @JvmField var foreignTransformArray: SixModelObject? = null
    @JvmField var foreignTransformHash: SixModelObject? = null
    @JvmField var foreignTransformCode: SixModelObject? = null
    @JvmField var foreignTransformAny: SixModelObject? = null

    /** Block exit handler, for those that need it. */
    @JvmField var exitHandler: SixModelObject? = null

    /** HLL Handler for lexical exceptions without handler. */
    @JvmField var lexicalHandlerNotFoundError: SixModelObject? = null

    /** Native reference types. */
    @JvmField var intLexRef: SixModelObject? = null
    @JvmField var uintLexRef: SixModelObject? = null
    @JvmField var numLexRef: SixModelObject? = null
    @JvmField var strLexRef: SixModelObject? = null
    @JvmField var intAttrRef: SixModelObject? = null
    @JvmField var uintAttrRef: SixModelObject? = null
    @JvmField var numAttrRef: SixModelObject? = null
    @JvmField var strAttrRef: SixModelObject? = null
    @JvmField var intPosRef: SixModelObject? = null
    @JvmField var uintPosRef: SixModelObject? = null
    @JvmField var numPosRef: SixModelObject? = null
    @JvmField var strPosRef: SixModelObject? = null
    @JvmField var intMultidimRef: SixModelObject? = null
    @JvmField var uintMultidimRef: SixModelObject? = null
    @JvmField var numMultidimRef: SixModelObject? = null
    @JvmField var strMultidimRef: SixModelObject? = null
}
