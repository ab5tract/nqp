package org.raku.nqp.sixmodel.reprs

import java.lang.ref.ReferenceQueue
import java.lang.ref.SoftReference
import java.lang.reflect.Field
import java.util.ArrayList
import java.util.HashMap

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap

import org.raku.nqp.runtime.BytecodeVersion
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.Boxable
import org.raku.nqp.sixmodel.BoxedPrimitive
import org.raku.nqp.sixmodel.Inlining
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

@Suppress("DEPRECATION")
class P6Opaque : REPR() {
    companion object {
        private var typeId: Long = 0

        private val classCache = ObjectCache<String, Class<*>>()
    }

    private class AttrInfo {
        lateinit var st: STable
        var boxTarget = false
        var hasAutoVivContainer = false
        var posDelegate = false
        var assDelegate = false
    }

    // this is a weak *value* map; it provides instances of V, but does not retain them, and keeps strong refs to the keys
    private class ObjectCache<K, V> {
        private inner class Ref(@JvmField val key: K, obj: V, queue: ReferenceQueue<V>) : SoftReference<V>(obj, queue)

        private val store = HashMap<K, Ref>()
        private val queue = ReferenceQueue<V>()

        @Synchronized
        fun get(key: K): V? {
            drainQueue()
            val ref = store[key]
            return ref?.get()
        }

        @Synchronized
        fun put(key: K, value: V) {
            drainQueue()
            store.put(key, Ref(key, value, queue))
        }

        private fun drainQueue() {
            while (true) {
                @Suppress("UNCHECKED_CAST")
                val ref = queue.poll() as ObjectCache<K, V>.Ref? ?: break
                store.remove(ref.key)
            }
        }
    }

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        st.REPRData = P6OpaqueREPRData()
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    override fun compose(tc: ThreadContext, st: STable, reprInfo: SixModelObject) {
        /* Get attribute part of the protocol from the hash. */
        val attrInfo = reprInfo.at_key_boxed(tc, "attribute")!!

        /* Go through MRO and find all classes with attributes and build up
         * mapping info hashes. Note, reverse order so indexes will match
         * those in parent types. */
        var curAttr = 0
        var mi = false
        val classHandles = ArrayList<SixModelObject>()
        val attrIndexes = ArrayList<Object2IntOpenHashMap<String>>()
        val autoVivs = ArrayList<SixModelObject?>()
        val flattenedSTables = ArrayList<STable?>()
        val attrInfoList = ArrayList<AttrInfo>()
        val reprData = st.REPRData as P6OpaqueREPRData
        val mroLength = attrInfo.elems(tc)
        for (i in mroLength - 1 downTo 0) {
            val entry = attrInfo.at_pos_boxed(tc, i)!!
            val type = entry.at_pos_boxed(tc, 0)!!
            val attrs = entry.at_pos_boxed(tc, 1)!!
            val parents = entry.at_pos_boxed(tc, 2)!!

            /* If it has any attributes, give them each indexes and put them
             * in the list to add to the layout. */
            val numAttrs = attrs.elems(tc)
            if (numAttrs > 0) {
                val indexes = Object2IntOpenHashMap<String>()
                for (j in 0 until numAttrs) {
                    val attrHash = attrs.at_pos_boxed(tc, j)!!
                    val attrName = attrHash.at_key_boxed(tc, "name")!!.get_str(tc)
                    var attrType = attrHash.at_key_boxed(tc, "type")
                    if (Ops.isnull(attrType) == 1L)
                        attrType = tc.gc.KnowHOW
                    indexes.put(attrName, curAttr)
                    val info = AttrInfo()
                    info.st = attrType!!.st
                    if (attrType.st.REPR.get_storage_spec(tc, attrType.st).inlining == Inlining.INLINED)
                        flattenedSTables.add(attrType.st)
                    else
                        flattenedSTables.add(null)
                    info.boxTarget = attrHash.exists_key(tc, "box_target") != 0L
                    val autoViv = attrHash.at_key_boxed(tc, "auto_viv_container")
                    autoVivs.add(autoViv)
                    if (Ops.isnull(autoViv) == 0L)
                        info.hasAutoVivContainer = true
                    info.posDelegate = attrHash.exists_key(tc, "positional_delegate") != 0L
                    info.assDelegate = attrHash.exists_key(tc, "associative_delegate") != 0L
                    attrInfoList.add(info)

                    if (info.boxTarget) {
                        when (info.st.REPR.get_storage_spec(tc, info.st).boxedPrimitive) {
                            BoxedPrimitive.INT ->
                                reprData.unboxIntSlot = curAttr
                            //BoxedPrimitive.UINT ->
                            //    reprData.unboxUIntSlot = curAttr
                            BoxedPrimitive.NUM ->
                                reprData.unboxNumSlot = curAttr
                            BoxedPrimitive.STR ->
                                reprData.unboxStrSlot = curAttr
                            else ->
                                reprData.unboxObjSlot = curAttr
                        }
                    }
                    if (info.posDelegate)
                        reprData.posDelSlot = curAttr
                    if (info.assDelegate)
                        reprData.assDelSlot = curAttr

                    curAttr++
                }
                classHandles.add(type)
                attrIndexes.add(indexes)
            }

            /* Multiple parents means it's multiple inheritance. */
            if (parents.elems(tc) > 1)
                mi = true
        }

        /* Populate some REPR data. */
        reprData.classHandles = classHandles.toArray(arrayOfNulls<SixModelObject>(0))
        reprData.nameToHintMap = attrIndexes
        reprData.autoVivContainers = autoVivs.toArray(arrayOfNulls<SixModelObject>(0))
        reprData.flattenedSTables = flattenedSTables.toArray(arrayOfNulls<STable>(0))
        reprData.mi = mi

        /* Provided we have attributes, generate the JVM backing type. If not,
         * P6OpaqueBaseInstance will do. */
        if (attrInfoList.size > 0) {
            installJVMType(tc, st, attrInfoList)
        }
        else {
            reprData.jvmClass = P6OpaqueBaseInstance::class.java
            val instance = P6OpaqueBaseInstance()
            reprData.instance = instance
            instance.st = st
        }
    }

    /* Adds delegation, needed for mixin support. */
    private fun addDelegation(mv: MethodVisitor, methodName: String,
            retType: Type, argTypes: Array<Type>, hasValue: Boolean) {

        mv.visitVarInsn(Opcodes.ALOAD, 0) // this
        mv.visitFieldInsn(Opcodes.GETFIELD, "org/raku/nqp/sixmodel/reprs/P6OpaqueBaseInstance", "delegate",
                "Lorg/raku/nqp/sixmodel/SixModelObject;")
        mv.visitInsn(Opcodes.DUP)

        val label = Label()
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/raku/nqp/runtime/Ops", "isnull", "(Lorg/raku/nqp/sixmodel/SixModelObject;)J")
        mv.visitInsn(Opcodes.LCONST_1)
        mv.visitInsn(Opcodes.LCMP)
        mv.visitJumpInsn(Opcodes.IFEQ, label)

        mv.visitVarInsn(Opcodes.ALOAD, 1) // tc
        mv.visitVarInsn(Opcodes.ALOAD, 2) // classHandle
        mv.visitVarInsn(Opcodes.ALOAD, 3) // name
        mv.visitVarInsn(Opcodes.LLOAD, 4) // hint
        if (hasValue)
            mv.visitVarInsn(Opcodes.ALOAD, 6) // value

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/raku/nqp/sixmodel/SixModelObject", methodName,
                Type.getMethodDescriptor(retType, *argTypes))

        mv.visitInsn(if (retType == Type.VOID_TYPE) Opcodes.RETURN
                     else if (retType == Type.LONG_TYPE) Opcodes.LRETURN
                     else Opcodes.ARETURN)
        mv.visitLabel(label)
        mv.visitInsn(Opcodes.POP)
    }

    private fun installJVMType(tc: ThreadContext, st: STable, attrInfoList: List<AttrInfo>) {
        val sigBuilder = StringBuilder()

        var forceNew = false
        for (attr in attrInfoList) {

            if (attr.hasAutoVivContainer) sigBuilder.append('v')
            if (attr.posDelegate) sigBuilder.append('p')
            if (attr.assDelegate) sigBuilder.append('a')

            val ss = attr.st.REPR.get_storage_spec(tc, attr.st)
            if (ss.inlining != Inlining.REFERENCE) {
                sigBuilder.append('I')
                sigBuilder.append(attr.st.REPR.name)
                sigBuilder.append('(')
                if (!attr.st.REPR.inline_description(tc, attr.st, sigBuilder)) forceNew = true
                sigBuilder.append(')')
            }

            if (attr.boxTarget) {
                sigBuilder.append('B')
                sigBuilder.append(attr.st.REPR.name)
                sigBuilder.append('(')
                if (!attr.st.REPR.box_description(tc, attr.st, sigBuilder)) forceNew = true
                sigBuilder.append(')')
            }

            sigBuilder.append(';')
        }

        var use: Class<*>?
        if (forceNew) {
            use = generateJVMClass(tc, attrInfoList)
        } else {
            val sig = sigBuilder.toString()
            use = classCache.get(sig)
            if (use == null) {
                use = generateJVMClass(tc, attrInfoList)
                classCache.put(sig, use)
                //System.out.println("CREATE "+sig);
            } else {
                //System.out.println("REUSE "+sig);
            }
        }

        val reprData = st.REPRData as P6OpaqueREPRData
        reprData.jvmClass = use
        val instance = try {
            @Suppress("DEPRECATION")
            reprData.jvmClass!!.newInstance() as P6OpaqueBaseInstance
        }
        catch (e: ReflectiveOperationException) {
            throw RuntimeException(e)
        }
        reprData.instance = instance
        instance.st = st
    }

    private fun generateJVMClass(tc: ThreadContext, attrInfoList: List<AttrInfo>): Class<*> {
        /* Create a unique name. */
        val className = "__P6opaque__" + typeId++

        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(BytecodeVersion.EMITTED, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER, className, null,
                "org/raku/nqp/sixmodel/reprs/P6OpaqueBaseInstance", null)

        val tcType = Type.getType("Lorg/raku/nqp/runtime/ThreadContext;")
        val smoType = Type.getType("Lorg/raku/nqp/sixmodel/SixModelObject;")
        val srType = Type.getType("Lorg/raku/nqp/sixmodel/SerializationReader;")
        val stType = Type.getType("Lorg/raku/nqp/sixmodel/STable;")

        /* bind_attribute_boxed */
        val bindBoxedVisitor: MethodVisitor
        var bindBoxedSwitch: Label? = null
        var bindBoxedLabels: Array<Label>? = null
        val bindBoxedDefault = Label()
        run {
            val descriptor = Type.getMethodDescriptor(Type.VOID_TYPE,
                    tcType, smoType, Type.getType(String::class.java), Type.LONG_TYPE, smoType)
            bindBoxedVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "bind_attribute_boxed", descriptor, null, null)
            bindBoxedVisitor.visitCode()
            addDelegation(bindBoxedVisitor, "bind_attribute_boxed", Type.VOID_TYPE,
                    arrayOf(tcType, smoType, Type.getType(String::class.java), Type.LONG_TYPE, smoType), true)

            bindBoxedVisitor.visitVarInsn(Opcodes.LLOAD, 4)
            bindBoxedVisitor.visitInsn(Opcodes.L2I)

            if (attrInfoList.size > 0) {
                bindBoxedLabels = Array(attrInfoList.size) { Label() }
                bindBoxedSwitch = Label()
                bindBoxedVisitor.visitLabel(bindBoxedSwitch)
                bindBoxedVisitor.visitTableSwitchInsn(0, attrInfoList.size - 1, bindBoxedDefault, *bindBoxedLabels!!)
            }
        }

        /* bind_attribute_native */
        val bindNativeVisitor: MethodVisitor
        var bindNativeSwitch: Label? = null
        var bindNativeLabels: Array<Label>? = null
        val bindNativeDefault = Label()
        run {
            val descriptor = Type.getMethodDescriptor(Type.VOID_TYPE,
                    tcType, smoType, Type.getType(String::class.java), Type.LONG_TYPE)
            bindNativeVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "bind_attribute_native", descriptor, null, null)
            bindNativeVisitor.visitCode()
            addDelegation(bindNativeVisitor, "bind_attribute_native", Type.VOID_TYPE,
                    arrayOf(tcType, smoType, Type.getType(String::class.java), Type.LONG_TYPE), false)

            bindNativeVisitor.visitVarInsn(Opcodes.LLOAD, 4)
            bindNativeVisitor.visitInsn(Opcodes.L2I)

            if (attrInfoList.size > 0) {
                bindNativeLabels = Array(attrInfoList.size) { Label() }
                bindNativeSwitch = Label()
                bindNativeVisitor.visitLabel(bindNativeSwitch)
                bindNativeVisitor.visitTableSwitchInsn(0, attrInfoList.size - 1, bindNativeDefault, *bindNativeLabels!!)
            }
        }

        /* get_attribute_boxed */
        val getBoxedVisitor: MethodVisitor
        var getBoxedSwitch: Label? = null
        var getBoxedLabels: Array<Label>? = null
        val getBoxedDefault = Label()
        run {
            val descriptor = Type.getMethodDescriptor(smoType,
                    tcType, smoType, Type.getType(String::class.java), Type.LONG_TYPE)
            getBoxedVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "get_attribute_boxed", descriptor, null, null)
            getBoxedVisitor.visitCode()
            addDelegation(getBoxedVisitor, "get_attribute_boxed", smoType,
                    arrayOf(tcType, smoType, Type.getType(String::class.java), Type.LONG_TYPE), false)

            getBoxedVisitor.visitVarInsn(Opcodes.LLOAD, 4)
            getBoxedVisitor.visitInsn(Opcodes.L2I)

            if (attrInfoList.size > 0) {
                getBoxedLabels = Array(attrInfoList.size) { Label() }
                getBoxedSwitch = Label()
                getBoxedVisitor.visitLabel(getBoxedSwitch)
                getBoxedVisitor.visitTableSwitchInsn(0, attrInfoList.size - 1, getBoxedDefault, *getBoxedLabels!!)
            }
        }

        /* get_attribute_native */
        val getNativeVisitor: MethodVisitor
        var getNativeSwitch: Label? = null
        var getNativeLabels: Array<Label>? = null
        val getNativeDefault = Label()
        run {
            val descriptor = Type.getMethodDescriptor(Type.VOID_TYPE,
                    tcType, smoType, Type.getType(String::class.java), Type.LONG_TYPE)
            getNativeVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "get_attribute_native", descriptor, null, null)
            getNativeVisitor.visitCode()
            addDelegation(getNativeVisitor, "get_attribute_native", Type.VOID_TYPE,
                    arrayOf(tcType, smoType, Type.getType(String::class.java), Type.LONG_TYPE), false)

            getNativeVisitor.visitVarInsn(Opcodes.LLOAD, 4)
            getNativeVisitor.visitInsn(Opcodes.L2I)

            if (attrInfoList.size > 0) {
                getNativeLabels = Array(attrInfoList.size) { Label() }
                getNativeSwitch = Label()
                getNativeVisitor.visitLabel(getNativeSwitch)
                getNativeVisitor.visitTableSwitchInsn(0, attrInfoList.size - 1, getNativeDefault, *getNativeLabels!!)
            }
        }

        /* is_attribute_initialized */
        val isInitVisitor: MethodVisitor
        var isInitSwitch: Label? = null
        var isInitLabels: Array<Label>? = null
        val isInitDefault = Label()
        val isInitNull = Label()
        run {
            val descriptor = Type.getMethodDescriptor(Type.LONG_TYPE,
                    tcType, smoType, Type.getType(String::class.java), Type.LONG_TYPE)
            isInitVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "is_attribute_initialized", descriptor, null, null)
            isInitVisitor.visitCode()
            addDelegation(isInitVisitor, "is_attribute_initialized", Type.LONG_TYPE,
                    arrayOf(tcType, smoType, Type.getType(String::class.java), Type.LONG_TYPE), false)

            isInitVisitor.visitVarInsn(Opcodes.LLOAD, 4)
            isInitVisitor.visitInsn(Opcodes.L2I)

            if (attrInfoList.size > 0) {
                isInitLabels = Array(attrInfoList.size) { Label() }
                isInitSwitch = Label()
                isInitVisitor.visitLabel(isInitSwitch)
                isInitVisitor.visitTableSwitchInsn(0, attrInfoList.size - 1, isInitDefault, *isInitLabels!!)
            }
        }

        /* deserializeFields */
        val deserVisitor: MethodVisitor
        run {
            val descriptor = Type.getMethodDescriptor(Type.VOID_TYPE,
                    tcType, stType, srType)
            deserVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "deserializeFields", descriptor, null, null)
            deserVisitor.visitCode()
        }

        /* Now add all of the required fields and fill out the methods. */
        for (i in attrInfoList.indices) {
            val attr = attrInfoList[i]

            /* Is it a reference type or not? */
            val ss = attr.st.REPR.get_storage_spec(tc, attr.st)
            if (ss.inlining == Inlining.REFERENCE) {
                /* Add field. */
                val field = "field_$i"
                val desc = "Lorg/raku/nqp/sixmodel/SixModelObject;"
                cw.visitField(Opcodes.ACC_PUBLIC, field, desc, null, null)

                /* Add bind code. */
                bindBoxedVisitor.visitLabel(bindBoxedLabels!![i])
                bindBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 0)
                bindBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 6)
                bindBoxedVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, field, desc)
                bindBoxedVisitor.visitInsn(Opcodes.RETURN)

                /* Add get code. */
                getBoxedVisitor.visitLabel(getBoxedLabels!![i])
                getBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 0)
                getBoxedVisitor.visitFieldInsn(Opcodes.GETFIELD, className, field, desc)

                if (attr.hasAutoVivContainer) {
                    val end = Label()
                    getBoxedVisitor.visitInsn(Opcodes.DUP)
                    getBoxedVisitor.visitJumpInsn(Opcodes.IFNONNULL, end)
                    getBoxedVisitor.visitInsn(Opcodes.POP)
                    getBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 0)
                    getBoxedVisitor.visitIntInsn(Opcodes.BIPUSH, i)
                    getBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 1)
                    val methodDesc = "(ILorg/raku/nqp/runtime/ThreadContext;)Lorg/raku/nqp/sixmodel/SixModelObject;"
                    getBoxedVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "autoViv", methodDesc)
                    getBoxedVisitor.visitInsn(Opcodes.DUP)
                    getBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 0)
                    getBoxedVisitor.visitInsn(Opcodes.SWAP)
                    getBoxedVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, field, desc)
                    getBoxedVisitor.visitLabel(end)
                    getBoxedVisitor.visitInsn(Opcodes.ARETURN)
                }
                else {
                    getBoxedVisitor.visitInsn(Opcodes.ARETURN)
                }

                /* Add is init code. */
                isInitVisitor.visitLabel(isInitLabels!![i])
                isInitVisitor.visitVarInsn(Opcodes.ALOAD, 0)
                isInitVisitor.visitFieldInsn(Opcodes.GETFIELD, className, field, desc)
                isInitVisitor.visitJumpInsn(Opcodes.IFNULL, isInitNull)
                isInitVisitor.visitInsn(Opcodes.ICONST_1)
                isInitVisitor.visitInsn(Opcodes.I2L)
                isInitVisitor.visitInsn(Opcodes.LRETURN)

                /* Native variants should just throw. */
                bindNativeVisitor.visitLabel(bindNativeLabels!![i])
                bindNativeVisitor.visitVarInsn(Opcodes.ALOAD, 0)
                bindNativeVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "badNative", "()V")

                getNativeVisitor.visitLabel(getNativeLabels!![i])
                getNativeVisitor.visitVarInsn(Opcodes.ALOAD, 0)
                getNativeVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "badNative", "()V")

                /* We deserialize these ourselves */
                deserVisitor.visitVarInsn(Opcodes.ALOAD, 0)
                deserVisitor.visitVarInsn(Opcodes.ALOAD, 3)
                deserVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/raku/nqp/sixmodel/SerializationReader", "readRef", "()Lorg/raku/nqp/sixmodel/SixModelObject;")
                deserVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, field, desc)
            }
            else {
                /* Generate field prefix and have target REPR install the field. */
                val prefix = "field_$i"
                attr.st.REPR.inlineStorage(tc, attr.st, cw, prefix)

                /* Install bind/get instructions. */
                bindNativeVisitor.visitLabel(bindNativeLabels!![i])
                attr.st.REPR.inlineBind(tc, attr.st, bindNativeVisitor, className, prefix)
                getNativeVisitor.visitLabel(getNativeLabels!![i])
                attr.st.REPR.inlineGet(tc, attr.st, getNativeVisitor, className, prefix)

                /* Deserialization */
                attr.st.REPR.inlineDeserialize(tc, attr.st, deserVisitor, className, prefix)

                /* Add is init code. */
                isInitVisitor.visitLabel(isInitLabels!![i])
                isInitVisitor.visitInsn(Opcodes.ICONST_1)
                isInitVisitor.visitInsn(Opcodes.I2L)
                isInitVisitor.visitInsn(Opcodes.LRETURN)

                /* Reference variants should just throw. */
                bindBoxedVisitor.visitLabel(bindBoxedLabels!![i])
                bindBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 0)
                bindBoxedVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "badReference", "()V")

                getBoxedVisitor.visitLabel(getBoxedLabels!![i])
                getBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 0)
                getBoxedVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "badReference", "()V")
            }

            /* If this is a box/unbox target, make sure it gets the appropriate
             * methods.
             */
            if (attr.boxTarget) {
                if (ss.inlining == Inlining.REFERENCE)
                    throw ExceptionHandling.dieInternal(tc, "A box_target must not have a reference type attribute")
                attr.st.REPR.generateBoxingMethods(tc, attr.st, cw, className, "field_$i")
            }

            /* If it's a positional or associative delegate, give it the methods
             * for that.
             */
            if (attr.posDelegate)
                generateDelegateMethod(tc, cw, className, "field_$i", "posDelegate")
            if (attr.assDelegate)
                generateDelegateMethod(tc, cw, className, "field_$i", "assDelegate")
        }

        /* Finish bind_boxed_attribute. */
        bindBoxedVisitor.visitLabel(bindBoxedDefault)
        bindBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        bindBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 2)
        bindBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 3)
        bindBoxedVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "resolveAttribute",
                "(Lorg/raku/nqp/sixmodel/SixModelObject;Ljava/lang/String;)I")
        if (attrInfoList.size > 0)
            bindBoxedVisitor.visitJumpInsn(Opcodes.GOTO, bindBoxedSwitch)
        else
            bindBoxedVisitor.visitInsn(Opcodes.RETURN)
        bindBoxedVisitor.visitMaxs(0, 0)
        bindBoxedVisitor.visitEnd()

        /* Finish bind_native_attribute. */
        bindNativeVisitor.visitLabel(bindNativeDefault)
        bindNativeVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        bindNativeVisitor.visitVarInsn(Opcodes.ALOAD, 2)
        bindNativeVisitor.visitVarInsn(Opcodes.ALOAD, 3)
        bindNativeVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "resolveAttribute",
                "(Lorg/raku/nqp/sixmodel/SixModelObject;Ljava/lang/String;)I")
        if (attrInfoList.size > 0)
            bindNativeVisitor.visitJumpInsn(Opcodes.GOTO, bindNativeSwitch)
        else
            bindNativeVisitor.visitInsn(Opcodes.RETURN)
        bindNativeVisitor.visitMaxs(0, 0)
        bindNativeVisitor.visitEnd()

        /* Finish get_boxed_attribute. */
        getBoxedVisitor.visitLabel(getBoxedDefault)
        getBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        getBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 2)
        getBoxedVisitor.visitVarInsn(Opcodes.ALOAD, 3)
        getBoxedVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "resolveAttribute",
                "(Lorg/raku/nqp/sixmodel/SixModelObject;Ljava/lang/String;)I")
        if (attrInfoList.size > 0)
            getBoxedVisitor.visitJumpInsn(Opcodes.GOTO, getBoxedSwitch)
        else
            getBoxedVisitor.visitInsn(Opcodes.ACONST_NULL)
        getBoxedVisitor.visitInsn(Opcodes.ARETURN)
        getBoxedVisitor.visitMaxs(0, 0)
        getBoxedVisitor.visitEnd()

        /* Finish get_native_attribute. */
        getNativeVisitor.visitLabel(getNativeDefault)
        getNativeVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        getNativeVisitor.visitVarInsn(Opcodes.ALOAD, 2)
        getNativeVisitor.visitVarInsn(Opcodes.ALOAD, 3)
        getNativeVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "resolveAttribute",
                "(Lorg/raku/nqp/sixmodel/SixModelObject;Ljava/lang/String;)I")
        if (attrInfoList.size > 0)
            getNativeVisitor.visitJumpInsn(Opcodes.GOTO, getNativeSwitch)
        else
            getNativeVisitor.visitInsn(Opcodes.RETURN)
        getNativeVisitor.visitMaxs(6, 6)
        getNativeVisitor.visitEnd()

        /* Finish is_attribute_initialized. */
        isInitVisitor.visitLabel(isInitDefault)
        isInitVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        isInitVisitor.visitVarInsn(Opcodes.ALOAD, 2)
        isInitVisitor.visitVarInsn(Opcodes.ALOAD, 3)
        isInitVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "resolveAttribute",
                "(Lorg/raku/nqp/sixmodel/SixModelObject;Ljava/lang/String;)I")
        if (attrInfoList.size > 0)
            isInitVisitor.visitJumpInsn(Opcodes.GOTO, isInitSwitch)
        isInitVisitor.visitLabel(isInitNull)
        isInitVisitor.visitInsn(Opcodes.ICONST_0)
        isInitVisitor.visitInsn(Opcodes.I2L)
        isInitVisitor.visitInsn(Opcodes.LRETURN)
        isInitVisitor.visitMaxs(0, 0)
        isInitVisitor.visitEnd()

        /* Finish deserializeFields. */
        deserVisitor.visitInsn(Opcodes.RETURN)
        deserVisitor.visitMaxs(0, 0)
        deserVisitor.visitEnd()

        /* Finally, add empty constructor and generate the JVM storage class. */
        val constructor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "org/raku/nqp/sixmodel/reprs/P6OpaqueBaseInstance", "<init>", "()V")
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()

        cw.visitEnd()

        val classCompiled = cw.toByteArray()
        // Uncomment the following line to help debug the code-gen.
        if (System.getenv("NQP_DEBUG_DUMP_CLASSFILES") != null) {
            try {
                val fos = FileOutputStream(File("$className.class"))
                fos.write(classCompiled)
                fos.close()
            } catch (e: IOException) {
            }
        }
        return tc.gc.byteClassLoader.defineClass(className, classCompiled)
    }

    private fun generateDelegateMethod(tc: ThreadContext, cw: ClassWriter, className: String, field: String, methodName: String) {
        val desc = "Lorg/raku/nqp/sixmodel/SixModelObject;"

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()$desc", null, null)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(Opcodes.GETFIELD, className, field, desc)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        return (st.REPRData as P6OpaqueREPRData).instance!!.instClone()
    }

    override fun change_type(tc: ThreadContext, Object: SixModelObject, NewType: SixModelObject) {
        // Ensure target type is also P6opaque-based.
        if (NewType.st.REPR !is P6Opaque)
            throw ExceptionHandling.dieInternal(tc, "P6opaque can only rebless to another P6opaque-based type")

        // Ensure that the MROs overlap properly.
        val ourREPRData = Object.st.REPRData as P6OpaqueREPRData
        val targetREPRData = NewType.st.REPRData as P6OpaqueREPRData
        val ourClassHandles = ourREPRData.classHandles!!
        val targetClassHandles = targetREPRData.classHandles!!
        if (ourClassHandles.size > targetClassHandles.size)
            throw ExceptionHandling.dieInternal(tc, "Incompatible MROs in P6opaque rebless")
        for (i in ourClassHandles.indices) {
            if (ourClassHandles[i] !== targetClassHandles[i])
                throw ExceptionHandling.dieInternal(tc, "Incompatible MROs in P6opaque rebless")
        }

        // If there's a different number of attributes, need to set up delegate.
        // Note the condition below works because we don't make an entry in the
        // class handles list for a type with no attributes.
        val instance = Object as P6OpaqueBaseInstance
        if (ourClassHandles.size != targetClassHandles.size) {
            // Create delegate.
            val delegate = NewType.st.REPR.allocate(tc, NewType.st)

            // Find original object.
            val orig: SixModelObject
            if (Ops.isnull(instance.delegate) == 0L)
                orig = instance.delegate!!
            else
                orig = Object

            // Copy over current attribute values.
            val fromFields = orig.javaClass.fields
            val toFields = delegate.javaClass.fields
            try {
                for (i in 0 until fromFields.size - 3)
                    toFields[i].set(delegate, fromFields[i].get(orig))
            }
            catch (e: IllegalAccessException) {
                throw RuntimeException(e)
            }

            // Install.
            instance.delegate = delegate
        }
        // If we don't have to create a new delegate, but there's a delegate
        // in place already, the delegate needs a new STable as well.
        // Otherwise, methods introduced in the new type won't be available.
        else if (Ops.isnull(instance.delegate) == 0L) {
            instance.delegate!!.st = NewType.st
        }

        // Switch STable over to the new type.
        Object.st = NewType.st
    }

    override fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec {
        val rd = st.REPRData as P6OpaqueREPRData
        val canBox = buildSet<Boxable> {
            if (rd.unboxIntSlot >= 0) add(Boxable.INT)
            if (rd.unboxNumSlot >= 0) add(Boxable.NUM)
            if (rd.unboxStrSlot >= 0) add(Boxable.STR)
        }
        return StorageSpec(canBox = canBox)
    }

    override fun hint_for(tc: ThreadContext, st: STable, classHandle: SixModelObject?, name: String?): Long {
        /* A type that has not been composed yet has no REPR data to look a
         * hint up in; MoarVM answers NO_HINT there, so we do too and the
         * access falls back to a by-name lookup at runtime. */
        val rd = st.REPRData as? P6OpaqueREPRData ?: return STable.NO_HINT
        val classHandles = rd.classHandles ?: return STable.NO_HINT
        for (i in classHandles.indices) {
            if (classHandles[i] === classHandle) {
                val idx = rd.nameToHintMap!![i].getOrDefault(name, -1)
                if (idx != -1)
                    return idx.toLong()
                else
                    break
            }
        }
        return STable.NO_HINT
    }

    override fun deserialize_repr_data(tc: ThreadContext, st: STable, reader: SerializationReader) {
        // Instantiate REPR data.
        val REPRData = P6OpaqueREPRData()
        st.REPRData = REPRData

        // Get attribute count.
        val numAttributes = reader.readLong().toInt()

        // Get list of any flattened in STables.
        val flattenedSTables = arrayOfNulls<STable>(numAttributes)
        for (i in 0 until numAttributes)
            if (reader.readLong() != 0L)
                flattenedSTables[i] = reader.readSTableRef()
        REPRData.flattenedSTables = flattenedSTables

        // Read "is multiple inheritance" flag; can go straight into data.
        REPRData.mi = reader.readLong() != 0L

        // Read any auto-viv values, if we have them.
        val autoVivContainers = arrayOfNulls<SixModelObject>(numAttributes)
        REPRData.autoVivContainers = autoVivContainers
        if (reader.readLong() != 0L) {
            for (i in 0 until numAttributes)
                autoVivContainers[i] = reader.readRef()
        }

        // Read unbox slot locations.
        REPRData.unboxIntSlot = reader.readLong().toInt()
        REPRData.unboxNumSlot = reader.readLong().toInt()
        REPRData.unboxStrSlot = reader.readLong().toInt()

        // Read unbox object slot, if there is one.
        if (reader.readLong() != 0L) {
            REPRData.unboxObjSlot = reader.readLong().toInt()
        }

        // Read in the name to index mapping.
        val numClasses = reader.readLong().toInt()
        val classHandles = ArrayList<SixModelObject?>()
        val nameToHintMaps = ArrayList<Object2IntOpenHashMap<String>>()
        for (i in 0 until numClasses) {
            val classHandle = reader.readRef()
            val nameToHintObject = reader.readRef()
            if (Ops.isnull(nameToHintObject) == 1L) {
                /* Nothing to do. */
            }
            else if (nameToHintObject is VMHashInstance) {
                val nameToHintMap = Object2IntOpenHashMap<String>()
                val origMap = nameToHintObject.storage
                if (origMap.size > 0) {
                    for (key in origMap.keys)
                        nameToHintMap.put(key, origMap[key]!!.get_int(tc).toInt())
                    classHandles.add(classHandle)
                    nameToHintMaps.add(nameToHintMap)
                }
            }
            else {
                throw ExceptionHandling.dieInternal(tc, "Unexpected hint map representation in deserialize")
            }
        }
        REPRData.classHandles = classHandles.toArray(arrayOfNulls<SixModelObject>(0))
        REPRData.nameToHintMap = nameToHintMaps

        // Read delegate slots.
        REPRData.posDelSlot = reader.readLong().toInt()
        REPRData.assDelSlot = reader.readLong().toInt()

        // Finally, reassemble the Java backing type.
        val attrInfoList = ArrayList<AttrInfo>()
        for (i in 0 until numAttributes) {
            val info = AttrInfo()
            val flattened = flattenedSTables[i]
            if (flattened != null) {
                /* installJVMType below asks each flattened type for its
                 * storage spec, and for a native type that is repr data its
                 * own deserialize fills in. The STable table is not in
                 * dependency order, so make sure it has been read. */
                reader.forceSTable(flattened)
                info.st = flattened
            }
            else
                info.st = tc.gc.KnowHOW!!.st // Any reference type will do
            info.boxTarget = i == REPRData.unboxIntSlot || i == REPRData.unboxNumSlot ||
                    i == REPRData.unboxStrSlot || i == REPRData.unboxObjSlot
            info.posDelegate = i == REPRData.posDelSlot
            info.assDelegate = i == REPRData.assDelSlot
            info.hasAutoVivContainer = Ops.isnull(autoVivContainers[i]) == 0L
            attrInfoList.add(info)
        }
        if (numAttributes > 0) {
            installJVMType(tc, st, attrInfoList)
        }
        else {
            REPRData.jvmClass = P6OpaqueBaseInstance::class.java
            val instance = P6OpaqueBaseInstance()
            REPRData.instance = instance
            instance.st = st
        }
    }

    override fun serialize_repr_data(tc: ThreadContext, st: STable, writer: SerializationWriter) {
        val REPRData = st.REPRData as P6OpaqueREPRData

        val flattenedSTables = REPRData.flattenedSTables!!
        val numAttrs = flattenedSTables.size
        writer.writeInt(numAttrs.toLong())

        for (i in 0 until numAttrs) {
            val flattened = flattenedSTables[i]
            if (flattened == null) {
                writer.writeInt(0)
            }
            else {
                writer.writeInt(1)
                writer.writeSTableRef(flattened)
            }
        }

        writer.writeInt(if (REPRData.mi) 1 else 0)

        val autoVivContainers = REPRData.autoVivContainers
        if (autoVivContainers != null) {
            writer.writeInt(1)
            for (i in 0 until numAttrs)
                writer.writeRef(autoVivContainers[i])
        }
        else {
            writer.writeInt(0)
        }

        writer.writeInt(REPRData.unboxIntSlot.toLong())
        writer.writeInt(REPRData.unboxNumSlot.toLong())
        writer.writeInt(REPRData.unboxStrSlot.toLong())

        // Unbox slots
        if (REPRData.unboxObjSlot != -1) {
            writer.writeInt(1)
            writer.writeInt(REPRData.unboxObjSlot.toLong())
        }
        else {
            writer.writeInt(0)
        }

        val classHandles = REPRData.classHandles!!
        val numClasses = classHandles.size
        writer.writeInt(numClasses.toLong())
        for (i in 0 until numClasses) {
            writer.writeRef(classHandles[i])
            writer.writeIntHash(REPRData.nameToHintMap!![i])
        }

        writer.writeInt(REPRData.posDelSlot.toLong())
        writer.writeInt(REPRData.assDelSlot.toLong())
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject {
        val stub = P6OpaqueDelegateInstance()
        stub.st = st
        return stub
    }

    override fun deserialize_finish(tc: ThreadContext, st: STable,
                                    reader: SerializationReader, obj: SixModelObject) {
        // Create instance that we'll deserialize into.
        val deserInto = (st.REPRData as P6OpaqueREPRData).instance!!.instClone() as P6OpaqueBaseInstance

        // Install it as the stub's delegate.
        (obj as P6OpaqueDelegateInstance).delegate = deserInto

        // Now deserialize all the fields.
        deserInto.deserializeFields(tc, st, reader)
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        try {
            var inst = obj as P6OpaqueBaseInstance
            if (Ops.isnull(inst.delegate) == 0L)
                inst = inst.delegate as P6OpaqueBaseInstance
            val flattenedSTables = (inst.st.REPRData as P6OpaqueREPRData).flattenedSTables
                ?: throw ExceptionHandling.dieInternal(tc,
                    "Representation must be composed before it can be serialized")
            for (i in flattenedSTables.indices) {
                val flattened = flattenedSTables[i]
                if (flattened == null) {
                    writer.writeRef(inst.javaClass.getField("field_$i").get(inst) as SixModelObject?)
                }
                else {
                    flattened.REPR.serialize_inlined(tc, flattened,
                            writer, "field_$i", inst)
                }
            }
        }
        catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        }
        catch (e: NoSuchFieldException) {
            throw RuntimeException(e)
        }
    }
}
