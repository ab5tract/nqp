package org.raku.nqp.sixmodel

import com.oracle.truffle.api.Assumption
import com.oracle.truffle.api.Truffle
import org.raku.nqp.runtime.HLLConfig

/**
 * The published facts of a type: what compiled code folds to constants.
 *
 * Immutable. A state belongs to exactly one STable and is replaced, never
 * mutated, through [STable.publish], which installs the next state and then
 * invalidates this one's [assumption]. Because states are never shared,
 * `obj.st.state === s` implies `obj.st` is s's owner, so a state-identity
 * guard subsumes a type-identity guard; in compiled code `assumption.isValid`
 * folds to nothing and a publish deoptimizes exactly the code that trusted it.
 *
 * Not here: `REPRData` (REPR-owned, read by the REPRs themselves) and
 * `parametricity` (a lookup table that grows). A change to either
 * republishes the state instead (Ops.composetype, SerializationReader).
 */
class TypeState(
    @JvmField val methodCache: Map<String, SixModelObject?>?,
    @JvmField val vTable: Array<SixModelObject?>?,
    @JvmField val typeCheckCache: Array<SixModelObject?>?,
    @JvmField val modeFlags: Int,
    @JvmField val containerSpec: ContainerSpec?,
    @JvmField val invocationSpec: InvocationSpec?,
    @JvmField val boolificationSpec: BoolificationSpec?,
    @JvmField val hllOwner: HLLConfig?,
    @JvmField val hllRole: Long,
    /** The owning type's debug name, so the assumption trace names the type. */
    @JvmField val name: String?,
) {
    /** Valid exactly while this state is its STable's current one. */
    @JvmField val assumption: Assumption = Truffle.getRuntime().createAssumption(name ?: "type")

    val methodCacheAuthoritative: Boolean
        get() = (modeFlags and STable.METHOD_CACHE_AUTHORITATIVE) != 0

    val typeCheckMode: Int
        get() = modeFlags and STable.TYPE_CHECK_CACHE_FLAG_MASK

    /** A new state, with a fresh assumption, differing in the named facts only. Does not publish. */
    fun withFacts(
        methodCache: Map<String, SixModelObject?>? = this.methodCache,
        vTable: Array<SixModelObject?>? = this.vTable,
        typeCheckCache: Array<SixModelObject?>? = this.typeCheckCache,
        modeFlags: Int = this.modeFlags,
        containerSpec: ContainerSpec? = this.containerSpec,
        invocationSpec: InvocationSpec? = this.invocationSpec,
        boolificationSpec: BoolificationSpec? = this.boolificationSpec,
        hllOwner: HLLConfig? = this.hllOwner,
        hllRole: Long = this.hllRole,
        name: String? = this.name,
    ): TypeState = TypeState(methodCache, vTable, typeCheckCache, modeFlags, containerSpec,
        invocationSpec, boolificationSpec, hllOwner, hllRole, name)

    companion object {
        /** The state every STable starts in: no facts, valid. One per STable, never shared. */
        @JvmStatic
        fun initial(): TypeState = TypeState(null, null, null, 0, null, null, null, null, 0L, null)
    }
}
