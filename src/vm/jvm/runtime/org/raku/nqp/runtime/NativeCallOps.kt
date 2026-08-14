package org.raku.nqp.runtime

import java.util.Arrays
import java.util.HashMap

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Union

import org.raku.nqp.jast2bc.BytecodeVersion
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

import org.raku.nqp.sixmodel.REPRRegistry
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.reprs.VMArrayInstance
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i8
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i16
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i32
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_n
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_s
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_u8
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_u16
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_u32
import org.raku.nqp.sixmodel.reprs.CArray
import org.raku.nqp.sixmodel.reprs.CArrayInstance
import org.raku.nqp.sixmodel.reprs.CPointer
import org.raku.nqp.sixmodel.reprs.CPointerInstance
import org.raku.nqp.sixmodel.reprs.CStrInstance
import org.raku.nqp.sixmodel.reprs.CStruct
import org.raku.nqp.sixmodel.reprs.CStructInstance
import org.raku.nqp.sixmodel.reprs.CStructREPRData
import org.raku.nqp.sixmodel.reprs.CPPStruct
import org.raku.nqp.sixmodel.reprs.CPPStructInstance
import org.raku.nqp.sixmodel.reprs.CPPStructREPRData
import org.raku.nqp.sixmodel.reprs.CUnion
import org.raku.nqp.sixmodel.reprs.CUnionInstance
import org.raku.nqp.sixmodel.reprs.CUnionREPRData
import org.raku.nqp.sixmodel.reprs.NativeCall.ArgType
import org.raku.nqp.sixmodel.reprs.NativeCallInstance
import org.raku.nqp.sixmodel.reprs.NativeCallBody
import org.raku.nqp.sixmodel.reprs.NativeRefInstance
import org.raku.nqp.sixmodel.reprs.Refreshable
import org.raku.nqp.sixmodel.reprs.P6OpaqueBaseInstance

object NativeCallOps {
    @JvmStatic
    fun init(): Long {
        /* Nothing to do here. The REPRs are all registered over in
         * REPRRegistry.java. */
        return 1L
    }

    @JvmStatic
    fun build(target: SixModelObject, libname: String?, symbol: String, convention: String?, arguments: SixModelObject, returns: SixModelObject, tc: ThreadContext): Long {
        val call = getNativeCallBody(tc, target)

        try {
            /* Load the library and locate the symbol. */
            /* TODO: Error handling! */
            val entry_point = Ops.atkey(returns, "entry_point", tc)
            if (Ops.isnull(entry_point) == 0L) {
                /* TODO: Set the calling convention? */
                call.entry_point = Function.getFunction(Pointer(Ops.unbox_i(entry_point, tc)))
            }
            else {
                val library = if (libname == null || libname == "")
                    NativeLibrary.getProcess()
                else
                    NativeLibrary.getInstance(libname)
                call.entry_point = library.getFunction(symbol)
            }

            /* TODO: Set the calling convention. */

            /* Set up the argument types. */
            val n = arguments.elems(tc).toInt()
            val argTypes = arrayOfNulls<ArgType>(n)
            val argInfo = arrayOfNulls<SixModelObject>(n)
            for (i in 0 until n) {
                val info = arguments.at_pos_boxed(tc, i.toLong())!!
                argTypes[i] = getArgType(tc, info, false)

                if (argTypes[i] == ArgType.CALLBACK)
                    argInfo[i] = info.at_key_boxed(tc, "callback_args")
            }
            @Suppress("UNCHECKED_CAST")
            call.arg_types = argTypes as Array<ArgType>
            call.arg_info = argInfo

            call.ret_type = getArgType(tc, returns, true)

            return 1L
        }
        catch (t: Throwable) {
            throw ExceptionHandling.dieInternal(tc, t)
        }
    }

    @JvmStatic
    fun call(returns: SixModelObject, callObject: SixModelObject, arguments: SixModelObject, tc: ThreadContext): SixModelObject? {
        val call = getNativeCallBody(tc, callObject)

        try {
            val argTypes = call.arg_types!!
            /* C++ structure invocant, in case we hit a C++ constructor. */
            var cppstruct: CPPStructInstance? = null
            /* Convert arguments into array of appropriate objects. */
            val n = arguments.elems(tc).toInt()
            /* TODO: Make sure n == call.arg_types.length? */
            val cArgs = arrayOfNulls<Any>(n)
            for (i in 0 until n) {
                val arg = arguments.at_pos_boxed(tc, i.toLong())
                /* We need to allocate the struct (THIS) for C++ constructor before passing it along. */
                if (i == 0 && argTypes[i] == ArgType.CPPSTRUCT && Ops.isconcrete(arg, tc) == 0L) {
                    val repr_data = returns.st.REPRData as CPPStructREPRData
                    val structClass = repr_data.structureClass!!
                    val struct = returns.st.REPR.allocate(tc, returns.st) as CPPStructInstance
                    cppstruct = struct
                    @Suppress("DEPRECATION")
                    struct.storage = structClass.newInstance() as Structure
                    cArgs[i] = struct.storage
                }
                else {
                    cArgs[i] = toJNAType(tc, arg, argTypes[i], call.arg_info!![i])
                }
            }

            if (cppstruct != null) {
                /* We are calling a C++ constructor so we hand back the invocant (THIS) we recorded earlier. */
                call.entry_point!!.invoke(Void::class.java, cArgs)
                return toNQPType(tc, call.ret_type, returns, cppstruct.storage)
            }
            else {
                /* The actual foreign function call. */
                val returned = call.entry_point!!.invoke(javaType(tc, call.ret_type!!, returns), cArgs)

                /* Assign to NativeRefs in case the argument is in an 'is rw' param slot, or otherwise call refresh(). */
                for (i in 0 until arguments.elems(tc).toInt()) {
                    var o = arguments.at_pos_boxed(tc, i.toLong())
                    when (argTypes[i]) {
                        ArgType.CHAR_RW ->
                            (o as NativeRefInstance).store_i(tc, (cArgs[i] as Memory).getByte(0).toLong())
                        ArgType.UCHAR_RW -> {
                            var bval = (cArgs[i] as Memory).getByte(0).toLong()
                            bval += if (bval < 0) 0x100 else 0
                            (o as NativeRefInstance).store_i(tc, bval)
                        }
                        ArgType.SHORT_RW ->
                            (o as NativeRefInstance).store_i(tc, (cArgs[i] as Memory).getShort(0).toLong())
                        ArgType.USHORT_RW -> {
                            var sval = (cArgs[i] as Memory).getShort(0).toLong()
                            sval += if (sval < 0) 0x10000 else 0
                            (o as NativeRefInstance).store_i(tc, sval)
                        }
                        ArgType.INT_RW ->
                            (o as NativeRefInstance).store_i(tc, (cArgs[i] as Memory).getInt(0).toLong())
                        ArgType.UINT_RW -> {
                            var ival = (cArgs[i] as Memory).getInt(0).toLong()
                            ival += if (ival < 0) 0x100000000L else 0
                            (o as NativeRefInstance).store_i(tc, ival)
                        }
                        ArgType.LONG_RW, ArgType.ULONG_RW ->
                            (o as NativeRefInstance).store_i(tc, (cArgs[i] as Memory).getNativeLong(0).toLong())
                        ArgType.LONGLONG_RW, ArgType.ULONGLONG_RW ->
                            (o as NativeRefInstance).store_i(tc, (cArgs[i] as Memory).getLong(0))
                        ArgType.FLOAT_RW ->
                            (o as NativeRefInstance).store_n(tc, (cArgs[i] as Memory).getFloat(0).toDouble())
                        ArgType.DOUBLE_RW ->
                            (o as NativeRefInstance).store_n(tc, (cArgs[i] as Memory).getDouble(0))
                        ArgType.CPOINTER_RW -> {
                            o = Ops.decont(o, tc)
                            (o as CPointerInstance).set_int(tc, Pointer.nativeValue((cArgs[i] as Memory).getPointer(0)))
                        }
                        else ->
                            refresh(o, tc)
                    }
                }

                /* Wrap returned in the appropriate REPR type. */
                return toNQPType(tc, call.ret_type, returns, returned)
            }
        }
        catch (e: ControlException) {
            throw e
        }
        catch (t: Throwable) {
            throw ExceptionHandling.dieInternal(tc, t)
        }
    }

    @JvmStatic
    fun refresh(obj: SixModelObject?, tc: ThreadContext): Long {
        val deconted = Ops.decont(obj, tc)
        if (deconted !is Refreshable) return 1L

        deconted.refresh(tc)

        return 1L
    }

    @JvmStatic
    fun nativecallglobal(libname: String?, symbol: String, target_spec: SixModelObject, target_type: SixModelObject, tc: ThreadContext): SixModelObject? {
        try {
            /* Load the library and locate the symbol. */
            /* TODO: Error handling! */
            val library = if (libname == null || libname == "")
                NativeLibrary.getProcess()
            else
                NativeLibrary.getInstance(libname)
            var entry_point = library.getGlobalVariableAddress(symbol)

            val ss = target_spec.st.REPR.get_storage_spec(tc, target_spec.st)
            if (ss.boxed_primitive == StorageSpec.BP_STR)
                entry_point = entry_point.getPointer(0)

            return castNativeCall(tc, target_spec, target_type, entry_point)
        }
        catch (t: Throwable) {
            throw ExceptionHandling.dieInternal(tc, t)
        }
    }

    @JvmStatic
    fun nativecallsizeof(obj: SixModelObject, tc: ThreadContext): Long {
        var o = Ops.decont(obj, tc)
        val ss = o.st.REPR.get_storage_spec(tc, o.st)

        when (ss.boxed_primitive) {
            StorageSpec.BP_INT, StorageSpec.BP_UINT, StorageSpec.BP_NUM ->
                return (ss.bits / 8).toLong()
            StorageSpec.BP_STR ->
                return Native.POINTER_SIZE.toLong()
            else -> {
                if (Ops.isconcrete(o, tc) == 0L)
                    o = o.st.REPR.allocate(tc, o.st)
                if (o is CStrInstance
                 || o is CPointerInstance
                 || o is CArrayInstance) {
                    return Native.POINTER_SIZE.toLong()
                }
                else if (o is CStructInstance) {
                    return o.storage!!.size().toLong()
                }
                else if (o is CPPStructInstance) {
                    return o.storage!!.size().toLong()
                }
                else if (o is CUnionInstance) {
                    return o.storage!!.size().toLong()
                }
                else {
                    throw ExceptionHandling.dieInternal(tc,
                        String.format("NativeCall op sizeof expected type with CPointer, CStruct, CUnion, CArray, P6int or P6num representation, but got a %s", o))
                }
            }
        }
    }

    @JvmStatic
    fun nativecallcast(target_spec: SixModelObject, target_type: SixModelObject, source: SixModelObject, tc: ThreadContext): SixModelObject? {
        var o: Pointer? = null

        if (source is CPointerInstance) {
            o = source.pointer
        }
        else if (source is CArrayInstance) {
            /* NOTE: the Java original casts a CArrayInstance to
             * CStructInstance here, which throws ClassCastException;
             * faithfully preserved. */
            o = (source as CStructInstance).storage!!.getPointer()
        }
        else if (source is CStructInstance) {
            o = source.storage!!.getPointer()
        }
        else if (source is CPPStructInstance) {
            o = source.storage!!.getPointer()
        }
        else if (source is CUnionInstance) {
            o = source.storage!!.getPointer()
        }
        else {
            /* If we got something that is either a CPointer, CArray or CStruct but is not an instance,
             * it is the type object which represents NULL. So, just don't throw and we are fine. */
            if ( !(source.st.REPR is CPointer
                || source.st.REPR is CArray
                || source.st.REPR is CStruct
                || source.st.REPR is CPPStruct
                || source.st.REPR is CUnion) ) {
                throw ExceptionHandling.dieInternal(tc,
                    "Native call expected object with CPointer representation, but got something else")
            }
        }

        return castNativeCall(tc, target_spec, target_type, o)
    }

    @JvmStatic
    fun castNativeCall(tc: ThreadContext, target_spec: SixModelObject, target_type: SixModelObject, o: Pointer?): SixModelObject? {
        if (o == null)
            return target_type

        val nqpobj = target_type.st.REPR.allocate(tc, target_type.st)
        val ss = target_spec.st.REPR.get_storage_spec(tc, target_spec.st)

        when (ss.boxed_primitive) {
            StorageSpec.BP_INT, StorageSpec.BP_UINT ->
                when (ss.bits.toInt()) {
                    8 -> nqpobj.set_int(tc, o.getByte(0).toLong())
                    16 -> nqpobj.set_int(tc, o.getShort(0).toLong())
                    32 -> nqpobj.set_int(tc, o.getInt(0).toLong())
                    64 -> nqpobj.set_int(tc, o.getLong(0))
                    else ->
                        throw ExceptionHandling.dieInternal(tc,
                            String.format("Cannot cast to %d bits integer", ss.bits))
                }
            StorageSpec.BP_NUM ->
                when (ss.bits.toInt()) {
                    32 -> nqpobj.set_num(tc, o.getFloat(0).toDouble())
                    64 -> nqpobj.set_num(tc, o.getDouble(0))
                    else ->
                        throw ExceptionHandling.dieInternal(tc,
                            String.format("Cannot cast to %d bits number", ss.bits))
                }
            StorageSpec.BP_STR ->
                /* TODO: Handle encodings. */
                nqpobj.set_str(tc, o.getString(0))
            else -> {
                if (target_type is CStrInstance) {
                    /* TODO: Handle encodings. */
                    nqpobj.set_str(tc, o.getString(0))
                }
                else if (nqpobj is CPointerInstance) {
                    nqpobj.pointer = o
                }
                else if (nqpobj is CArrayInstance) {
                    nqpobj.storage = o
                    nqpobj.managed = false
                }
                else if (nqpobj is CStructInstance) {
                    val structClass = (target_type.st.REPRData as CStructREPRData).structureClass!!
                    nqpobj.storage = Structure.newInstance(structClass.asSubclass(Structure::class.java), o)
                }
                else if (nqpobj is CPPStructInstance) {
                    val structClass = (target_type.st.REPRData as CPPStructREPRData).structureClass!!
                    nqpobj.storage = Structure.newInstance(structClass.asSubclass(Structure::class.java), o)
                }
                else if (nqpobj is CUnionInstance) {
                    val structClass = (target_type.st.REPRData as CUnionREPRData).structureClass!!
                    nqpobj.storage = Union.newInstance(structClass.asSubclass(Union::class.java), o) as Union
                }
                else {
                    throw ExceptionHandling.dieInternal(tc,
                        String.format("Don't know how to cast to %s", nqpobj))
                }
            }
        }

        return nqpobj
    }

    private val ncrepr = REPRRegistry.getByName("NativeCall")
    private fun getNativeCallBody(tc: ThreadContext, target: SixModelObject): NativeCallBody {
        val call: NativeCallBody
        if (target is NativeCallInstance) {
            call = target.body!!
        }
        else {
            /* Handle mixins by following delegates. */
            var resolved = target
            if (resolved is P6OpaqueBaseInstance
            && Ops.isnull(resolved.delegate) == 0L)
                resolved = resolved.delegate!!
            var boxed = resolved.get_boxing_of(tc, ncrepr.ID.toLong()) as NativeCallBody?
            if (boxed == null) {
                boxed = NativeCallBody()
                resolved.set_boxing_of(tc, ncrepr.ID.toLong(), boxed)
            }
            call = boxed
        }
        return call
    }

    private fun javaType(tc: ThreadContext, target: ArgType, smoType: SixModelObject): Class<*> {
        return when (target) {
            ArgType.VOID -> Void::class.java
            ArgType.CHAR -> Byte::class.javaObjectType
            ArgType.SHORT -> Short::class.javaObjectType
            ArgType.INT -> Integer::class.java
            ArgType.LONG -> NativeLong::class.java
            ArgType.LONGLONG -> Long::class.javaObjectType
            ArgType.UCHAR -> Byte::class.javaObjectType
            ArgType.USHORT -> Short::class.javaObjectType
            ArgType.UINT -> Integer::class.java
            ArgType.ULONG -> NativeLong::class.java
            ArgType.ULONGLONG -> Long::class.javaObjectType
            ArgType.FLOAT -> Float::class.javaObjectType
            ArgType.DOUBLE -> Double::class.javaObjectType
            /* TODO: Handle encodings. */
            ArgType.ASCIISTR, ArgType.UTF8STR, ArgType.UTF16STR -> String::class.java
            ArgType.CPOINTER, ArgType.CARRAY -> Pointer::class.java
            ArgType.CSTRUCT -> (smoType.st.REPRData as CStructREPRData).structureClass!!
            ArgType.CPPSTRUCT -> (smoType.st.REPRData as CPPStructREPRData).structureClass!!
            ArgType.CUNION -> (smoType.st.REPRData as CUnionREPRData).structureClass!!
            else ->
                throw ExceptionHandling.dieInternal(tc, String.format("Don't know correct Java class for %s arguments yet", target))
        }
    }

    @JvmStatic
    fun toJNAType(tc: ThreadContext, o: SixModelObject?, target: ArgType?, info: SixModelObject?): Any? {
        var v = o
        when (target!!) {
        ArgType.CHAR -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v.get_int(tc).toByte()
        }
        ArgType.SHORT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v.get_int(tc).toShort()
        }
        ArgType.INT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v.get_int(tc).toInt()
        }
        ArgType.LONG -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return NativeLong(v.get_int(tc))
        }
        ArgType.LONGLONG -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v.get_int(tc)
        }
        ArgType.UCHAR -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v.get_int(tc).toByte()
        }
        ArgType.USHORT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v.get_int(tc).toShort()
        }
        ArgType.UINT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v.get_int(tc).toInt()
        }
        ArgType.ULONG -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return NativeLong(v.get_int(tc))
        }
        ArgType.ULONGLONG -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v.get_int(tc)
        }
        ArgType.FLOAT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v.get_num(tc).toFloat()
        }
        ArgType.DOUBLE -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v.get_num(tc)
        }
        ArgType.ASCIISTR, ArgType.UTF8STR, ArgType.UTF16STR -> {
            /* TODO: Handle encodings. */
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            val meth = Ops.findmethodNonFatal(v, "cstr", tc)
            if (meth != null) {
                Ops.invokeDirect(tc, meth, CallSiteDescriptor(byteArrayOf(CallSiteDescriptor.ARG_OBJ), null), arrayOf<Any?>(v))
                val cstr = Ops.decont(Ops.result_o(tc.resultFrame()), tc) as CStrInstance
                return cstr.cstr
            }
            else {
                return v.get_str(tc)
            }
        }
        ArgType.CPOINTER -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return (v as CPointerInstance).pointer
        }
        ArgType.CARRAY -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return (v as CArrayInstance).storage
        }
        ArgType.CSTRUCT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return (v as CStructInstance).storage
        }
        ArgType.CPPSTRUCT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return (v as CPPStructInstance).storage
        }
        ArgType.CUNION -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return (v as CUnionInstance).storage
        }
        ArgType.CALLBACK -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return callbackHandlerFor(v, info!!, tc)
        }
        ArgType.VMARRAY -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            if (v is VMArrayInstance_i) {
                return v.slots
            }
            if (v is VMArrayInstance_i8) {
                return v.slots
            }
            else if (v is VMArrayInstance_i16) {
                return v.slots
            }
            else if (v is VMArrayInstance_i32) {
                return v.slots
            }
            else if (v is VMArrayInstance_n) {
                return v.slots
            }
            else if (v is VMArrayInstance_s) {
                return v.slots
            }
            else if (v is VMArrayInstance_u8) {
                return v.slots
            }
            else if (v is VMArrayInstance_u16) {
                return v.slots
            }
            else if (v is VMArrayInstance_u32) {
                return v.slots
            }
            return (v as VMArrayInstance).slots
        }
        ArgType.CHAR_RW, ArgType.UCHAR_RW -> {
            if (Ops.iscont_i(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native integer, but got %s", v))
            val m = Memory(java.lang.Byte.SIZE.toLong())
            m.setByte(0, (v as NativeRefInstance).fetch_i(tc).toByte())
            return m
        }
        ArgType.SHORT_RW, ArgType.USHORT_RW -> {
            if (Ops.iscont_i(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native integer, but got %s", v))
            val m = Memory(java.lang.Short.SIZE.toLong())
            m.setShort(0, (v as NativeRefInstance).fetch_i(tc).toShort())
            return m
        }
        ArgType.INT_RW, ArgType.UINT_RW -> {
            if (Ops.iscont_i(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native integer, but got %s", v))
            val m = Memory(Integer.SIZE.toLong())
            m.setInt(0, (v as NativeRefInstance).fetch_i(tc).toInt())
            return m
        }
        ArgType.LONG_RW, ArgType.ULONG_RW -> {
            if (Ops.iscont_i(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native integer, but got %s", v))
            val m = Memory(NativeLong.SIZE.toLong())
            m.setNativeLong(0, NativeLong((v as NativeRefInstance).fetch_i(tc)))
            return m
        }
        ArgType.LONGLONG_RW, ArgType.ULONGLONG_RW -> {
            if (Ops.iscont_i(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native integer, but got %s", v))
            val m = Memory(java.lang.Long.SIZE.toLong())
            m.setLong(0, (v as NativeRefInstance).fetch_i(tc))
            return m
        }
        ArgType.FLOAT_RW -> {
            if (Ops.iscont_n(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native number, but got %s", v))
            val m = Memory(java.lang.Float.SIZE.toLong())
            m.setFloat(0, (v as NativeRefInstance).fetch_n(tc).toFloat())
            return m
        }
        ArgType.DOUBLE_RW -> {
            if (Ops.iscont_n(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native number, but got %s", v))
            val m = Memory(java.lang.Double.SIZE.toLong())
            m.setDouble(0, (v as NativeRefInstance).fetch_n(tc))
            return m
        }
        ArgType.CPOINTER_RW -> {
            val m = Memory(Native.POINTER_SIZE.toLong())
            v = Ops.decont(v, tc)
            val ptr = (v as CPointerInstance).get_int(tc)
            if (ptr > 0)
                m.setPointer(0, Pointer.createConstant(ptr))
            else
                m.setPointer(0, null)
            return m
        }
        else ->
            throw ExceptionHandling.dieInternal(tc, String.format("Don't know how to convert %s arguments to JNA yet", target))
        }
    }

    @JvmStatic
    fun toNQPType(tc: ThreadContext, target: ArgType?, type: SixModelObject?, o: Any?): SixModelObject? {
        var nqpobj = type

        when (target!!) {
        ArgType.VOID ->
            return type
        ArgType.CHAR -> {
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            val value = (o as Byte)
            nqpobj.set_int(tc, value.toLong())
        }
        ArgType.SHORT -> {
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            val value = (o as Short)
            nqpobj.set_int(tc, value.toLong())
        }
        ArgType.INT -> {
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            val value = (o as Int)
            nqpobj.set_int(tc, value.toLong())
        }
        ArgType.LONG -> {
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            val value = (o as NativeLong).toLong()
            nqpobj.set_int(tc, value)
        }
        ArgType.LONGLONG -> {
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            val value = (o as Long)
            nqpobj.set_int(tc, value)
        }
        ArgType.UCHAR -> {
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            var value = (o as Byte).toLong()
            if (value < 0)
                value += 0x100
            nqpobj.set_int(tc, value)
        }
        ArgType.USHORT -> {
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            var value = (o as Short).toLong()
            if (value < 0)
                value += 0x10000
            nqpobj.set_int(tc, value)
        }
        ArgType.UINT -> {
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            var value = (o as Int).toLong()
            if (value < 0)
                value += 0x100000000L
            nqpobj.set_int(tc, value)
        }
        ArgType.ULONG -> {
            /* TODO: handle unsignedness properly. */
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            val value = (o as NativeLong).toLong()
            nqpobj.set_int(tc, value)
        }
        ArgType.ULONGLONG -> {
            /* TODO: handle unsignedness properly. */
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            val value = (o as Long)
            nqpobj.set_int(tc, value)
        }
        ArgType.FLOAT -> {
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            val value = (o as Float)
            nqpobj.set_num(tc, value.toDouble())
        }
        ArgType.DOUBLE -> {
            nqpobj = type!!.st.REPR.allocate(tc, type.st)
            val value = (o as Double)
            nqpobj.set_num(tc, value)
        }
        ArgType.ASCIISTR, ArgType.UTF8STR, ArgType.UTF16STR ->
            /* TODO: Handle encodings. */
            if (o != null) {
                nqpobj = type!!.st.REPR.allocate(tc, type.st)
                nqpobj.set_str(tc, o as String)
            }
        ArgType.CPOINTER ->
            if (o != null) {
                nqpobj = type!!.st.REPR.allocate(tc, type.st)
                val cpointer = nqpobj as CPointerInstance
                cpointer.pointer = o as Pointer
            }
        ArgType.CARRAY ->
            if (o != null) {
                nqpobj = type!!.st.REPR.allocate(tc, type.st)
                val carray = nqpobj as CArrayInstance
                carray.storage = o as Pointer
                carray.managed = false
            }
        ArgType.CSTRUCT ->
            if (o != null) {
                nqpobj = type!!.st.REPR.allocate(tc, type.st)
                val cstruct = nqpobj as CStructInstance
                cstruct.storage = o as Structure
            }
        ArgType.CPPSTRUCT ->
            if (o != null) {
                nqpobj = type!!.st.REPR.allocate(tc, type.st)
                val cppstruct = nqpobj as CPPStructInstance
                cppstruct.storage = o as Structure
            }
        ArgType.CUNION ->
            if (o != null) {
                nqpobj = type!!.st.REPR.allocate(tc, type.st)
                val cunion = nqpobj as CUnionInstance
                cunion.storage = o as Union
            }
        else ->
            throw ExceptionHandling.dieInternal(tc, String.format("Don't know how to convert %s arguments to NQP yet", target))
        }

        return nqpobj
    }

    private fun getArgType(tc: ThreadContext, info: SixModelObject, isReturn: Boolean): ArgType {
        var type_name = info.at_key_boxed(tc, "type")!!.get_str(tc)!!

        val rw = info.at_key_boxed(tc, "rw")
        if (rw != null && rw.get_int(tc) == 1L)
            type_name += "_RW"

        var type = ArgType.VOID
        try {
            type = java.lang.Enum.valueOf(ArgType::class.java, type_name.uppercase())
        }
        catch (e: IllegalArgumentException) {
            throw ExceptionHandling.dieInternal(tc, String.format("Unknown type '%s' used for native call", type_name))
        }

        if (!isReturn && type == ArgType.VOID) {
            throw ExceptionHandling.dieInternal(tc, "Can only use 'void' type on native call return values")
        }

        return type
    }

    @JvmField var typeId = 0
    @JvmField var handlerName: String = Type.getInternalName(CallbackHandler::class.java)
    private val callbackHandlers = HashMap<SixModelObject, CallbackHandler>()
    private val callbackClasses = HashMap<String, Class<CallbackHandler>>()

    private fun callbackHandlerFor(function: SixModelObject, infos: SixModelObject, tc: ThreadContext): CallbackHandler {
        val existing = callbackHandlers.get(function)
        if (existing != null) return existing

        /* Extract the information we need from the list of infos. The first
         * element of the list is the return type and any following items are
         * the argument types. We process the arguments first, since a JVM
         * method signature has the form "($arguments)$returns".
         *
         * At the same time, we collect the data needed for the callback when
         * it's time to call back into NQP: type objects for argument and
         * return types, and the ArgTypes for all of them.
         */
        val num_info = infos.elems(tc).toInt()
        val argumentTypes = arrayOfNulls<SixModelObject>(num_info - 1)
        val argumentInfo = arrayOfNulls<ArgType>(num_info - 1)
        val isVoid = infos.at_pos_boxed(tc, 0L)!!.at_key_boxed(tc, "type")!!.get_str(tc) == "void"
        val sb = StringBuilder("(")
        for (i in 1 until num_info) {
            val info = infos.at_pos_boxed(tc, i.toLong())!!
            val type = info.at_key_boxed(tc, "typeobj")
            sb.append(Type.getDescriptor(javaType(tc, getArgType(tc, info, false), type!!)))
            argumentTypes[i - 1] = type
            argumentInfo[i - 1] = getArgType(tc, info, false)
        }
        sb.append(")")

        val info = infos.at_pos_boxed(tc, 0L)!!
        val returnType = info.at_key_boxed(tc, "typeobj")
        val returnInfo = getArgType(tc, info, true)
        var javaReturn: Class<*>? = null
        if (!isVoid) javaReturn = javaType(tc, getArgType(tc, info, true), returnType!!)
        sb.append(if (isVoid) "V" else Type.getDescriptor(javaReturn))
        val sig = sb.toString()
        var handlerClass = callbackClasses.get(sig)

        if (handlerClass == null) {
            val typeNo = typeId++

            /* We need to generate two separate pieces two work with callbacks
             * in JNA: an interface, which specifies which method to call and
             * its signature, and a class implementing that interface that
             * does the actual work.
             *
             * To keep codegen to a minimum, we'll only ever have a single
             * class implementing each interface (one for each type signature
             * used), with the classes delegating to the correct NQP function.
             */

            val ifaceWriter = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
            val ifaceName = "__CallbackInterface__$typeNo"

            // public interface $interfaceName extends com.sun.jna.Callback { ... }
            ifaceWriter.visit(BytecodeVersion.EMITTED, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
                    ifaceName, null, "java/lang/Object", arrayOf(Type.getInternalName(Callback::class.java)))
            // public $sig[0] callback($sig[1..*]);
            val ifaceMeth = ifaceWriter.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "callback", sig, null, null)
            ifaceMeth.visitEnd()

            ifaceWriter.visitEnd()
            val ifaceCompiled = ifaceWriter.toByteArray()
            val iface = tc.gc.byteClassLoader.defineClass(ifaceName, ifaceCompiled)

            val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
            val className = "__CallbackHandler__$typeNo"

            // public class $className extends CallbackHandler implements $ifaceName { ... }
            classWriter.visit(BytecodeVersion.EMITTED, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, className, null,
                    handlerName, arrayOf(Type.getInternalName(iface)))

            // public $className(GlobalContext gc, SixModelObject function) { super(gc, function); }
            //String ctorSig = "(Lorg/raku/nqp/runtime/GlobalContext;Lorg/raku/nqp/sixmodel/SixModelObject;)V";
            val ctorSig = String.format("(%s%s%s%s%s)V",
                    Type.getDescriptor(GlobalContext::class.java), Type.getDescriptor(SixModelObject::class.java),
                    Type.getDescriptor(ArgType::class.java),
                    Type.getDescriptor(Array<SixModelObject>::class.java), Type.getDescriptor(Array<ArgType>::class.java))
            val constructor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorSig, null, null)
            constructor.visitCode()
            constructor.visitVarInsn(Opcodes.ALOAD, 0)
            constructor.visitVarInsn(Opcodes.ALOAD, 1)
            constructor.visitVarInsn(Opcodes.ALOAD, 2)
            constructor.visitVarInsn(Opcodes.ALOAD, 3)
            constructor.visitVarInsn(Opcodes.ALOAD, 4)
            constructor.visitVarInsn(Opcodes.ALOAD, 5)
            @Suppress("DEPRECATION")
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, handlerName, "<init>", ctorSig)
            constructor.visitInsn(Opcodes.RETURN)
            constructor.visitMaxs(6, 6)
            constructor.visitEnd()

            // public $sig[0] callback($sig[1..*]) { ... }
            val callback = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "callback", sig, null, null)
            //   Object[] args = new Object[$argCount];
            callback.visitLdcInsn(num_info - 1)
            callback.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")

            //  args[$i] = $sig[$i+1];
            for (i in 0 until num_info - 1) {
                callback.visitInsn(Opcodes.DUP) // Dup the array, since we'll store to it
                callback.visitLdcInsn(i)
                callback.visitVarInsn(Opcodes.ALOAD, i + 1)
                callback.visitInsn(Opcodes.AASTORE)
            }

            //  callFunction(args);
            callback.visitVarInsn(Opcodes.ALOAD, 0)
            callback.visitInsn(Opcodes.SWAP)
            @Suppress("DEPRECATION")
            callback.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "callFunction", "([Ljava/lang/Object;)Ljava/lang/Object;")

            if (isVoid) {
                callback.visitInsn(Opcodes.POP)
                callback.visitInsn(Opcodes.RETURN)
            }
            else {
                callback.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(javaReturn))
                callback.visitInsn(Opcodes.ARETURN)
            }

            callback.visitMaxs(4, num_info)
            callback.visitEnd()

            classWriter.visitEnd()
            val classCompiled = classWriter.toByteArray()
            /* Uncomment to dump generated class to file:
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(new java.io.File(className + ".class"));
                fos.write(classCompiled);
                fos.close();
            } catch (java.io.IOException e) {
            }
            */
            @Suppress("UNCHECKED_CAST")
            handlerClass = tc.gc.byteClassLoader.defineClass(className, classCompiled) as Class<CallbackHandler>
            callbackClasses.put(sig, handlerClass)
        }

        val handler: CallbackHandler
        try {
            val ctor = handlerClass.getConstructor(GlobalContext::class.java, SixModelObject::class.java,
                    ArgType::class.java,
                    Array<SixModelObject>::class.java, Array<ArgType>::class.java)
            handler = ctor.newInstance(tc.gc, function,
                    returnInfo,
                    argumentTypes, argumentInfo)
        }
        catch (e: Exception) {
            throw ExceptionHandling.dieInternal(tc, e)
        }
        callbackHandlers.put(function, handler)
        return handler
    }

    abstract class CallbackHandler protected constructor(
        @JvmField var gc: GlobalContext,
        @JvmField var function: SixModelObject,
        @JvmField var returnInfo: ArgType,
        @JvmField var argumentTypes: Array<SixModelObject>,
        @JvmField var argumentInfo: Array<ArgType>,
    ) : Callback {
        @JvmField var callsite: CallSiteDescriptor

        init {
            val desc = ByteArray(argumentTypes.size)
            Arrays.fill(desc, CallSiteDescriptor.ARG_OBJ)
            this.callsite = CallSiteDescriptor(desc, null)
        }

        protected fun callFunction(vararg args: Any?): Any? {
            val tc = gc.getCurrentThreadContext()!!

            /* TODO: Make sure args.length == argumentTypes.length */

            val converted = arrayOfNulls<Any>(args.size)
            for (i in args.indices) {
                converted[i] = toNQPType(tc, argumentInfo[i], argumentTypes[i], args[i])
            }
            Ops.invokeDirect(tc, function, callsite, converted)

            if (returnInfo == ArgType.VOID) {
                return null
            }
            else {
                return toJNAType(tc, Ops.decont(Ops.result_o(tc.resultFrame()), tc), returnInfo, null)
            }
        }
    }
}
