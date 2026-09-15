package org.raku.nqp.dispatch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The persisted twin of a DispatchProgram (milestone 7 Phase C): the
 * same tree with every object reference replaced by an SC address, the
 * HLL config by its name, the syscall by its name and the dispatcher by
 * its id. One DispatchSlot per site slot of unit.dispatch, encoded by
 * UnitCodec. Data only; DispatchSlotCodec converts both ways.
 */
@Serializable class PRef(val handle: String, val index: Int, val kind: Int) {
    companion object { const val OBJ = 0; const val CODE = 1; const val STABLE = 2 }
}

@Serializable class PDescriptor(val flags: ByteArray, val names: List<String>?)

@Serializable sealed class PSource
@Serializable @SerialName("arg") class PArg(val index: Int) : PSource()
@Serializable @SerialName("rinit") class PResumeInitArg(val level: Int, val index: Int) : PSource()
/** OBJ: [obj] (null is the null object); INT/UINT: [i]; NUM: [n]; STR: [s]. */
@Serializable @SerialName("lit") class PLiteral(val kind: ArgKind, val obj: PRef?, val i: Long, val n: Double, val s: String?) : PSource()
@Serializable @SerialName("attr") class PAttribute(val from: PSource, val classHandle: PRef?, val name: String, val kind: ArgKind) : PSource()
@Serializable @SerialName("how") class PHow(val from: PSource) : PSource()
@Serializable @SerialName("unbox") class PUnbox(val from: PSource, val kind: ArgKind) : PSource()
@Serializable @SerialName("lookup") class PLookup(val table: PSource, val key: PSource) : PSource()
@Serializable @SerialName("rstate") class PResumeState(val level: Int) : PSource()

@Serializable sealed class PGuard
@Serializable @SerialName("type") class PGuardType(val on: PSource, val type: PRef?) : PGuard()
@Serializable @SerialName("conc") class PGuardConcreteness(val on: PSource, val concrete: Boolean) : PGuard()
@Serializable @SerialName("lit") class PGuardLiteral(val on: PSource, val expected: PLiteral) : PGuard()
@Serializable @SerialName("notlit") class PGuardNotLiteralObj(val on: PSource, val rejected: PRef?) : PGuard()
/** The name alone does not name a config: [compilerSide] picks the registry
 *  it lives in (HLLConfig.compilerSide). */
@Serializable @SerialName("hll") class PGuardHll(val on: PSource, val hll: String?, val compilerSide: Boolean) : PGuard()

@Serializable class PShape(val sources: List<PSource>, val descriptor: PDescriptor)

@Serializable sealed class POutcome
@Serializable @SerialName("value") class POutcomeValue(val source: PSource) : POutcome()
@Serializable @SerialName("invoke") class POutcomeInvoke(val callee: PSource, val args: PShape) : POutcome()
@Serializable @SerialName("syscall") class POutcomeSyscall(val syscall: String, val args: PShape) : POutcome()

@Serializable class PResumption(val dispatcher: String, val initArgs: PShape)
@Serializable class PLevel(val dispatcher: String, val initDescriptor: PDescriptor, val guards: List<PGuard>,
                           val newState: PSource?, val requireNoFurther: Boolean)
@Serializable class PBind(val failureFlag: Long, val successFlag: Long?, val onSuccessToo: Boolean)

@Serializable class PProgram(
    val descriptor: PDescriptor, val guards: List<PGuard>, val outcome: POutcome,
    val resumptions: List<PResumption>, val resumeKind: ResumeKind, val resumeLevels: List<PLevel>,
    val bindControl: PBind?, val bindFailure: PProgram?)

@Serializable class DispatchSlot(val programs: List<PProgram>)
