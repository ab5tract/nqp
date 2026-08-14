package org.raku.nqp.jast2bc

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap
import java.util.HashSet

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList

internal class AutosplitMethodWriter(
    private val target: ClassVisitor,
    private val tgtype: String,
    access: Int, name: String?, desc: String, sig: String?, exn: Array<String>?,
) : MethodNode(Opcodes.ASM9, access, name, desc, sig, exn) {

    companion object {
        /** Maximum size of a method to leave alone. */
        private const val MAX_UNSPLIT_METHOD = 65535
        private const val MAX_FRAGMENT = 65535
        private const val MAX_SWITCH = 256

        /** True to dump control flow analysis. */
        private const val DEBUG_CONTROL = false
        private const val DEBUG_FRAGMENT = false
        private const val TYPE_TRACE = false

        private val simpleEffects = arrayOfNulls<StackEffect>(256)

        init {
            simpleEffects[Opcodes.AASTORE] = StackEffect("L", "I", "L", "")
            simpleEffects[Opcodes.ACONST_NULL] = StackEffect("", "0")
            simpleEffects[Opcodes.ARETURN] = StackEffect("")
            simpleEffects[Opcodes.ARRAYLENGTH] = StackEffect("L", "", "I")
            simpleEffects[Opcodes.ATHROW] = StackEffect("")
            simpleEffects[Opcodes.BALOAD] = StackEffect("L", "I", "", "I")
            simpleEffects[Opcodes.BASTORE] = StackEffect("L", "I", "I", "")
            simpleEffects[Opcodes.BIPUSH] = StackEffect("", "I")
            simpleEffects[Opcodes.CALOAD] = StackEffect("[C", "I", "", "I")
            simpleEffects[Opcodes.CASTORE] = StackEffect("[C", "I", "I", "")
            simpleEffects[Opcodes.D2F] = StackEffect("D", "", "F")
            simpleEffects[Opcodes.D2I] = StackEffect("D", "", "I")
            simpleEffects[Opcodes.D2L] = StackEffect("D", "", "J")
            simpleEffects[Opcodes.DADD] = StackEffect("D", "D", "", "D")
            simpleEffects[Opcodes.DALOAD] = StackEffect("[D", "I", "", "D")
            simpleEffects[Opcodes.DASTORE] = StackEffect("[D", "I", "D", "")
            simpleEffects[Opcodes.DCMPG] = StackEffect("D", "D", "", "I")
            simpleEffects[Opcodes.DCMPL] = StackEffect("D", "D", "", "I")
            simpleEffects[Opcodes.DCONST_0] = StackEffect("", "D")
            simpleEffects[Opcodes.DCONST_1] = StackEffect("", "D")
            simpleEffects[Opcodes.DDIV] = StackEffect("D", "D", "", "D")
            simpleEffects[Opcodes.DLOAD] = StackEffect("", "D")
            simpleEffects[Opcodes.DMUL] = StackEffect("D", "D", "", "D")
            simpleEffects[Opcodes.DNEG] = StackEffect("D", "", "D")
            simpleEffects[Opcodes.DREM] = StackEffect("D", "D", "", "D")
            simpleEffects[Opcodes.DRETURN] = StackEffect("")
            simpleEffects[Opcodes.DSUB] = StackEffect("D", "D", "", "D")
            simpleEffects[Opcodes.F2D] = StackEffect("F", "", "D")
            simpleEffects[Opcodes.F2I] = StackEffect("F", "", "I")
            simpleEffects[Opcodes.F2L] = StackEffect("F", "", "J")
            simpleEffects[Opcodes.FADD] = StackEffect("F", "F", "", "F")
            simpleEffects[Opcodes.FALOAD] = StackEffect("[F", "I", "", "F")
            simpleEffects[Opcodes.FASTORE] = StackEffect("[F", "I", "F", "")
            simpleEffects[Opcodes.FCMPG] = StackEffect("F", "F", "", "I")
            simpleEffects[Opcodes.FCMPL] = StackEffect("F", "F", "", "I")
            simpleEffects[Opcodes.FCONST_0] = StackEffect("", "F")
            simpleEffects[Opcodes.FCONST_1] = StackEffect("", "F")
            simpleEffects[Opcodes.FCONST_2] = StackEffect("", "F")
            simpleEffects[Opcodes.FDIV] = StackEffect("F", "F", "", "F")
            simpleEffects[Opcodes.FLOAD] = StackEffect("", "F")
            simpleEffects[Opcodes.FMUL] = StackEffect("F", "F", "", "F")
            simpleEffects[Opcodes.FNEG] = StackEffect("F", "", "F")
            simpleEffects[Opcodes.FREM] = StackEffect("F", "F", "", "F")
            simpleEffects[Opcodes.FRETURN] = StackEffect("")
            simpleEffects[Opcodes.FSUB] = StackEffect("F", "F", "", "F")
            simpleEffects[Opcodes.GOTO] = StackEffect("")
            simpleEffects[Opcodes.I2B] = StackEffect("I", "", "I")
            simpleEffects[Opcodes.I2C] = StackEffect("I", "", "I")
            simpleEffects[Opcodes.I2D] = StackEffect("I", "", "D")
            simpleEffects[Opcodes.I2F] = StackEffect("I", "", "F")
            simpleEffects[Opcodes.I2L] = StackEffect("I", "", "J")
            simpleEffects[Opcodes.I2S] = StackEffect("I", "", "I")
            simpleEffects[Opcodes.IADD] = StackEffect("I", "I", "", "I")
            simpleEffects[Opcodes.IALOAD] = StackEffect("[I", "I", "", "I")
            simpleEffects[Opcodes.IAND] = StackEffect("I", "I", "", "I")
            simpleEffects[Opcodes.IASTORE] = StackEffect("[I", "I", "I", "")
            simpleEffects[Opcodes.ICONST_0] = StackEffect("", "I")
            simpleEffects[Opcodes.ICONST_1] = StackEffect("", "I")
            simpleEffects[Opcodes.ICONST_2] = StackEffect("", "I")
            simpleEffects[Opcodes.ICONST_3] = StackEffect("", "I")
            simpleEffects[Opcodes.ICONST_4] = StackEffect("", "I")
            simpleEffects[Opcodes.ICONST_5] = StackEffect("", "I")
            simpleEffects[Opcodes.ICONST_M1] = StackEffect("", "I")
            simpleEffects[Opcodes.IDIV] = StackEffect("I", "I", "", "I")
            simpleEffects[Opcodes.IFEQ] = StackEffect("I", "")
            simpleEffects[Opcodes.IFGE] = StackEffect("I", "")
            simpleEffects[Opcodes.IFGT] = StackEffect("I", "")
            simpleEffects[Opcodes.IFLE] = StackEffect("I", "")
            simpleEffects[Opcodes.IFLT] = StackEffect("I", "")
            simpleEffects[Opcodes.IFNE] = StackEffect("I", "")
            simpleEffects[Opcodes.IFNONNULL] = StackEffect("L", "")
            simpleEffects[Opcodes.IFNULL] = StackEffect("L", "")
            simpleEffects[Opcodes.IF_ACMPEQ] = StackEffect("I", "I", "")
            simpleEffects[Opcodes.IF_ACMPNE] = StackEffect("I", "I", "")
            simpleEffects[Opcodes.IF_ICMPEQ] = StackEffect("I", "I", "")
            simpleEffects[Opcodes.IF_ICMPGE] = StackEffect("I", "I", "")
            simpleEffects[Opcodes.IF_ICMPGT] = StackEffect("I", "I", "")
            simpleEffects[Opcodes.IF_ICMPLE] = StackEffect("I", "I", "")
            simpleEffects[Opcodes.IF_ICMPLT] = StackEffect("I", "I", "")
            simpleEffects[Opcodes.IF_ICMPNE] = StackEffect("I", "I", "")
            simpleEffects[Opcodes.IINC] = StackEffect("")
            simpleEffects[Opcodes.ILOAD] = StackEffect("", "I")
            simpleEffects[Opcodes.IMUL] = StackEffect("I", "I", "", "I")
            simpleEffects[Opcodes.INEG] = StackEffect("I", "", "I")
            simpleEffects[Opcodes.INSTANCEOF] = StackEffect("L", "", "I")
            simpleEffects[Opcodes.IOR] = StackEffect("I", "I", "", "I")
            simpleEffects[Opcodes.IREM] = StackEffect("I", "I", "", "I")
            simpleEffects[Opcodes.IRETURN] = StackEffect("")
            simpleEffects[Opcodes.ISHL] = StackEffect("I", "I", "", "I")
            simpleEffects[Opcodes.ISHR] = StackEffect("I", "I", "", "I")
            simpleEffects[Opcodes.ISUB] = StackEffect("I", "I", "", "I")
            simpleEffects[Opcodes.IUSHR] = StackEffect("I", "I", "", "I")
            simpleEffects[Opcodes.IXOR] = StackEffect("I", "I", "", "I")
            simpleEffects[Opcodes.L2D] = StackEffect("J", "", "D")
            simpleEffects[Opcodes.L2F] = StackEffect("J", "", "F")
            simpleEffects[Opcodes.L2I] = StackEffect("J", "", "I")
            simpleEffects[Opcodes.LADD] = StackEffect("J", "J", "", "J")
            simpleEffects[Opcodes.LALOAD] = StackEffect("[J", "I", "", "J")
            simpleEffects[Opcodes.LAND] = StackEffect("J", "J", "", "J")
            simpleEffects[Opcodes.LASTORE] = StackEffect("[J", "I", "J", "")
            simpleEffects[Opcodes.LCMP] = StackEffect("J", "J", "", "I")
            simpleEffects[Opcodes.LCONST_0] = StackEffect("", "J")
            simpleEffects[Opcodes.LCONST_1] = StackEffect("", "J")
            simpleEffects[Opcodes.LDIV] = StackEffect("J", "J", "", "J")
            simpleEffects[Opcodes.LLOAD] = StackEffect("", "J")
            simpleEffects[Opcodes.LMUL] = StackEffect("J", "J", "", "J")
            simpleEffects[Opcodes.LNEG] = StackEffect("J", "", "J")
            simpleEffects[Opcodes.LOOKUPSWITCH] = StackEffect("I", "")
            simpleEffects[Opcodes.LOR] = StackEffect("J", "J", "", "J")
            simpleEffects[Opcodes.LREM] = StackEffect("J", "J", "", "J")
            simpleEffects[Opcodes.LRETURN] = StackEffect("")
            simpleEffects[Opcodes.LSHL] = StackEffect("J", "I", "", "J")
            simpleEffects[Opcodes.LSHR] = StackEffect("J", "I", "", "J")
            simpleEffects[Opcodes.LSUB] = StackEffect("J", "J", "", "J")
            simpleEffects[Opcodes.LUSHR] = StackEffect("J", "I", "", "J")
            simpleEffects[Opcodes.LXOR] = StackEffect("J", "J", "", "J")
            simpleEffects[Opcodes.MONITORENTER] = StackEffect("L", "")
            simpleEffects[Opcodes.MONITOREXIT] = StackEffect("L", "")
            simpleEffects[Opcodes.NOP] = StackEffect("")
            simpleEffects[Opcodes.PUTFIELD] = StackEffect("X", "")
            simpleEffects[Opcodes.PUTSTATIC] = StackEffect("X", "X", "")
            simpleEffects[Opcodes.RETURN] = StackEffect("")
            simpleEffects[Opcodes.SALOAD] = StackEffect("[S", "I", "", "I")
            simpleEffects[Opcodes.SASTORE] = StackEffect("[S", "I", "I", "")
            simpleEffects[Opcodes.SIPUSH] = StackEffect("", "I")
            simpleEffects[Opcodes.TABLESWITCH] = StackEffect("I", "")
        }
    }

    /** The real instructions (not branches) in program order.  Filled out by [getControlFlow]. */
    private lateinit var insnList: Array<AbstractInsnNode>
    private lateinit var insnMap: Object2IntOpenHashMap<AbstractInsnNode>
    private lateinit var lineNumbers: IntArray

    /** Array of (source, target) pairs.  Filled out by [getControlFlow].  -1 means from-outside. */
    private lateinit var controlEdges: Array<ControlEdge>
    private lateinit var successors: Array<Array<ControlEdge>>
    private lateinit var baselineSize: IntArray
    private lateinit var types: Array<Frame?>

    private var nextJumpNo = 0
    private lateinit var jumpNoMap: IntArray
    private val firstJump = IntArrayList()

    private class ControlEdge(@JvmField val from: Int, @JvmField val to: Int,
                              /** if non-null, replace the stack with this */
                              @JvmField val exn: String?)

    private var nlocal = 0

    override fun visitEnd() {
        super.visitEnd()

        var maxsize = 0
        var ai = instructions.getFirst()
        while (ai != null) {
            maxsize += insnSize(ai)
            ai = ai.getNext()
        }

        if (maxsize <= MAX_UNSPLIT_METHOD) {
            // hey cool, we don't need to do anything fancy here
            val mw = target.visitMethod(access, name, desc, signature, exceptions.toTypedArray())
            accept(mw)
            return
        }

        /* we need to split this thing */

        if (DEBUG_FRAGMENT) System.out.printf("method=%s max=%d\n", name, maxsize)

        splitSwitches()
        getInstructions()
        getControlFlow()
        if (DEBUG_CONTROL) printControlFlow()
        getTypes()
        getBaselineSize()

        if (DEBUG_CONTROL) {
            for (i in 1..insnList.size)
                System.out.printf("from=%d to=%d frag=%d\n", 0, i, calcFragmentSize(0, i))
        }

        jumpNoMap = IntArray(insnList.size)
        Arrays.fill(jumpNoMap, -1)
        val fragmentSizes = IntArrayList()
        var taken = 0
        while (taken < insnList.size) {
            if (calcFragmentSize(taken, taken + 1) > MAX_FRAGMENT)
                throw RuntimeException("cannot take even one more instruction at $taken")
            val takeable = bite(taken, 1, insnList.size - taken)

            if (DEBUG_FRAGMENT) System.out.printf("fragment: %d - %d (max %d bytes)\n", taken, taken + takeable - 1, calcFragmentSize(taken, taken + takeable))
            fragmentSizes.add(takeable)
            allocateJumpNrs(taken, taken + takeable)
            taken += takeable
        }

        taken = 0
        var fno = 0
        for (sz in fragmentSizes) {
            emitFragment(fno++, taken, taken + sz)
            taken += sz
        }

        becomeWrapper()
        accept(target)
    }

    private fun bite(from: Int, min_takeIn: Int, max_takeIn: Int): Int { /* min_take is known good */
        var min_take = min_takeIn
        var max_take = max_takeIn
        while (true) {
            if (min_take == max_take) return min_take
            val mid_take = (min_take + max_take + 1) / 2

            if (calcFragmentSize(from, from + mid_take) <= MAX_FRAGMENT) {
                min_take = mid_take
            } else {
                max_take = mid_take - 1
            }
        }
    }

    private fun isRealInsn(node: AbstractInsnNode): Boolean {
        return when (node.getType()) {
            AbstractInsnNode.LINE,
            AbstractInsnNode.LABEL,
            AbstractInsnNode.FRAME ->
                false
            else ->
                true
        }
    }

    /** Break apart large switch instructions so that they may fit in a fragment. Runs before [getInstructions] because it changes instruction sequence. */
    private fun splitSwitches() {
        var ptr = instructions.getFirst()

        while (ptr != null) {
            var cutoff = 0
            var left: AbstractInsnNode? = null
            var right: AbstractInsnNode? = null

            when (ptr.getType()) {
                AbstractInsnNode.LOOKUPSWITCH_INSN -> {
                    val lsi = ptr as LookupSwitchInsnNode
                    if (lsi.labels.size > MAX_SWITCH) {
                        val lsl = LookupSwitchInsnNode(lsi.dflt, IntArray(0), arrayOfNulls(0))
                        val lsr = LookupSwitchInsnNode(lsi.dflt, IntArray(0), arrayOfNulls(0))

                        val lsisz = lsi.labels.size
                        lsl.keys.addAll(lsi.keys.subList(0, lsisz / 2))
                        lsr.keys.addAll(lsi.keys.subList(lsisz / 2, lsisz))
                        lsl.labels.addAll(lsi.labels.subList(0, lsisz / 2))
                        lsr.labels.addAll(lsi.labels.subList(lsisz / 2, lsisz))
                        left = lsl
                        right = lsr
                        cutoff = lsr.keys[0] as Int
                    }
                }

                AbstractInsnNode.TABLESWITCH_INSN -> {
                    val lsi = ptr as TableSwitchInsnNode
                    if (lsi.labels.size > MAX_SWITCH) {
                        cutoff = (lsi.min + lsi.max) / 2
                        val lsl = TableSwitchInsnNode(lsi.min, cutoff - 1, lsi.dflt)
                        val lsr = TableSwitchInsnNode(cutoff, lsi.max, lsi.dflt)

                        lsl.labels.addAll(lsi.labels.subList(0, cutoff - lsi.min))
                        lsr.labels.addAll(lsi.labels.subList(cutoff - lsi.min, lsi.max + 1 - lsi.min))
                        left = lsl
                        right = lsr
                    }
                }

                else -> {}
            }

            if (left != null) {
                if (DEBUG_FRAGMENT) System.out.printf("Breaking switch at %d\n", cutoff)

                val high = LabelNode()
                instructions.insertBefore(ptr, InsnNode(Opcodes.DUP))
                instructions.insertBefore(ptr, intNode(cutoff))
                instructions.insertBefore(ptr, JumpInsnNode(Opcodes.IF_ICMPGE, high))
                instructions.insertBefore(ptr, left)
                instructions.insertBefore(ptr, high)
                instructions.insertBefore(ptr, right!!)
                instructions.remove(ptr)

                ptr = left
            } else {
                ptr = ptr.getNext()
            }
        }
    }

    private fun intNode(value: Int): AbstractInsnNode {
        return if (value >= -1 && value <= 5) InsnNode(Opcodes.ICONST_0 + value)
               else if (value >= -128 && value <= 127) IntInsnNode(Opcodes.BIPUSH, value)
               else if (value >= -32768 && value <= 32767) IntInsnNode(Opcodes.SIPUSH, value)
               else LdcInsnNode(value)
    }

    /** Extract the real instructions from the instruction list. */
    private fun getInstructions() {
        // munge the linked list of insns we got from ASM into something saner
        val tempInsnList = ArrayList<AbstractInsnNode>()
        insnMap = Object2IntOpenHashMap()
        val linesMap = Object2IntOpenHashMap<AbstractInsnNode>()

        var n = instructions.getFirst()
        while (n != null) {
            insnMap.put(n, tempInsnList.size)
            if (isRealInsn(n)) tempInsnList.add(n)
            if (n.getType() == AbstractInsnNode.LINE) {
                val nn = n as LineNumberNode
                var start: AbstractInsnNode? = nn.start
                while (start != null && !isRealInsn(start)) start = start.getNext()
                linesMap.put(start, nn.line)
            }
            n = n.getNext()
        }
        insnList = tempInsnList.toTypedArray()

        var curLine = 0
        lineNumbers = IntArray(insnList.size)

        for (i in insnList.indices) {
            val ll = linesMap.getOrDefault(insnList[i], -1)
            if (ll != -1) curLine = ll
            lineNumbers[i] = curLine
        }
    }

    /** Build the control flow graph. */
    private fun getControlFlow() {
        val controlTemp = ArrayList<ControlEdge>()
        val succTemps = arrayOfNulls<MutableList<ControlEdge>>(insnList.size)

        for (insnNo in insnList.indices) {
            val node = insnList[insnNo]

            val succTemp = ArrayList<ControlEdge>()
            succTemps[insnNo] = succTemp

            when (node.getType()) {
                AbstractInsnNode.JUMP_INSN -> {
                    val ji = node as JumpInsnNode
                    succTemp.add(ControlEdge(insnNo, insnMap.getInt(ji.label), null))
                    if (node.getOpcode() != Opcodes.GOTO)
                        succTemp.add(ControlEdge(insnNo, insnNo + 1, null))
                }

                AbstractInsnNode.TABLESWITCH_INSN -> {
                    val tsi = node as TableSwitchInsnNode
                    succTemp.add(ControlEdge(insnNo, insnMap.getInt(tsi.dflt), null))
                    for (i in 0 until tsi.labels.size)
                        succTemp.add(ControlEdge(insnNo, insnMap.getInt(tsi.labels[i]), null))
                }

                AbstractInsnNode.LOOKUPSWITCH_INSN -> {
                    val lsi = node as LookupSwitchInsnNode
                    succTemp.add(ControlEdge(insnNo, insnMap.getInt(lsi.dflt), null))
                    for (i in 0 until lsi.labels.size)
                        succTemp.add(ControlEdge(insnNo, insnMap.getInt(lsi.labels[i]), null))
                }

                else -> {
                    val opcode = node.getOpcode()

                    if (!(opcode == Opcodes.ATHROW || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN))
                        succTemp.add(ControlEdge(insnNo, insnNo + 1, null))
                }
            }
        }

        for (tcb in tryCatchBlocks) {
            val start = insnMap.getInt(tcb.start)
            val end = insnMap.getInt(tcb.end)
            val handler = insnMap.getInt(tcb.handler)
            val type = tcb.type ?: "java/lang/Throwable"

            for (i in start until end)
                succTemps[i]!!.add(ControlEdge(i, handler, type))
        }

        successors = Array(insnList.size) { insnNo ->
            controlTemp.addAll(succTemps[insnNo]!!)
            succTemps[insnNo]!!.toTypedArray()
        }

        controlEdges = controlTemp.toTypedArray()
    }

    private class StackEffect(vararg ops: String) {
        @JvmField val pop: Array<String>
        @JvmField val push: Array<String>

        init {
            var nul = 0
            while (!ops[nul].isEmpty()) nul++
            @Suppress("UNCHECKED_CAST")
            this.pop = Arrays.copyOfRange(ops, 0, nul) as Array<String>
            @Suppress("UNCHECKED_CAST")
            this.push = Arrays.copyOfRange(ops, nul + 1, ops.size) as Array<String>
        }
    }

    private fun printControlFlow() {
        for (i in insnList.indices) {
            System.out.printf("%5d: %s\n", i, insnList[i])
            for (ce in successors[i])
                System.out.printf("     %5d -> %d %s\n", ce.from, ce.to, ce.exn ?: "-")
        }
    }

    /** Infer types. */
    private class Frame(stack: Array<String>, @JvmField var sp: Int, @JvmField var sbase: Int) {
        @JvmField var stack: Array<String> = stack.clone()

        fun copy(): Frame {
            return Frame(stack, sp, sbase)
        }

        fun grow(len: Int) {
            if (len <= stack.size) return

            val olen = stack.size
            stack = Arrays.copyOf(stack, len)
            Arrays.fill(stack as Array<Any?>, olen, len, "T")
        }

        fun describe(): String {
            return String.format("locals=[%s] stack=[%s]", Arrays.toString(Arrays.copyOfRange(stack, 0, sbase)), Arrays.toString(Arrays.copyOfRange(stack, sbase, sp)))
        }

        fun thrown(ex: String) {
            sp = sbase
            stack[sp++] = ("L$ex;").intern()
        }

        private fun pushReturn(s: String) {
            when (s[0]) {
                'V' -> {}
                'B', 'Z', 'S', 'C' -> stack[sp++] = "I"
                else -> stack[sp++] = s
            }
        }

        fun execute(index: Int, anode: AbstractInsnNode) {
            val simp = simpleEffects[anode.getOpcode()]

            if (stack.size < sp + 5) grow(sp + 8) // room for all fixed effects and a bit to spare

            if (simp != null) {
                sp -= simp.pop.size
                if (sp < sbase) throw RuntimeException("stack underflow")
                if (stack.size < sp + simp.push.size)
                    stack = Arrays.copyOf(stack, sp + simp.push.size)
                for (s in simp.push)
                    stack[sp++] = s
                return
            }

            var vi: VarInsnNode? = null
            var tidesc: String? = null
            var fi: FieldInsnNode? = null
            var midesc: Type? = null

            when (anode.getType()) {
                AbstractInsnNode.VAR_INSN -> {
                    vi = anode as VarInsnNode
                    if (vi.`var` != 0 && ("D" == stack[vi.`var` - 1] || "J" == stack[vi.`var` - 1]))
                        stack[vi.`var` - 1] = "T"
                }
                AbstractInsnNode.TYPE_INSN -> {
                    val ti = anode as TypeInsnNode
                    tidesc = if (ti.desc[0] == '[') ti.desc else "L" + ti.desc + ";"
                }
                AbstractInsnNode.METHOD_INSN -> {
                    val mi = anode as MethodInsnNode
                    midesc = Type.getMethodType(mi.desc)
                }
                AbstractInsnNode.INVOKE_DYNAMIC_INSN ->
                    midesc = Type.getMethodType((anode as InvokeDynamicInsnNode).desc)
                AbstractInsnNode.FIELD_INSN ->
                    fi = anode as FieldInsnNode
            }

            val a: String
            val b: String
            val c: String
            val d: String
            val opcode = anode.getOpcode()
            when (opcode) {
                Opcodes.AALOAD -> {
                    stack[sp - 2] = stack[sp - 2].substring(1)
                    sp--
                }
                Opcodes.ALOAD ->
                    stack[sp++] = stack[vi!!.`var`]
                Opcodes.ANEWARRAY ->
                    stack[sp - 1] = ("[" + tidesc).intern()
                Opcodes.ASTORE ->
                    stack[vi!!.`var`] = stack[--sp]
                Opcodes.CHECKCAST ->
                    stack[sp - 1] = tidesc!!.intern()
                Opcodes.DSTORE -> {
                    stack[vi!!.`var`] = "D"
                    stack[vi.`var` + 1] = "T"
                    sp--
                }
                Opcodes.DUP2 -> {
                    // [b] a -> [b] a [b] a
                    a = stack[--sp]
                    b = if ("D" == a || "J" == a) "X" else stack[--sp]

                    if ("X" != b) stack[sp++] = b
                    stack[sp++] = a
                    if ("X" != b) stack[sp++] = b
                    stack[sp++] = a
                }
                Opcodes.DUP2_X1 -> {
                    // c [b] a -> [b] a c [b] a
                    a = stack[--sp]
                    b = if ("D" == a || "J" == a) "X" else stack[--sp]
                    c = stack[--sp]

                    if ("X" != b) stack[sp++] = b
                    stack[sp++] = a
                    stack[sp++] = c
                    if ("X" != b) stack[sp++] = b
                    stack[sp++] = a
                }
                Opcodes.DUP2_X2 -> {
                    // [d] c [b] a -> b a d c b a
                    a = stack[--sp]
                    b = if ("D" == a || "J" == a) "X" else stack[--sp]
                    c = stack[--sp]
                    d = if ("D" == c || "J" == c) "X" else stack[--sp]

                    if ("X" != b) stack[sp++] = b
                    stack[sp++] = a
                    if ("X" != d) stack[sp++] = d
                    stack[sp++] = c
                    if ("X" != b) stack[sp++] = b
                    stack[sp++] = a
                }
                Opcodes.DUP -> {
                    // D, J invalid...
                    stack[sp] = stack[sp - 1]
                    sp++
                }
                Opcodes.DUP_X1 -> {
                    // b a -> a b a
                    a = stack[--sp]
                    b = stack[--sp]

                    stack[sp++] = a
                    stack[sp++] = b
                    stack[sp++] = a
                }
                Opcodes.DUP_X2 -> {
                    // [d] c a -> a [d] c a
                    a = stack[--sp]
                    c = stack[--sp]
                    d = if ("D" == c || "J" == c) "X" else stack[--sp]

                    stack[sp++] = a
                    if ("X" != d) stack[sp++] = d
                    stack[sp++] = c
                    stack[sp++] = a
                }
                Opcodes.FSTORE -> {
                    stack[vi!!.`var`] = "F"
                    sp--
                }

                Opcodes.GETFIELD -> {
                    sp--
                    pushReturn(fi!!.desc.intern())
                }

                Opcodes.GETSTATIC ->
                    pushReturn(fi!!.desc.intern())

                Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEDYNAMIC -> {
                    sp -= midesc!!.getArgumentTypes().size // pop arguments
                    if (opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKEDYNAMIC) {
                        val self = stack[--sp] // pop this
                        // initialize
                        if (self[0] == 'U') {
                            val initialized = self.substring(self.indexOf(':') + 1).intern()
                            for (i in stack.indices)
                                if (self == stack[i]) stack[i] = initialized
                        }
                    }
                    pushReturn(midesc.getReturnType().getDescriptor().intern())
                }

                Opcodes.ISTORE -> {
                    stack[vi!!.`var`] = "I"
                    sp--
                }

                //simpleEffects[Opcodes.JSR] = new StackEffect(null);

                Opcodes.LDC -> {
                    val cst = (anode as LdcInsnNode).cst
                    when (cst) {
                        is Int -> stack[sp++] = "I"
                        is Byte -> stack[sp++] = "I"
                        is Char -> stack[sp++] = "I"
                        is Short -> stack[sp++] = "I"
                        is Boolean -> stack[sp++] = "I"
                        is Float -> stack[sp++] = "F"
                        is Long -> stack[sp++] = "J"
                        is Double -> stack[sp++] = "D"
                        is String -> stack[sp++] = "Ljava/lang/String;"
                        is Type -> stack[sp++] = if (cst.getSort() == Type.METHOD) "Ljava/lang/invoke/MethodType;" else "Ljava/lang/Class;"
                        is Handle -> stack[sp++] = "Ljava/lang/invoke/MethodHandle;"
                        else -> throw RuntimeException("Unknown constant type $cst")
                    }
                }

                Opcodes.LSTORE -> {
                    stack[vi!!.`var`] = "J"
                    stack[vi.`var` + 1] = "T"
                    sp--
                }

                Opcodes.MULTIANEWARRAY -> {
                    val m = anode as MultiANewArrayInsnNode
                    sp -= m.dims
                    stack[sp++] = m.desc
                }

                Opcodes.NEWARRAY -> {
                    val type = (anode as IntInsnNode).operand
                    when (type) {
                        Opcodes.T_BOOLEAN -> stack[sp++] = "[Z"
                        Opcodes.T_CHAR -> stack[sp++] = "[C"
                        Opcodes.T_FLOAT -> stack[sp++] = "[F"
                        Opcodes.T_DOUBLE -> stack[sp++] = "[D"
                        Opcodes.T_BYTE -> stack[sp++] = "[B"
                        Opcodes.T_SHORT -> stack[sp++] = "[S"
                        Opcodes.T_INT -> stack[sp++] = "[I"
                        Opcodes.T_LONG -> stack[sp++] = "[J"
                        else -> throw RuntimeException("NEWARRAY $type")
                    }
                }

                Opcodes.NEW ->
                    stack[sp++] = ("U" + index + ':' + tidesc).intern()

                Opcodes.POP2 -> {
                    // [d] c ->
                    c = stack[--sp]
                    d = if ("D" == c || "J" == c) "X" else stack[--sp]
                }
                Opcodes.POP ->
                    sp--
                //simpleEffects[Opcodes.RET] = new StackEffect(null);
                Opcodes.SWAP -> {
                    a = stack[--sp]
                    b = stack[--sp]
                    stack[sp++] = a
                    stack[sp++] = b
                }
                else ->
                    throw RuntimeException("unimplemented opcode " + anode.getOpcode())
            }
        }
    }

    private class TypeInference(size: Int) {
        @JvmField val frames = arrayOfNulls<Frame>(size)
        @JvmField val changedQueue = IntArray(size + 1)
        @JvmField var changedHead = 0
        @JvmField var changedTail = 0
        @JvmField val changedVec = BooleanArray(size)

        fun next(): Int {
            if (changedHead == changedTail) return -1
            val n = changedQueue[changedTail++]
            if (changedTail == changedQueue.size) changedTail = 0
            changedVec[n] = false
            return n
        }

        fun merge(index: Int, f: Frame) {
            val slot = frames[index]
            if (slot == null) {
                frames[index] = f.copy()
                mark(index)
                return
            }
            slot.grow(f.stack.size)
            f.grow(slot.stack.size)

            if (f.sp != slot.sp) throw RuntimeException(String.format("Insn %d can be reached with stack sizes of %d and %d", index, f.sp - f.sbase, slot.sp - slot.sbase))

            var changed = false
            if (TYPE_TRACE) System.out.printf("MERGE INSN=%d\nOLD=   [%s]\nINPUT= [%s]\n", index, slot.describe(), f.describe())
            for (i in 0 until f.sp) {
                val a = f.stack[i]
                val b = slot.stack[i]
                val c = lub(a, b)
                if (b != c) {
                    //System.out.printf("%d.%d %s -> %s\n", index, i, b, c);
                    if (DEBUG_FRAGMENT && a != c && b != c) System.out.printf("%d.%d  %s | %s => %s\n", index, i, a, b, c)
                    slot.stack[i] = c
                    changed = true
                }
            }
            if (TYPE_TRACE) System.out.printf("OUTPUT=[%s]\n%s\n", slot.describe(), if (changed) "CHANGED" else "")

            if (changed) mark(index)
        }

        fun mark(index: Int) {
            if (changedVec[index]) return
            changedVec[index] = true
            changedQueue[changedHead++] = index
            if (changedHead == changedQueue.size) changedHead = 0
        }

        // computes the least upper bound of two verification types; we use descriptors, but U44:Lbar; for uninitialized(44), 0 for null, and T for TOP
        fun lub(a: String, b: String): String {
            // same type?  trivial
            if (a == b) return a
            if (a == "T") return a
            if (b == "T") return b

            val a0 = a[0]
            val b0 = b[0]

            // types other than initialized references cannot validly merge with anything
            if (a0 != '0' && a0 != 'L' && a0 != '[') return "T"
            if (b0 != '0' && b0 != 'L' && b0 != '[') return "T"

            // null is the bottom of what remains
            if (a0 == '0') return b
            if (b0 == '0') return a

            // an array and a non-array can merge only to Object
            if (a0 == '[') {
                if (b0 != '[') return "Ljava/lang/Object;"

                // array types are covariant
                val cc = lub(a.substring(1), b.substring(1))
                if (cc[0] == 'T') // children are not compatible, but we know we have *some* array, which is an object
                    return "Ljava/lang/Object;"
                return ("[" + cc).intern()
            } else {
                if (b0 != 'L') return "Ljava/lang/Object;"

                // at this point in a real verifier we would load the named classes and use their common superclass
                // lub(P6OpaqueInstance, CodeRef) = SixModelObject
                // punt.
                if (a == "Lorg/raku/nqp/runtime/CodeRef;" && b == "Lorg/raku/nqp/sixmodel/SixModelObject;") return b
                if (b == "Lorg/raku/nqp/runtime/CodeRef;" && a == "Lorg/raku/nqp/sixmodel/SixModelObject;") return a

                return "Ljava/lang/Object;"
            }
        }
    }

    private fun getTypes() {
        // first, establish a locals size so that we can merge locals and stacks
        nlocal = Type.getArgumentsAndReturnSizes(desc) shr 2
        if ((access and Opcodes.ACC_STATIC) != 0) nlocal--

        for (an in insnList) {
            if (an.getType() != AbstractInsnNode.VAR_INSN) continue
            val size = if (an.getOpcode() == Opcodes.DSTORE || an.getOpcode() == Opcodes.LSTORE) 2 else 1
            nlocal = Math.max(nlocal, (an as VarInsnNode).`var` + size)
        }

        val state = TypeInference(insnList.size)
        val initial = Frame(arrayOf(), 0, 0)
        initial.grow(nlocal + 10)

        var locwp = 0

        if ((access and Opcodes.ACC_STATIC) == 0) {
            initial.stack[locwp++] = ("L$tgtype;").intern()
        }
        for (arg in Type.getArgumentTypes(desc)) {
            initial.stack[locwp] = arg.getDescriptor().intern()
            locwp += arg.getSize()
        }
        initial.sp = nlocal
        initial.sbase = nlocal
        state.merge(0, initial)

        var insn: Int
        var step = 0
        while (state.next().also { insn = it } >= 0) {
            val inf = state.frames[insn]!!.copy()

            if (TYPE_TRACE) System.out.printf("INFERENCE STEP: insn=%d [%d] locals=[%s] stack=[%s]\n", insn, insnList[insn].getOpcode(), Arrays.toString(Arrays.copyOfRange(inf.stack, 0, nlocal)), Arrays.toString(Arrays.copyOfRange(inf.stack, inf.sbase, inf.sp)))
            inf.execute(insn, insnList[insn])

            for (ce in successors[insn]) {
                // assume exceptions follow non-exceptions
                if (ce.exn != null) inf.thrown(ce.exn)
                state.merge(ce.to, inf)
            }
            step++
            if (DEBUG_FRAGMENT && (step % 10000) == 0) System.out.printf("Inference step %d\n", step)
        }
        types = state.frames

        if (DEBUG_FRAGMENT) {
            val histog = HashMap<String, Int>()
            for (fr in types) {
                for (i in 0 until fr!!.sp) {
                    val r = histog[fr.stack[i]]
                    histog[fr.stack[i]] = if (r == null) 1 else 1 + r
                }
            }
            for (ent in histog.entries)
                System.out.printf("%s : %d\n", ent.key, ent.value)
        }
    }

    private fun insnSize(ai: AbstractInsnNode): Int {
        val opc = ai.getOpcode()
        return when (ai.getType()) {
            AbstractInsnNode.INSN ->
                1
            AbstractInsnNode.INT_INSN ->
                if (opc == Opcodes.SIPUSH) 3 else 2
            AbstractInsnNode.VAR_INSN -> {
                val v = (ai as VarInsnNode).`var`
                if (v < 4 && opc != Opcodes.RET) 1 else if (v >= 256) 4 else 2
            }
            AbstractInsnNode.TYPE_INSN ->
                3
            AbstractInsnNode.FIELD_INSN ->
                3
            AbstractInsnNode.METHOD_INSN ->
                if (opc == Opcodes.INVOKEINTERFACE) 5 else 3
            AbstractInsnNode.INVOKE_DYNAMIC_INSN ->
                5
            AbstractInsnNode.JUMP_INSN ->
                if (opc == Opcodes.GOTO || opc == Opcodes.JSR) 5 else 8
            AbstractInsnNode.LDC_INSN ->
                3
            AbstractInsnNode.IINC_INSN -> {
                val ii = ai as IincInsnNode
                if (ii.`var` >= 256 || ii.incr > 127 || ii.incr < -128) 6 else 3
            }
            AbstractInsnNode.TABLESWITCH_INSN -> {
                val si = ai as TableSwitchInsnNode
                16 + si.labels.size * 4
            }
            AbstractInsnNode.LOOKUPSWITCH_INSN -> {
                val si = ai as LookupSwitchInsnNode
                12 + si.labels.size * 8
            }
            AbstractInsnNode.MULTIANEWARRAY_INSN ->
                4
            else ->
                0
        }
    }

    // loosely based on the codesizeevaluator;
    private fun getBaselineSize() {
        baselineSize = IntArray(insnList.size + 1)

        var accum = 0

        for (i in insnList.indices) {
            val ai = insnList[i]
            var size = insnSize(ai)

            // some of these require special handling
            when (ai.getOpcode()) {
                Opcodes.RETURN ->
                    // iconst_m1; ireturn
                    size = 2
                Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.LRETURN, Opcodes.DRETURN, Opcodes.ARETURN ->
                    // xstore tmp[0-2]; aload buf; iconst_0; new java/lang/Wrapper; dup; xload tmp; invokespecial; aastore; iconst_m1; ireturn
                    size = 1 + (if (nlocal >= 254) 4 else 2) + 1 + 3 + 1 + 1 + 3 + 1 + 1 + 1
                // Opcodes.NEW may be rewritten into aconst_null if a spill is needed, but that doesn't affect the max
                Opcodes.INVOKESPECIAL -> {
                    val mi = ai as MethodInsnNode
                    if ("<init>" == mi.name) {
                        var skeep = 0
                        val f1 = types[i]!!
                        val f2 = types[i + 1]!!
                        val uninit = f1.stack[f2.sp]

                        while (skeep < f1.sp && uninit != f1.stack[skeep]) skeep++

                        size = 0
                        var ltmp = nlocal + 2 /*buf*/

                        // spill everything relevant above skeep into locals
                        for (j in skeep until f1.sp) {
                            if (uninit == f1.stack[j]) continue
                            size += if (ltmp < 256) 2 else 4
                            ltmp += 2
                        }
                        ltmp++ // keep a copy of the value

                        val argc = Type.getArgumentTypes(mi.desc).size

                        // newobj, dup, unspill, invokespecial
                        size += 3 + 1 + argc * (if (ltmp < 256) 2 else 4) + 3

                        // store in locals, including <tmp>
                        for (j in 0 until nlocal) {
                            if (uninit == f1.stack[j])
                                size += 1 + (if (j < 256) 2 else 4)
                        }
                        size += if (ltmp < 256) 2 else 4
                        // unspill
                        size += (if (ltmp < 256) 2 else 4) * (f2.sp - skeep)
                    }
                }
            }

            baselineSize[i] = accum
            accum += size
        }
        baselineSize[insnList.size] = accum

        if (DEBUG_CONTROL) System.out.println(Arrays.toString(baselineSize))
    }

    private fun nonlocalEntryExit(from: Int, to: Int): Array<IntArray> {
        // need to include entry trampolines and exit trampolines
        var entryPts = IntArray(to - from)
        var entryCt = 0
        val entryDedup = BooleanArray(to - from)
        var exitPts = IntArray(insnList.size)
        var exitCt = 0
        val exitDedup = BooleanArray(insnList.size)

        for (ce in controlEdges) {
            val from_this = (ce.from >= from && ce.from < to)
            val to_this = (ce.to >= from && ce.to < to)
            if (!from_this && to_this && !entryDedup[ce.to - from]) {
                entryDedup[ce.to - from] = true
                entryPts[entryCt++] = ce.to
            }
            if (from_this && !to_this && !exitDedup[ce.to]) {
                exitDedup[ce.to] = true
                exitPts[exitCt++] = ce.to
            }
        }

        if (from == 0 && !entryDedup[0]) {
            entryPts[entryCt++] = 0
        }

        entryPts = Arrays.copyOf(entryPts, entryCt)
        exitPts = Arrays.copyOf(exitPts, exitCt)
        Arrays.sort(entryPts)
        Arrays.sort(exitPts)

        if (DEBUG_CONTROL) {
            System.out.printf("NONLOCAL ENTRY: %s\n", Arrays.toString(entryPts))
            System.out.printf("NONLOCAL EXIT: %s\n", Arrays.toString(exitPts))
        }
        return arrayOf(entryPts, exitPts)
    }

    private fun calcFragmentSize(from: Int, to: Int): Int {
        // we have to include the instructions
        val base = baselineSize[to] - baselineSize[from]

        val ee = nonlocalEntryExit(from, to)
        val entryPts = ee[0]
        val exitPts = ee[1]
        // factor out commonalities from the trampolines
        val commonEntry = commonTrampoline(entryPts, null)
        val commonExit = commonTrampoline(exitPts, null)

        // common entry code
        // iload; aload; {dup; ipush; aaload; UNBOX; xstore; }; swap; tableswitch

        var centry = 2
        for (i in commonEntry.indices) {
            centry += localEntrySize(i, commonEntry[i])
        }
        centry += 13 // swap+tswitch

        // uncommon entry code
        var uentry = 0

        for (ept in entryPts) {
            uentry += 4 // dispatch vector
            val f = types[ept]!!
            for (j in 0 until f.sp) {
                if (j < commonEntry.size && commonEntry[j] == f.stack[j]) {
                    /* no action */
                } else if (j < nlocal) {
                    uentry += localEntrySize(j, f.stack[j])
                } else {
                    uentry += stackEntrySize(j, f.stack[j])
                }
            }
            uentry += if (nlocal <= 255) 2 else 4 // astore
            uentry += 5 // final jump
        }

        // jump insertion
        var uexit = 3

        // uncommon exit code
        for (pt in exitPts) {
            val f = types[pt]!!
            for (j in 0 until f.sp) {
                if (j < commonExit.size && commonExit[j] == f.stack[j]) {
                    /* no action */
                } else if (j < nlocal) {
                    uexit += localExitSize(j, f.stack[j])
                } else {
                    uexit += stackExitSize(j, f.stack[j])
                }
            }
            uexit += if (nlocal <= 255) 2 else 4 // aload in middle
            uexit += 3 // ipush
            uexit += 5 // jump to combiner
        }

        // common exit code
        var cexit = 1 // swap
        for (i in commonExit.indices) {
            cexit += localExitSize(i, commonExit[i])
        }
        cexit += 2 // pop; ireturn

        val total = centry + uentry + base + uexit + cexit

        if (DEBUG_FRAGMENT) System.out.printf("calcSize: %d-%d : centry(%d) uentry(%d) base(%d) uexit(%d) cexit(%d) total(%d)\n", from, to, centry, uentry, base, uexit, cexit, total)

        return total
    }

    private fun localEntrySize(loc: Int, desc: String): Int {
        val c0 = desc[0]
        if (c0 == 'T') return 0 // not loaded
        val sz: Int
        when (c0) {
            '0', 'U' ->
                // just load as a null
                sz = 1
            'L', '[' ->
                // dup, ipush, aaload, checkcast
                sz = if (loc < 128) 7 else 8
            else ->
                // dup, ipush, aaload, checkcast, fooValue
                sz = if (loc < 128) 10 else 11
        }
        return sz + (if (loc < 256) 2 else 4)
    }

    private fun localExitSize(loc: Int, desc: String): Int {
        val c0 = desc[0]
        if (c0 == 'T' || c0 == '0' || c0 == 'U') return 0 // not saved
        val sz = if (loc < 256) 2 else 4
        return when (c0) {
            'L', '[' ->
                // dup, ipush, xload, aastore
                sz + (if (loc < 128) 4 else 5)
            else ->
                // dup, ipush, new, dup, xload, invokespecial, aastore
                sz + (if (loc < 128) 11 else 12)
        }
    }

    private fun stackEntrySize(loc: Int, desc: String): Int {
        val c0 = desc[0]
        return when (c0) {
            '0', 'U' ->
                // just load as a null
                1
            'L', '[' ->
                // aload, ipush, aaload, checkcast
                if (loc < 128) 10 else 11
            else ->
                // aload, ipush, aaload, checkcast, fooValue
                if (loc < 128) 13 else 14
        }
    }

    private fun stackExitSize(loc: Int, desc: String): Int {
        val c0 = desc[0]
        if (c0 == 'T' || c0 == '0' || c0 == 'U') return 1 // not saved
        return when (c0) {
            'L', '[' ->
                // xstore, aload, ipush, xload, aastore
                if (loc < 128) 15 else 16
            else ->
                // xstore, aload, ipush, new, dup, xload, invokespecial, aastore
                if (loc < 128) 22 else 23
        }
    }

    private fun commonTrampoline(points: IntArray, spills: MutableSet<String>?): Array<String> {
        var common: Array<String>? = null
        for (i in points.indices) {
            val f = types[points[i]]!!
            if (spills != null) {
                for (j in 0 until f.sp) spills.add(f.stack[j])
            }
            if (common == null) {
                @Suppress("UNCHECKED_CAST")
                common = Arrays.copyOf(f.stack, nlocal) as Array<String>
            } else {
                for (j in common.indices) {
                    if (j >= f.sp || f.stack[j] != common[j])
                        common[j] = "T"
                }
            }
        }
        return common ?: arrayOf()
    }

    private fun allocateJumpNrs(begin: Int, end: Int) {
        val ee = nonlocalEntryExit(begin, end)

        val firstEntry = nextJumpNo
        for (entry in ee[0]) {
            jumpNoMap[entry] = nextJumpNo++
        }
        firstJump.add(firstEntry)

        if (DEBUG_FRAGMENT) System.out.printf("Fragment %d-%d has jump numbers %d-%d\n", begin, end, firstEntry, nextJumpNo)
    }

    private fun emitFragment(fno: Int, begin: Int, end: Int) {

        val v = target.visitMethod(Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_SYNTHETIC, name + "\$f" + fno, "(I[Ljava/lang/Object;)I", null, null)
        v.visitCode()

        val ee = nonlocalEntryExit(begin, end)
        val entryPts = ee[0]
        val exitPts = ee[1]
        // factor out commonalities from the trampolines
        val spilledUTypes = HashSet<String>()
        val commonEntry = commonTrampoline(entryPts, spilledUTypes)
        val commonExit = commonTrampoline(exitPts, spilledUTypes)

        val entryTrampolineLabels = Array(entryPts.size) { Label() }

        val exitTrampolineLabels = Int2ObjectOpenHashMap<Label>()
        for (i in exitPts.indices)
            exitTrampolineLabels.put(exitPts[i], Label())

        val insnLabels = Array(end - begin + 1) { Label() }

        // common entry code
        // aload; {dup; ipush; aaload; UNBOX; xstore; }; iload; tableswitch
        v.visitVarInsn(Opcodes.ILOAD, 0)
        v.visitVarInsn(Opcodes.ALOAD, 1)

        for (i in commonEntry.indices) {
            localEntryCode(v, i, commonEntry[i])
        }
        v.visitInsn(Opcodes.SWAP)
        val firstj = firstJump.getInt(fno)
        v.visitTableSwitchInsn(firstj, firstj + entryPts.size - 1, entryTrampolineLabels[0] /*XXX*/, *entryTrampolineLabels)

        val stash = nlocal
        val scratch = nlocal + 1

        // emit salient tryblocks
        for (tcbn in tryCatchBlocks) {
            val nstart = Math.max(begin, insnMap.getInt(tcbn.start))
            val nend = Math.min(end, insnMap.getInt(tcbn.end))
            val nhndlr = insnMap.getInt(tcbn.handler)
            if (nstart >= nend) continue

            v.visitTryCatchBlock(insnLabels[nstart - begin], insnLabels[nend - begin],
                    if (exitTrampolineLabels.containsKey(nhndlr)) exitTrampolineLabels.get(nhndlr) else insnLabels[nhndlr - begin],
                    tcbn.type)
        }

        // uncommon entry code
        for (ept in entryPts) {
            v.visitLabel(entryTrampolineLabels[jumpNoMap[ept] - firstj])
            val f = types[ept]!!
            for (j in 0 until nlocal) {
                if (j < commonEntry.size && commonEntry[j] == f.stack[j])
                    continue
                localEntryCode(v, j, f.stack[j])
            }
            v.visitVarInsn(Opcodes.ASTORE, stash)
            for (j in nlocal until f.sp) {
                stackEntryCode(v, stash, j, f.stack[j])
            }
            v.visitJumpInsn(Opcodes.GOTO, insnLabels[ept - begin])
        }

        // we have to include the instructions
        for (iix in begin until end) {
            emitFragmentInsn(v, iix, begin, insnLabels, exitTrampolineLabels, spilledUTypes)
        }
        v.visitLabel(insnLabels[end - begin])

        var fallthru = false
        for (ce in successors[end - 1]) {
            if (ce.to == end) {
                fallthru = true
                break
            }
        }

        if (fallthru)
            v.visitJumpInsn(Opcodes.GOTO, exitTrampolineLabels.get(end))

        var lineno = -1
        for (i in begin until end) {
            if (lineNumbers[i] != lineno) {
                lineno = lineNumbers[i]
                v.visitLineNumber(lineno, insnLabels[i - begin])
            }
        }

        val commonExitLabel = Label()

        // uncommon exit code
        for (pt in exitPts) {
            v.visitLabel(exitTrampolineLabels.get(pt))
            val f = types[pt]!!
            for (j in f.sp - 1 downTo nlocal) {
                stackExitCode(v, stash, scratch, j, f.stack[j])
            }
            v.visitVarInsn(Opcodes.ALOAD, stash)
            for (j in 0 until nlocal) {
                if (j < commonExit.size && commonExit[j] == f.stack[j])
                    continue

                localExitCode(v, j, f.stack[j])
            }
            pushInt(v, jumpNoMap[pt])
            v.visitJumpInsn(Opcodes.GOTO, commonExitLabel)
        }

        // common exit code
        if (exitPts.size > 0) {
            v.visitLabel(commonExitLabel)
            v.visitInsn(Opcodes.SWAP)
            for (i in commonExit.indices) {
                localExitCode(v, i, commonExit[i])
            }
            v.visitInsn(Opcodes.POP)
            v.visitInsn(Opcodes.IRETURN)
        }
        v.visitMaxs(0, 0)
        v.visitEnd()
    }

    private val box_types = arrayOf("java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double")
    private val box_descs = arrayOf("(I)V", "(J)V", "(F)V", "(D)V")

    private fun emitFragmentInsn(v: MethodVisitor, iix: Int, begin: Int, insnLabels: Array<Label>, exitTrampolineLabels: Int2ObjectOpenHashMap<Label>, spilledUTypes: Set<String>) {
        v.visitLabel(insnLabels[iix - begin])
        val ai = insnList[iix]

        // some instructions require very special handling
        val opc = ai.getOpcode()
        if (opc == Opcodes.RETURN) {
            v.visitInsn(Opcodes.ICONST_M1)
            v.visitInsn(Opcodes.IRETURN)
            return
        }

        if (opc >= Opcodes.IRETURN && opc <= Opcodes.ARETURN) {
            val t = opc - Opcodes.IRETURN
            v.visitVarInsn(Opcodes.ISTORE + t, nlocal + 1)
            v.visitVarInsn(Opcodes.ALOAD, nlocal)
            v.visitInsn(Opcodes.ICONST_0)
            if (opc != Opcodes.ARETURN) {
                v.visitTypeInsn(Opcodes.NEW, box_types[t])
                v.visitInsn(Opcodes.DUP)
                v.visitVarInsn(Opcodes.ILOAD + t, nlocal + 1)
                v.visitMethodInsn(Opcodes.INVOKESPECIAL, box_types[t], "<init>", box_descs[t])
            } else {
                v.visitVarInsn(Opcodes.ILOAD + t, nlocal + 1)
            }
            v.visitInsn(Opcodes.AASTORE)
            v.visitInsn(Opcodes.ICONST_M1)
            v.visitInsn(Opcodes.IRETURN)
            return
        }

        if (opc == Opcodes.NEW) {
            val f = types[iix + 1]!!
            if (spilledUTypes.contains(f.stack[f.sp - 1])) {
                v.visitInsn(Opcodes.ACONST_NULL)
                return
            }
        }

        if (opc == Opcodes.INVOKESPECIAL) {
            val mi = ai as MethodInsnNode
            val f1 = types[iix]!!
            val f2 = types[iix + 1]!!
            val uninit = f1.stack[f2.sp]
            if (mi.name == "<init>" && spilledUTypes.contains(uninit)) {
                if (f2.stack[f2.sp - 1] != uninit) throw RuntimeException("general case of INVOKESPECIAL spill not implemented")
                for (i in 0 until f2.sp - 1) if (f2.stack[i] == uninit) throw RuntimeException("general case of INVOKESPECIAL spill not implemented")

                var ltmp = nlocal + 1
                val argc = Type.getArgumentTypes(mi.desc).size
                val spillarg = IntArray(argc)

                for (d in 0 until argc) {
                    val ty0 = f1.stack[f1.sp - d - 1][0]

                    spillarg[d] = ltmp
                    v.visitVarInsn(if (ty0 == 'D') Opcodes.DSTORE else if (ty0 == 'J') Opcodes.LSTORE else if (ty0 == 'I') Opcodes.ISTORE else if (ty0 == 'F') Opcodes.FSTORE else Opcodes.ASTORE, ltmp)
                    ltmp += if (ty0 == 'D' || ty0 == 'J') 2 else 1
                }
                v.visitInsn(Opcodes.POP2)
                v.visitTypeInsn(Opcodes.NEW, mi.owner)
                v.visitInsn(Opcodes.DUP)

                /* NOTE: the Java original's unspill loop runs
                 * `for (d = argc-1; d >= 0; d++)` — an infinite loop on this
                 * (rare, spilled-<init>-with-arguments) path; faithfully
                 * preserved. */
                var d = argc - 1
                while (d >= 0) {
                    val ty0 = f1.stack[f1.sp - d - 1][0]
                    v.visitVarInsn(if (ty0 == 'D') Opcodes.DLOAD else if (ty0 == 'J') Opcodes.LLOAD else if (ty0 == 'I') Opcodes.ILOAD else if (ty0 == 'F') Opcodes.FLOAD else Opcodes.ALOAD, spillarg[d])
                    d++
                }

                v.visitMethodInsn(Opcodes.INVOKESPECIAL, mi.owner, mi.name, mi.desc)
                return
            }
        }

        // all other instructions can be processed normally, perhaps with some control-flow fudging

        when (ai.getType()) {
            AbstractInsnNode.JUMP_INSN -> {
                val ji = ai as JumpInsnNode
                v.visitJumpInsn(opc, mapLabel(ji.label, begin, insnLabels, exitTrampolineLabels))
            }
            AbstractInsnNode.TABLESWITCH_INSN -> {
                val si = ai as TableSwitchInsnNode
                val mapped = Array(si.labels.size) { i ->
                    mapLabel(si.labels[i], begin, insnLabels, exitTrampolineLabels)
                }
                v.visitTableSwitchInsn(si.min, si.max, mapLabel(si.dflt, begin, insnLabels, exitTrampolineLabels), *mapped)
            }
            AbstractInsnNode.LOOKUPSWITCH_INSN -> {
                val si = ai as LookupSwitchInsnNode
                val mapped = Array(si.labels.size) { i ->
                    mapLabel(si.labels[i], begin, insnLabels, exitTrampolineLabels)
                }
                val keys = IntArray(si.keys.size)
                for (i in keys.indices)
                    keys[i] = si.keys[i]
                v.visitLookupSwitchInsn(mapLabel(si.dflt, begin, insnLabels, exitTrampolineLabels), keys, mapped)
            }
            else ->
                ai.accept(v)
        }
    }

    private fun mapLabel(ln: LabelNode, begin: Int, insnLabels: Array<Label>, exitTrampolineLabels: Int2ObjectOpenHashMap<Label>): Label {
        val lni = insnMap.getInt(ln)
        if (exitTrampolineLabels.containsKey(lni))
            return exitTrampolineLabels.get(lni)

        return insnLabels[lni - begin]
    }

    private fun localEntryCode(v: MethodVisitor, loc: Int, desc: String) {
        val c0 = desc[0]
        if (c0 == 'T') return // not loaded
        when (c0) {
            '0', 'U' ->
                // just load as a null
                v.visitInsn(Opcodes.ACONST_NULL)
            'L', '[' -> {
                // dup, ipush, aaload, checkcast
                v.visitInsn(Opcodes.DUP)
                pushInt(v, loc)
                v.visitInsn(Opcodes.AALOAD)
                if (desc != "Ljava/lang/Object;") v.visitTypeInsn(Opcodes.CHECKCAST, if (c0 == '[') desc else desc.substring(1, desc.length - 1))
            }
            else -> {
                // dup, ipush, aaload, checkcast, fooValue
                v.visitInsn(Opcodes.DUP)
                pushInt(v, loc)
                v.visitInsn(Opcodes.AALOAD)
                unbox(v, c0)
                v.visitVarInsn(if (c0 == 'I') Opcodes.ISTORE else if (c0 == 'J') Opcodes.LSTORE else if (c0 == 'F') Opcodes.FSTORE else Opcodes.DSTORE, loc)
                return
            }
        }
        v.visitVarInsn(Opcodes.ASTORE, loc)
    }

    private fun pushInt(v: MethodVisitor, value: Int) {
        if (value >= -1 && value <= 5)
            v.visitInsn(Opcodes.ICONST_0 + value)
        else if (value >= -128 && value <= 127)
            v.visitIntInsn(Opcodes.BIPUSH, value)
        else if (value >= -32768 && value <= 32767)
            v.visitIntInsn(Opcodes.SIPUSH, value)
        else
            v.visitLdcInsn(value)
    }

    private fun unbox(v: MethodVisitor, c0: Char) {
        val c: String
        val m: String
        val d: String
        when (c0) {
            'I' -> { c = "java/lang/Integer"; m = "intValue"; d = "()I" }
            'J' -> { c = "java/lang/Long"; m = "longValue"; d = "()J" }
            'F' -> { c = "java/lang/Float"; m = "floatValue"; d = "()F" }
            'D' -> { c = "java/lang/Double"; m = "doubleValue"; d = "()D" }
            else -> throw IllegalArgumentException()
        }
        v.visitTypeInsn(Opcodes.CHECKCAST, c)
        v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, c, m, d)
    }

    private fun localExitCode(v: MethodVisitor, loc: Int, desc: String) {
        val c0 = desc[0]
        if (c0 == 'T' || c0 == '0' || c0 == 'U') return // not saved
        when (c0) {
            'L', '[' -> {
                v.visitInsn(Opcodes.DUP)
                pushInt(v, loc)
                v.visitVarInsn(Opcodes.ALOAD, loc)
                v.visitInsn(Opcodes.AASTORE)
            }
            else -> {
                v.visitInsn(Opcodes.DUP)
                pushInt(v, loc)
                run {
                    val ty: String
                    val load: Int
                    when (c0) {
                        'I' -> { ty = "java/lang/Integer"; load = Opcodes.ILOAD }
                        'J' -> { ty = "java/lang/Long"; load = Opcodes.LLOAD }
                        'F' -> { ty = "java/lang/Float"; load = Opcodes.FLOAD }
                        'D' -> { ty = "java/lang/Double"; load = Opcodes.DLOAD }
                        else -> throw IllegalArgumentException(desc)
                    }

                    v.visitTypeInsn(Opcodes.NEW, ty)
                    v.visitInsn(Opcodes.DUP)
                    v.visitVarInsn(load, loc)
                    v.visitMethodInsn(Opcodes.INVOKESPECIAL, ty, "<init>", "($c0)V")
                }
                v.visitInsn(Opcodes.AASTORE)
            }
        }
    }

    private fun stackEntryCode(v: MethodVisitor, stash: Int, loc: Int, desc: String) {
        val c0 = desc[0]
        when (c0) {
            '0', 'U' ->
                v.visitInsn(Opcodes.ACONST_NULL)
            'L', '[' -> {
                v.visitVarInsn(Opcodes.ALOAD, stash)
                pushInt(v, loc)
                v.visitInsn(Opcodes.AALOAD)
                if (desc != "Ljava/lang/Object;") v.visitTypeInsn(Opcodes.CHECKCAST, if (c0 == '[') desc else desc.substring(1, desc.length - 1))
            }
            else -> {
                v.visitVarInsn(Opcodes.ALOAD, stash)
                pushInt(v, loc)
                v.visitInsn(Opcodes.AALOAD)
                unbox(v, c0)
            }
        }
    }

    private fun stackExitCode(v: MethodVisitor, stash: Int, scratch: Int, loc: Int, desc: String) {
        val c0 = desc[0]
        if (c0 == 'T' || c0 == '0' || c0 == 'U') {
            v.visitInsn(Opcodes.POP)
            return
        }
        val ty: String
        val load: Int
        val store: Int
        when (c0) {
            'L', '[' -> {
                v.visitVarInsn(Opcodes.ASTORE, scratch)
                v.visitVarInsn(Opcodes.ALOAD, stash)
                pushInt(v, loc)
                v.visitVarInsn(Opcodes.ALOAD, scratch)
                v.visitInsn(Opcodes.AASTORE)
                return
            }
            'I' -> { ty = "java/lang/Integer"; load = Opcodes.ILOAD; store = Opcodes.ISTORE }
            'J' -> { ty = "java/lang/Long"; load = Opcodes.LLOAD; store = Opcodes.LSTORE }
            'F' -> { ty = "java/lang/Float"; load = Opcodes.FLOAD; store = Opcodes.FSTORE }
            'D' -> { ty = "java/lang/Double"; load = Opcodes.DLOAD; store = Opcodes.DSTORE }
            else -> throw IllegalArgumentException(desc)
        }

        v.visitVarInsn(store, scratch)
        v.visitVarInsn(Opcodes.ALOAD, stash)
        pushInt(v, loc)
        v.visitTypeInsn(Opcodes.NEW, ty)
        v.visitInsn(Opcodes.DUP)
        v.visitVarInsn(load, scratch)
        v.visitMethodInsn(Opcodes.INVOKESPECIAL, ty, "<init>", "($c0)V")
        v.visitInsn(Opcodes.AASTORE)
    }

    private fun becomeWrapper() {
        var maxStack = 0
        for (f in types)
            maxStack = Math.max(maxStack, f!!.sp)

        tryCatchBlocks = null
        localVariables = null
        instructions.clear()

        // allocate the scratchpad
        instructions.add(intNode(maxStack))
        instructions.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
        // move the arguments onto the scratchpad
        var ltmp = 0
        if ((access and Opcodes.ACC_STATIC) == 0) ltmp += saveArg(instructions, ltmp, Type.getType(Object::class.java))
        for (at in Type.getArgumentTypes(desc)) ltmp += saveArg(instructions, ltmp, at)

        instructions.add(VarInsnNode(Opcodes.ASTORE, 0))
        instructions.add(intNode(0))
        instructions.add(VarInsnNode(Opcodes.ISTORE, 1))

        val loop = LabelNode()
        instructions.add(loop)

        for (i in firstJump.size - 1 downTo 0) {
            val not_my_problem = LabelNode()
            instructions.add(VarInsnNode(Opcodes.ILOAD, 1))
            instructions.add(intNode(firstJump.getInt(i)))
            instructions.add(JumpInsnNode(Opcodes.IF_ICMPLT, not_my_problem))
            instructions.add(VarInsnNode(Opcodes.ILOAD, 1))
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, tgtype, name + "\$f" + i, "(I[Ljava/lang/Object;)I"))
            instructions.add(VarInsnNode(Opcodes.ISTORE, 1))
            instructions.add(JumpInsnNode(Opcodes.GOTO, loop))
            instructions.add(not_my_problem)
        }

        // time for return
        var rty: String? = null
        var unboxName: String? = null
        var unboxDesc: String? = null
        val retinst: Int
        val rtyty = Type.getReturnType(desc)
        when (rtyty.getSort()) {
            Type.VOID ->
                retinst = Opcodes.RETURN
            Type.BOOLEAN, Type.CHAR, Type.INT, Type.SHORT, Type.BYTE -> {
                retinst = Opcodes.IRETURN; rty = "java/lang/Integer"; unboxName = "intValue"; unboxDesc = "()I"
            }
            Type.LONG -> {
                retinst = Opcodes.LRETURN; rty = "java/lang/Long"; unboxName = "longValue"; unboxDesc = "()J"
            }
            Type.FLOAT -> {
                retinst = Opcodes.FRETURN; rty = "java/lang/Float"; unboxName = "floatValue"; unboxDesc = "()F"
            }
            Type.DOUBLE -> {
                retinst = Opcodes.DRETURN; rty = "java/lang/Double"; unboxName = "doubleValue"; unboxDesc = "()D"
            }
            else -> {
                retinst = Opcodes.ARETURN; rty = rtyty.getInternalName()
            }
        }

        if (rty != null) {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(InsnNode(Opcodes.ICONST_0))
            instructions.add(InsnNode(Opcodes.AALOAD))
            instructions.add(TypeInsnNode(Opcodes.CHECKCAST, rty))
            if (unboxName != null)
                instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, rty, unboxName, unboxDesc))
        }
        instructions.add(InsnNode(retinst))
    }

    private fun saveArg(il: InsnList, ltmp: Int, at: Type): Int {
        il.add(InsnNode(Opcodes.DUP))
        il.add(intNode(ltmp))
        val opc: Int
        var ty: String? = null
        var desc: String? = null
        when (at.getSort()) {
            Type.BOOLEAN, Type.CHAR, Type.INT, Type.SHORT, Type.BYTE -> {
                opc = Opcodes.ILOAD; ty = "java/lang/Integer"; desc = "(I)V"
            }
            Type.LONG -> {
                opc = Opcodes.LLOAD; ty = "java/lang/Long"; desc = "(J)V"
            }
            Type.FLOAT -> {
                opc = Opcodes.FLOAD; ty = "java/lang/Float"; desc = "(F)V"
            }
            Type.DOUBLE -> {
                opc = Opcodes.DLOAD; ty = "java/lang/Double"; desc = "(D)V"
            }
            else ->
                opc = Opcodes.ALOAD
        }

        if (ty != null) {
            il.add(TypeInsnNode(Opcodes.NEW, ty))
            il.add(InsnNode(Opcodes.DUP))
        }
        il.add(VarInsnNode(opc, ltmp))
        if (ty != null) il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, ty, "<init>", desc))
        il.add(InsnNode(Opcodes.AASTORE))
        return at.getSize()
    }
}
