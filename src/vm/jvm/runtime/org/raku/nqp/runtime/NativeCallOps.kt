package org.raku.nqp.runtime

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

import java.util.Arrays
import java.util.HashMap

import org.raku.nqp.sixmodel.BoxedPrimitive
import org.raku.nqp.sixmodel.REPRRegistry
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.Boxable
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.reprs.CArray
import org.raku.nqp.sixmodel.reprs.CArrayInstance
import org.raku.nqp.sixmodel.reprs.CPPStruct
import org.raku.nqp.sixmodel.reprs.CPPStructInstance
import org.raku.nqp.sixmodel.reprs.CPointer
import org.raku.nqp.sixmodel.reprs.CPointerInstance
import org.raku.nqp.sixmodel.reprs.CStrInstance
import org.raku.nqp.sixmodel.reprs.CStruct
import org.raku.nqp.sixmodel.reprs.CStructInstance
import org.raku.nqp.sixmodel.reprs.CTypeInstance
import org.raku.nqp.sixmodel.reprs.CTypeREPRData
import org.raku.nqp.sixmodel.reprs.CUnion
import org.raku.nqp.sixmodel.reprs.CUnionInstance
import org.raku.nqp.sixmodel.reprs.NativeCall.ArgType
import org.raku.nqp.sixmodel.reprs.NativeCallBody
import org.raku.nqp.sixmodel.reprs.NativeCallInstance
import org.raku.nqp.sixmodel.reprs.NativeRefInstance
import org.raku.nqp.sixmodel.reprs.Refreshable
import org.raku.nqp.sixmodel.reprs.VMArrayInstance
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i16
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i32
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_i8
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_n
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_s
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_u16
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_u32
import org.raku.nqp.sixmodel.reprs.VMArrayInstance_u8

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
            val given = Ops.atkey(returns, "entry_point", tc)
            val address = if (Ops.isnull(given) == 0L) {
                /* TODO: Set the calling convention? */
                NativeSupport.pointer(Ops.unbox_i(given, tc))
            }
            else {
                NativeSupport.libraryLookup(libname).find(symbol).orElseThrow {
                    UnsatisfiedLinkError("Cannot find symbol '$symbol'"
                        + (if (libname.isNullOrEmpty()) " in the running process" else " in library '$libname'"))
                }
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
            call.argTypes = argTypes as Array<ArgType>
            call.argInfo = argInfo

            call.retType = getArgType(tc, returns, true)

            call.handle = NativeSupport.LINKER.downcallHandle(address,
                descriptorFor(tc, call.retType!!, call.argTypes!!))
            call.ctorHandle = null
            /* Last: a non-null entry point is how Rakudo's !setup decides the
             * call site is already built, so nothing may be missing once it
             * is set. */
            call.entryPoint = address

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
            val argTypes = call.argTypes!!
            /* C++ structure invocant, in case we hit a C++ constructor. */
            var cppstruct: CPPStructInstance? = null
            /* Convert arguments into array of appropriate objects. */
            val n = arguments.elems(tc).toInt()
            /* TODO: Make sure n == call.argTypes.length? */
            val cArgs = arrayOfNulls<Any>(n)
            for (i in 0 until n) {
                val arg = arguments.at_pos_boxed(tc, i.toLong())
                /* We need to allocate the struct (THIS) for C++ constructor before passing it along. */
                if (i == 0 && argTypes[i] == ArgType.CPPSTRUCT && Ops.isconcrete(arg, tc) == 0L) {
                    val struct = returns.st.REPR.allocate(tc, returns.st) as CPPStructInstance
                    cppstruct = struct
                    cArgs[i] = struct.storage
                }
                else {
                    cArgs[i] = toNativeType(tc, arg, argTypes[i], call.argInfo!![i])
                }

                /* C wants to see a null pointer, not a Java null. */
                if (cArgs[i] == null && isPointerType(argTypes[i]))
                    cArgs[i] = MemorySegment.NULL
            }

            if (cppstruct != null) {
                /* We are calling a C++ constructor so we hand back the invocant (THIS) we recorded earlier. */
                ctorHandle(tc, call).invokeWithArguments(*cArgs)
                return toNQPType(tc, call.retType, returns, cppstruct.storage)
            }
            else {
                /* The actual foreign function call. */
                val returned = call.handle!!.invokeWithArguments(*cArgs)

                /* Assign to NativeRefs in case the argument is in an 'is rw' param slot, or otherwise call refresh(). */
                for (i in 0 until arguments.elems(tc).toInt())
                    writeBackRW(tc, arguments.at_pos_boxed(tc, i.toLong()),
                        cArgs[i] as? MemorySegment, argTypes[i])

                /* Wrap returned in the appropriate REPR type. */
                return toNQPType(tc, call.retType, returns, returned)
            }
        }
        catch (e: ControlException) {
            throw e
        }
        catch (t: Throwable) {
            throw ExceptionHandling.dieInternal(tc, t)
        }
    }

    /** After a native call, propagates what the callee wrote through an 'is
     * rw' reference or an array back into the argument, or refreshes it. */
    private fun writeBackRW(tc: ThreadContext, arg: SixModelObject?, ref: MemorySegment?,
                            type: ArgType?) {
        var o = arg
        when (type) {
            ArgType.CHAR_RW ->
                (o as NativeRefInstance).store_i(tc, ref!!.get(ValueLayout.JAVA_BYTE, 0).toLong())
            ArgType.UCHAR_RW -> {
                var bval = ref!!.get(ValueLayout.JAVA_BYTE, 0).toLong()
                bval += if (bval < 0) 0x100 else 0
                (o as NativeRefInstance).store_i(tc, bval)
            }
            ArgType.SHORT_RW ->
                (o as NativeRefInstance).store_i(tc, ref!!.get(ValueLayout.JAVA_SHORT, 0).toLong())
            ArgType.USHORT_RW -> {
                var sval = ref!!.get(ValueLayout.JAVA_SHORT, 0).toLong()
                sval += if (sval < 0) 0x10000 else 0
                (o as NativeRefInstance).store_i(tc, sval)
            }
            ArgType.INT_RW ->
                (o as NativeRefInstance).store_i(tc, ref!!.get(ValueLayout.JAVA_INT, 0).toLong())
            ArgType.UINT_RW -> {
                var ival = ref!!.get(ValueLayout.JAVA_INT, 0).toLong()
                ival += if (ival < 0) 0x100000000L else 0
                (o as NativeRefInstance).store_i(tc, ival)
            }
            ArgType.LONG_RW, ArgType.ULONG_RW ->
                (o as NativeRefInstance).store_i(tc, readCLong(ref!!, 0))
            ArgType.LONGLONG_RW, ArgType.ULONGLONG_RW ->
                (o as NativeRefInstance).store_i(tc, ref!!.get(ValueLayout.JAVA_LONG, 0))
            ArgType.FLOAT_RW ->
                (o as NativeRefInstance).store_n(tc, ref!!.get(ValueLayout.JAVA_FLOAT, 0).toDouble())
            ArgType.DOUBLE_RW ->
                (o as NativeRefInstance).store_n(tc, ref!!.get(ValueLayout.JAVA_DOUBLE, 0))
            ArgType.CPOINTER_RW -> {
                o = Ops.decont(o, tc)
                (o as CPointerInstance).set_int(tc, NativeSupport.address(ref!!.get(ValueLayout.ADDRESS, 0)))
            }
            ArgType.VMARRAY -> {
                /* The callee wrote through the pointer we handed
                 * it, so copy the buffer back over the slots. */
                vmarrayFromNative(tc, Ops.decont(o, tc), ref)
                refresh(o, tc)
            }
            else ->
                refresh(o, tc)
        }
    }

    /**
     * The dispatcher-path native call, reached through the boot-foreign-code
     * dispatcher with (call object, return type, args...). Unlike [call],
     * whose arguments arrive as a list of boxed objects, these arrive as
     * dispatch values — native ints/nums/strs wherever the dispatch program
     * unboxed them, boxed objects otherwise — so conversion goes by the
     * callsite argument kind first and the C parameter type second.
     */
    @JvmStatic
    fun dispatchCall(tc: ThreadContext, descriptor: CallSiteDescriptor,
                     args: Array<Any?>): org.raku.nqp.dispatch.DispatchValue {
        val callObject = args[0] as SixModelObject
        val returns = args[1] as SixModelObject
        val call = getNativeCallBody(tc, callObject)
        try {
            val argTypes = call.argTypes!!
            var n = args.size - 2
            /* A variadic call carries more arguments than the built site has
             * types for; the dispatcher appends a bitfield naming which of
             * them are pass-by-pointer-and-write-back. */
            val isVariadic = n > argTypes.size
            var rwBitfield = 0L
            if (isVariadic) {
                n -= 1
                rwBitfield = Ops.unbox_i(Ops.decont(args[n + 2] as SixModelObject?, tc), tc)
            }
            if (n != argTypes.size && !isVariadic)
                throw ExceptionHandling.dieInternal(tc,
                    "Wrong number of arguments to a native call: got $n, expected ${argTypes.size}")
            val varargLayouts =
                if (isVariadic) arrayOfNulls<MemoryLayout>(n - argTypes.size) else null
            val cArgs = arrayOfNulls<Any>(n)
            /* C++ structure invocant, in case we hit a C++ constructor. */
            var cppstruct: CPPStructInstance? = null
            for (i in 0 until n) {
                if (i >= argTypes.size) {
                    /* A variadic argument: its C type comes from what the
                     * dispatch program made of it, mirroring the default
                     * argument promotions. */
                    val value = args[i + 2]
                    val j = i - argTypes.size
                    when (org.raku.nqp.dispatch.ArgKind.ofFlag(descriptor.argFlags[i + 2])) {
                        org.raku.nqp.dispatch.ArgKind.INT,
                        org.raku.nqp.dispatch.ArgKind.UINT -> {
                            varargLayouts!![j] = ValueLayout.JAVA_LONG
                            cArgs[i] = value as Long
                        }
                        org.raku.nqp.dispatch.ArgKind.NUM -> {
                            varargLayouts!![j] = ValueLayout.JAVA_DOUBLE
                            cArgs[i] = value as Double
                        }
                        org.raku.nqp.dispatch.ArgKind.STR -> {
                            varargLayouts!![j] = ValueLayout.ADDRESS
                            cArgs[i] = if (value == null) MemorySegment.NULL
                                       else NativeSupport.toCString(value as String)
                        }
                        org.raku.nqp.dispatch.ArgKind.OBJ -> {
                            if ((rwBitfield shr i) and 1L == 1L) {
                                varargLayouts!![j] = ValueLayout.ADDRESS
                                cArgs[i] = rwVarargToNative(tc, value as SixModelObject?)
                            }
                            else {
                                val (layout, cValue) = varargToNative(tc, value as SixModelObject?)
                                varargLayouts!![j] = layout
                                cArgs[i] = cValue
                            }
                        }
                    }
                    continue
                }
                val type = argTypes[i]
                val value = args[i + 2]
                cArgs[i] = when (org.raku.nqp.dispatch.ArgKind.ofFlag(descriptor.argFlags[i + 2])) {
                    org.raku.nqp.dispatch.ArgKind.OBJ -> {
                        if (i == 0 && type == ArgType.CPPSTRUCT &&
                                Ops.isconcrete(value as SixModelObject?, tc) == 0L) {
                            /* A C++ constructor: allocate the struct (THIS)
                             * to hand to the callee, and return it after. */
                            val struct = returns.st.REPR.allocate(tc, returns.st) as CPPStructInstance
                            cppstruct = struct
                            struct.storage
                        }
                        else
                            toNativeType(tc, value as SixModelObject?, type, call.argInfo!![i])
                    }
                    org.raku.nqp.dispatch.ArgKind.INT,
                    org.raku.nqp.dispatch.ArgKind.UINT -> intToNative(tc, value as Long, type)
                    org.raku.nqp.dispatch.ArgKind.NUM -> numToNative(tc, value as Double, type)
                    org.raku.nqp.dispatch.ArgKind.STR -> strToNative(tc, value as String?, type)
                }
                /* C wants to see a null pointer, not a Java null. */
                if (cArgs[i] == null && isPointerType(type))
                    cArgs[i] = MemorySegment.NULL
            }

            if (cppstruct != null) {
                /* Calling a C++ constructor: hand back the invocant (THIS). */
                ctorHandle(tc, call).invokeWithArguments(*cArgs)
                return org.raku.nqp.dispatch.DispatchValue(org.raku.nqp.dispatch.ArgKind.OBJ,
                    toNQPType(tc, call.retType, returns, cppstruct.storage))
            }

            val handle =
                if (isVariadic) {
                    @Suppress("UNCHECKED_CAST")
                    variadicHandle(tc, call, varargLayouts!! as Array<MemoryLayout>)
                }
                else call.handle!!
            val returned = handle.invokeWithArguments(*cArgs)

            /* Only an object argument can carry an 'is rw' reference or an
             * array for the callee to write into. */
            for (i in 0 until argTypes.size)
                if (org.raku.nqp.dispatch.ArgKind.ofFlag(descriptor.argFlags[i + 2]) ==
                        org.raku.nqp.dispatch.ArgKind.OBJ)
                    writeBackRW(tc, args[i + 2] as SixModelObject?,
                        cArgs[i] as? MemorySegment, argTypes[i])
            /* And propagate what the callee wrote through the pointers the
             * bitfield asked for. */
            for (i in argTypes.size until n)
                if ((rwBitfield shr i) and 1L == 1L)
                    rwVarargWriteBack(tc, args[i + 2] as SixModelObject?,
                        cArgs[i] as MemorySegment)

            return org.raku.nqp.dispatch.DispatchValue(org.raku.nqp.dispatch.ArgKind.OBJ,
                toNQPType(tc, call.retType, returns, returned))
        }
        catch (e: ControlException) {
            throw e
        }
        catch (t: Throwable) {
            throw ExceptionHandling.dieInternal(tc, t)
        }
    }

    /** A variadic pass-by-pointer argument: the value the reference holds,
     * in freshly allocated C storage for the callee to read and write. A
     * full 8-byte slot regardless of the referenced width: little-endian
     * reads of a narrower type see the right bytes, matching how the
     * value-side promotions widen everything to 8 bytes as well. */
    private fun rwVarargToNative(tc: ThreadContext, v: SixModelObject?): MemorySegment {
        val m = NativeSupport.allocate(8)
        if (Ops.iscont_i(v) != 0L || Ops.iscont_u(v) != 0L)
            m.set(ValueLayout.JAVA_LONG, 0, (v as NativeRefInstance).fetch_i(tc))
        else if (Ops.iscont_n(v) != 0L)
            m.set(ValueLayout.JAVA_DOUBLE, 0, (v as NativeRefInstance).fetch_n(tc))
        else
            throw ExceptionHandling.dieInternal(tc,
                "Can only pass a native reference by pointer in a variadic native call")
        return m
    }

    /** Writes what the callee left in a pass-by-pointer variadic argument's
     * storage back through the reference it came from. */
    private fun rwVarargWriteBack(tc: ThreadContext, v: SixModelObject?, m: MemorySegment) {
        if (Ops.iscont_i(v) != 0L || Ops.iscont_u(v) != 0L)
            (v as NativeRefInstance).store_i(tc, m.get(ValueLayout.JAVA_LONG, 0))
        else if (Ops.iscont_n(v) != 0L)
            (v as NativeRefInstance).store_n(tc, m.get(ValueLayout.JAVA_DOUBLE, 0))
    }

    /** An object variadic argument, marshalled by what it is rather than by
     * a declared parameter type (a variadic parameter has none): its C type
     * and the value to pass, promoted the way C's default argument
     * promotions would. */
    private fun varargToNative(tc: ThreadContext, v0: SixModelObject?): Pair<MemoryLayout, Any> {
        val v = Ops.decont(v0, tc)
        if (v == null || Ops.isconcrete(v, tc) == 0L)
            return Pair(ValueLayout.ADDRESS, MemorySegment.NULL)
        return when (v) {
            is CPointerInstance -> Pair(ValueLayout.ADDRESS, v.pointer ?: MemorySegment.NULL)
            is CArrayInstance -> Pair(ValueLayout.ADDRESS, v.storage!!)
            is CPPStructInstance -> Pair(ValueLayout.ADDRESS, v.storage!!)
            is CStructInstance -> Pair(ValueLayout.ADDRESS, v.storage!!)
            is CUnionInstance -> Pair(ValueLayout.ADDRESS, v.storage!!)
            is CStrInstance -> Pair(ValueLayout.ADDRESS, v.cstr ?: MemorySegment.NULL)
            else -> {
                val spec = v.st.REPR.get_storage_spec(tc, v.st)
                when {
                    spec.canBox.contains(Boxable.INT) ->
                        Pair(ValueLayout.JAVA_LONG, v.get_int(tc))
                    spec.canBox.contains(Boxable.NUM) ->
                        Pair(ValueLayout.JAVA_DOUBLE, v.get_num(tc))
                    spec.canBox.contains(Boxable.STR) ->
                        Pair(ValueLayout.ADDRESS, NativeSupport.toCString(v.get_str(tc)))
                    else -> throw ExceptionHandling.dieInternal(tc,
                        "Don't know how to pass a ${Ops.typeName(v, tc)} as a variadic native call argument")
                }
            }
        }
    }

    /** The downcall handle for one variadic shape of a call site. */
    private fun variadicHandle(tc: ThreadContext, call: NativeCallBody,
                               layouts: Array<MemoryLayout>): MethodHandle {
        var handles = call.variadicHandles
        if (handles == null) {
            handles = java.util.concurrent.ConcurrentHashMap()
            call.variadicHandles = handles
        }
        val key = layouts.joinToString("|") { it.toString() }
        return handles.getOrPut(key) {
            NativeSupport.LINKER.downcallHandle(call.entryPoint,
                descriptorFor(tc, call.retType!!, call.argTypes!!)
                    .appendArgumentLayouts(*layouts),
                Linker.Option.firstVariadicArg(call.argTypes!!.size))
        }
    }

    /** A native int dispatch value, converted for a C parameter type. */
    private fun intToNative(tc: ThreadContext, value: Long, target: ArgType?): Any? =
        when (target) {
            ArgType.CHAR, ArgType.UCHAR -> value.toByte()
            ArgType.SHORT, ArgType.USHORT -> value.toShort()
            ArgType.INT, ArgType.UINT -> value.toInt()
            ArgType.LONG, ArgType.ULONG -> cLong(value)
            ArgType.LONGLONG, ArgType.ULONGLONG -> value
            /* An Int-typed argument for a pointer parameter carries the
             * address itself; the marshalling dispatcher unboxes CPointers
             * to native ints the same way. */
            ArgType.CPOINTER ->
                if (value == 0L) MemorySegment.NULL else NativeSupport.pointer(value)
            /* An undefined Str argument reaches us as a literal int 0: the
             * dispatch program cannot track a value that is not there. */
            ArgType.ASCIISTR, ArgType.UTF8STR, ArgType.UTF16STR ->
                if (value == 0L) MemorySegment.NULL
                else throw ExceptionHandling.dieInternal(tc,
                    "A non-zero native int argument cannot be passed for a $target native parameter")
            else -> throw ExceptionHandling.dieInternal(tc,
                "A native int argument cannot be passed for a $target native parameter")
        }

    /** A native num dispatch value, converted for a C parameter type. */
    private fun numToNative(tc: ThreadContext, value: Double, target: ArgType?): Any? =
        when (target) {
            ArgType.FLOAT -> value.toFloat()
            ArgType.DOUBLE -> value
            else -> throw ExceptionHandling.dieInternal(tc,
                "A native num argument cannot be passed for a $target native parameter")
        }

    /** A native str dispatch value, converted for a C parameter type. */
    private fun strToNative(tc: ThreadContext, value: String?, target: ArgType?): Any? =
        when (target) {
            /* TODO: Handle encodings, as toNativeType. */
            ArgType.ASCIISTR, ArgType.UTF8STR, ArgType.UTF16STR ->
                if (value == null) null else NativeSupport.toCString(value)
            else -> throw ExceptionHandling.dieInternal(tc,
                "A native str argument cannot be passed for a $target native parameter")
        }

    @JvmStatic
    fun refresh(obj: SixModelObject?, tc: ThreadContext): Long {
        val deconted = Ops.decont(obj, tc)
        if (deconted !is Refreshable) return 1L

        deconted.refresh(tc)

        return 1L
    }

    @JvmStatic
    fun nativecallglobal(libname: String?, symbol: String, targetSpec: SixModelObject, targetType: SixModelObject, tc: ThreadContext): SixModelObject? {
        try {
            /* Load the library and locate the symbol. */
            /* TODO: Error handling! */
            var entryPoint = NativeSupport.unbounded(
                NativeSupport.libraryLookup(libname).find(symbol).orElseThrow {
                    UnsatisfiedLinkError("Cannot find symbol '$symbol'"
                        + (if (libname.isNullOrEmpty()) " in the running process" else " in library '$libname'"))
                })

            val ss = targetSpec.st.REPR.get_storage_spec(tc, targetSpec.st)
            if (ss.boxedPrimitive == BoxedPrimitive.STR)
                entryPoint = NativeSupport.unbounded(entryPoint!!.get(ValueLayout.ADDRESS, 0))

            return castNativeCall(tc, targetSpec, targetType, entryPoint)
        }
        catch (t: Throwable) {
            throw ExceptionHandling.dieInternal(tc, t)
        }
    }

    @JvmStatic
    fun nativecallsizeof(obj: SixModelObject, tc: ThreadContext): Long {
        var o = Ops.decont(obj, tc)
        val ss = o!!.st.REPR.get_storage_spec(tc, o.st)

        when (ss.boxedPrimitive) {
            BoxedPrimitive.INT, BoxedPrimitive.UINT, BoxedPrimitive.NUM ->
                return (ss.bits / 8).toLong()
            BoxedPrimitive.STR ->
                return NativeSupport.POINTER_SIZE.toLong()
            else -> {
                if (Ops.isconcrete(o, tc) == 0L)
                    o = o!!.st.REPR.allocate(tc, o.st)
                if (o is CStrInstance
                 || o is CPointerInstance
                 || o is CArrayInstance) {
                    return NativeSupport.POINTER_SIZE.toLong()
                }
                else if (o is CTypeInstance) {
                    return (o.st.REPRData as CTypeREPRData).size
                }
                else {
                    throw ExceptionHandling.dieInternal(tc,
                        String.format("NativeCall op sizeof expected type with CPointer, CStruct, CUnion, CArray, P6int or P6num representation, but got a %s", o))
                }
            }
        }
    }

    @JvmStatic
    fun nativecallcast(targetSpec: SixModelObject, targetType: SixModelObject, source: SixModelObject, tc: ThreadContext): SixModelObject? {
        var o: MemorySegment? = null

        if (source is CPointerInstance) {
            o = source.pointer
        }
        else if (source is CArrayInstance) {
            /* NOTE: the Java original casts a CArrayInstance to
             * CStructInstance here, which throws ClassCastException;
             * faithfully preserved. */
            o = (source as CStructInstance).storage
        }
        else if (source is CTypeInstance) {
            o = source.storage
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

        return castNativeCall(tc, targetSpec, targetType, o)
    }

    @JvmStatic
    fun castNativeCall(tc: ThreadContext, targetSpec: SixModelObject, targetType: SixModelObject, from: MemorySegment?): SixModelObject? {
        val o = NativeSupport.unbounded(from)
        if (o == null)
            return targetType

        val nqpobj = targetType.st.REPR.allocate(tc, targetType.st)
        val ss = targetSpec.st.REPR.get_storage_spec(tc, targetSpec.st)

        when (ss.boxedPrimitive) {
            BoxedPrimitive.INT, BoxedPrimitive.UINT ->
                when (ss.bits.toInt()) {
                    8 -> nqpobj.set_int(tc, o.get(ValueLayout.JAVA_BYTE, 0).toLong())
                    16 -> nqpobj.set_int(tc, o.get(ValueLayout.JAVA_SHORT, 0).toLong())
                    32 -> nqpobj.set_int(tc, o.get(ValueLayout.JAVA_INT, 0).toLong())
                    64 -> nqpobj.set_int(tc, o.get(ValueLayout.JAVA_LONG, 0))
                    else ->
                        throw ExceptionHandling.dieInternal(tc,
                            String.format("Cannot cast to %d bits integer", ss.bits))
                }
            BoxedPrimitive.NUM ->
                when (ss.bits.toInt()) {
                    32 -> nqpobj.set_num(tc, o.get(ValueLayout.JAVA_FLOAT, 0).toDouble())
                    64 -> nqpobj.set_num(tc, o.get(ValueLayout.JAVA_DOUBLE, 0))
                    else ->
                        throw ExceptionHandling.dieInternal(tc,
                            String.format("Cannot cast to %d bits number", ss.bits))
                }
            BoxedPrimitive.STR ->
                /* TODO: Handle encodings. */
                nqpobj.set_str(tc, o.getString(0))
            else -> {
                if (targetType is CStrInstance) {
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
                else if (nqpobj is CTypeInstance) {
                    nqpobj.storage = sizedAs(targetType, o)
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
            var boxed = target.get_boxing_of(tc, ncrepr.ID.toLong()) as NativeCallBody?
            if (boxed == null) {
                boxed = NativeCallBody()
                target.set_boxing_of(tc, ncrepr.ID.toLong(), boxed)
            }
            call = boxed
        }
        return call
    }

    /* Everything that crosses the boundary as a pointer. Structs and unions
     * are among them: like JNA, we pass and return aggregates by reference,
     * never by value. */
    private fun isPointerType(target: ArgType?): Boolean = when (target) {
        ArgType.ASCIISTR, ArgType.UTF8STR, ArgType.UTF16STR,
        ArgType.CPOINTER, ArgType.CARRAY, ArgType.CSTRUCT, ArgType.CPPSTRUCT,
        ArgType.CUNION, ArgType.CALLBACK, ArgType.VMARRAY,
        ArgType.CHAR_RW, ArgType.UCHAR_RW, ArgType.SHORT_RW, ArgType.USHORT_RW,
        ArgType.INT_RW, ArgType.UINT_RW, ArgType.LONG_RW, ArgType.ULONG_RW,
        ArgType.LONGLONG_RW, ArgType.ULONGLONG_RW, ArgType.FLOAT_RW,
        ArgType.DOUBLE_RW, ArgType.CPOINTER_RW -> true
        else -> false
    }

    private fun layoutFor(tc: ThreadContext, target: ArgType): MemoryLayout = when (target) {
        ArgType.CHAR, ArgType.UCHAR -> ValueLayout.JAVA_BYTE
        ArgType.SHORT, ArgType.USHORT -> ValueLayout.JAVA_SHORT
        ArgType.INT, ArgType.UINT -> ValueLayout.JAVA_INT
        ArgType.LONG, ArgType.ULONG -> NativeSupport.C_LONG
        ArgType.LONGLONG, ArgType.ULONGLONG -> ValueLayout.JAVA_LONG
        ArgType.FLOAT -> ValueLayout.JAVA_FLOAT
        ArgType.DOUBLE -> ValueLayout.JAVA_DOUBLE
        else ->
            if (isPointerType(target)) ValueLayout.ADDRESS
            else throw ExceptionHandling.dieInternal(tc, String.format("Don't know the C type of %s arguments yet", target))
    }

    private fun descriptorFor(tc: ThreadContext, ret: ArgType, args: Array<ArgType>): FunctionDescriptor {
        val argLayouts = Array<MemoryLayout>(args.size) { layoutFor(tc, args[it]) }
        return if (ret == ArgType.VOID) FunctionDescriptor.ofVoid(*argLayouts)
               else FunctionDescriptor.of(layoutFor(tc, ret), *argLayouts)
    }

    private fun ctorHandle(tc: ThreadContext, call: NativeCallBody): MethodHandle {
        var handle = call.ctorHandle
        if (handle == null) {
            handle = NativeSupport.LINKER.downcallHandle(call.entryPoint,
                descriptorFor(tc, ArgType.VOID, call.argTypes!!))
            call.ctorHandle = handle
        }
        return handle
    }

    /* A C long is a Java long on LP64 and a Java int on Windows, so it comes
     * back from a call and sits in memory as whichever of the two it is. */
    private fun readCLong(seg: MemorySegment, offset: Long): Long =
        if (NativeSupport.C_LONG_IS_INT) seg.get(ValueLayout.JAVA_INT, offset).toLong()
        else seg.get(ValueLayout.JAVA_LONG, offset)

    private fun cLong(value: Long): Any =
        if (NativeSupport.C_LONG_IS_INT) value.toInt() else value

    /* Give a pointer the extent of the aggregate it addresses, so the members
     * of the value it points at are reachable. */
    private fun sizedAs(type: SixModelObject?, seg: MemorySegment): MemorySegment {
        val data = type?.st?.REPRData as? CTypeREPRData
        return if (data != null && data.size > 0 && seg.byteSize() != data.size) seg.reinterpret(data.size) else seg
    }

    @JvmStatic
    fun toNativeType(tc: ThreadContext, o: SixModelObject?, target: ArgType?, info: SixModelObject?): Any? {
        var v = o
        when (target!!) {
        ArgType.CHAR -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v!!.get_int(tc).toByte()
        }
        ArgType.SHORT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v!!.get_int(tc).toShort()
        }
        ArgType.INT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v!!.get_int(tc).toInt()
        }
        ArgType.LONG -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return cLong(v!!.get_int(tc))
        }
        ArgType.LONGLONG -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v!!.get_int(tc)
        }
        ArgType.UCHAR -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v!!.get_int(tc).toByte()
        }
        ArgType.USHORT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v!!.get_int(tc).toShort()
        }
        ArgType.UINT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v!!.get_int(tc).toInt()
        }
        ArgType.ULONG -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return cLong(v!!.get_int(tc))
        }
        ArgType.ULONGLONG -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v!!.get_int(tc)
        }
        ArgType.FLOAT -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v!!.get_num(tc).toFloat()
        }
        ArgType.DOUBLE -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return v!!.get_num(tc)
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
                return NativeSupport.toCString(v!!.get_str(tc))
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
            return callbackStubFor(v!!, info!!, tc)
        }
        ArgType.VMARRAY -> {
            v = Ops.decont(v, tc)
            if (Ops.isconcrete(v, tc) == 0L) return null
            return vmarrayToNative(tc, v!!)
        }
        ArgType.CHAR_RW, ArgType.UCHAR_RW -> {
            if (Ops.iscont_i(v) == 0L && Ops.iscont_u(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native integer, but got %s", v))
            val m = NativeSupport.allocate(1)
            m.set(ValueLayout.JAVA_BYTE, 0, (v as NativeRefInstance).fetch_i(tc).toByte())
            return m
        }
        ArgType.SHORT_RW, ArgType.USHORT_RW -> {
            if (Ops.iscont_i(v) == 0L && Ops.iscont_u(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native integer, but got %s", v))
            val m = NativeSupport.allocate(2)
            m.set(ValueLayout.JAVA_SHORT, 0, (v as NativeRefInstance).fetch_i(tc).toShort())
            return m
        }
        ArgType.INT_RW, ArgType.UINT_RW -> {
            if (Ops.iscont_i(v) == 0L && Ops.iscont_u(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native integer, but got %s", v))
            val m = NativeSupport.allocate(4)
            m.set(ValueLayout.JAVA_INT, 0, (v as NativeRefInstance).fetch_i(tc).toInt())
            return m
        }
        ArgType.LONG_RW, ArgType.ULONG_RW -> {
            if (Ops.iscont_i(v) == 0L && Ops.iscont_u(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native integer, but got %s", v))
            val m = NativeSupport.allocate(NativeSupport.C_LONG_SIZE.toLong())
            val value = (v as NativeRefInstance).fetch_i(tc)
            if (NativeSupport.C_LONG_IS_INT) m.set(ValueLayout.JAVA_INT, 0, value.toInt())
            else m.set(ValueLayout.JAVA_LONG, 0, value)
            return m
        }
        ArgType.LONGLONG_RW, ArgType.ULONGLONG_RW -> {
            if (Ops.iscont_i(v) == 0L && Ops.iscont_u(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native integer, but got %s", v))
            val m = NativeSupport.allocate(8)
            m.set(ValueLayout.JAVA_LONG, 0, (v as NativeRefInstance).fetch_i(tc))
            return m
        }
        ArgType.FLOAT_RW -> {
            if (Ops.iscont_n(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native number, but got %s", v))
            val m = NativeSupport.allocate(4)
            m.set(ValueLayout.JAVA_FLOAT, 0, (v as NativeRefInstance).fetch_n(tc).toFloat())
            return m
        }
        ArgType.DOUBLE_RW -> {
            if (Ops.iscont_n(v) == 0L)
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Native call expected argument that references a native number, but got %s", v))
            val m = NativeSupport.allocate(8)
            m.set(ValueLayout.JAVA_DOUBLE, 0, (v as NativeRefInstance).fetch_n(tc))
            return m
        }
        ArgType.CPOINTER_RW -> {
            val m = NativeSupport.allocate(NativeSupport.POINTER_SIZE.toLong())
            v = Ops.decont(v, tc)
            val ptr = (v as CPointerInstance).get_int(tc)
            m.set(ValueLayout.ADDRESS, 0, NativeSupport.orNull(if (ptr > 0) NativeSupport.pointer(ptr) else null))
            return m
        }
        else ->
            throw ExceptionHandling.dieInternal(tc, String.format("Don't know how to convert %s arguments to C yet", target))
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
            val value = (o as Number).toLong()
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
            val value = (o as Number).toLong()
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
        ArgType.ASCIISTR, ArgType.UTF8STR, ArgType.UTF16STR -> {
            /* TODO: Handle encodings. */
            val value = if (o is MemorySegment) NativeSupport.fromCString(o) else o as String?
            if (value != null) {
                nqpobj = type!!.st.REPR.allocate(tc, type.st)
                nqpobj.set_str(tc, value)
            }
        }
        ArgType.CPOINTER -> {
            val ptr = NativeSupport.unbounded(o as MemorySegment?)
            if (ptr != null) {
                nqpobj = type!!.st.REPR.allocate(tc, type.st)
                val cpointer = nqpobj as CPointerInstance
                cpointer.pointer = ptr
            }
        }
        ArgType.CARRAY -> {
            val ptr = NativeSupport.unbounded(o as MemorySegment?)
            if (ptr != null) {
                nqpobj = type!!.st.REPR.allocate(tc, type.st)
                val carray = nqpobj as CArrayInstance
                carray.storage = ptr
                carray.managed = false
            }
        }
        ArgType.CSTRUCT, ArgType.CPPSTRUCT, ArgType.CUNION -> {
            val ptr = o as MemorySegment?
            if (ptr != null && ptr.address() != 0L) {
                nqpobj = type!!.st.REPR.allocate(tc, type.st)
                (nqpobj as CTypeInstance).storage = sizedAs(type, ptr)
            }
        }
        else ->
            throw ExceptionHandling.dieInternal(tc, String.format("Don't know how to convert %s arguments to NQP yet", target))
        }

        return nqpobj
    }

    private fun getArgType(tc: ThreadContext, info: SixModelObject, isReturn: Boolean): ArgType {
        var typeName = info.at_key_boxed(tc, "type")!!.get_str(tc)!!

        val rw = info.at_key_boxed(tc, "rw")
        if (rw != null && rw.get_int(tc) == 1L)
            typeName += "_RW"

        var type = ArgType.VOID
        try {
            type = java.lang.Enum.valueOf(ArgType::class.java, typeName.uppercase())
        }
        catch (e: IllegalArgumentException) {
            throw ExceptionHandling.dieInternal(tc, String.format("Unknown type '%s' used for native call", typeName))
        }

        if (!isReturn && type == ArgType.VOID) {
            throw ExceptionHandling.dieInternal(tc, "Can only use 'void' type on native call return values")
        }

        return type
    }

    /**
     * An nqp native array travels as a pointer to a copy of its slots, which
     * we hand back over the slots once the call returns.
     *
     * NOTE: the whole slot buffer goes out, spare capacity and all, and the
     * array's start offset is ignored -- which is what the callee saw when
     * JNA marshalled the Java array for us, so it stays that way.
     */
    private fun vmarrayToNative(tc: ThreadContext, v: SixModelObject): MemorySegment {
        val arena = Arena.ofAuto()
        when (v) {
            is VMArrayInstance_i8, is VMArrayInstance_u8 -> {
                val slots = (if (v is VMArrayInstance_i8) v.slots else (v as VMArrayInstance_u8).slots)
                    ?: return MemorySegment.NULL
                return arena.allocateFrom(ValueLayout.JAVA_BYTE, *slots)
            }
            is VMArrayInstance_i16, is VMArrayInstance_u16 -> {
                val slots = (if (v is VMArrayInstance_i16) v.slots else (v as VMArrayInstance_u16).slots)
                    ?: return MemorySegment.NULL
                return arena.allocateFrom(ValueLayout.JAVA_SHORT, *slots)
            }
            is VMArrayInstance_i32, is VMArrayInstance_u32 -> {
                val slots = (if (v is VMArrayInstance_i32) v.slots else (v as VMArrayInstance_u32).slots)
                    ?: return MemorySegment.NULL
                return arena.allocateFrom(ValueLayout.JAVA_INT, *slots)
            }
            is VMArrayInstance_i ->
                return arena.allocateFrom(ValueLayout.JAVA_LONG, *(v.slots ?: return MemorySegment.NULL))
            is VMArrayInstance_n ->
                return arena.allocateFrom(ValueLayout.JAVA_DOUBLE, *(v.slots ?: return MemorySegment.NULL))
            is VMArrayInstance_s -> {
                val slots = v.slots ?: return MemorySegment.NULL
                val seg = arena.allocate(ValueLayout.ADDRESS, slots.size.toLong())
                for (i in slots.indices) {
                    val s = slots[i]
                    seg.setAtIndex(ValueLayout.ADDRESS, i.toLong(),
                        if (s == null) MemorySegment.NULL else arena.allocateFrom(s))
                }
                return seg
            }
            else ->
                throw ExceptionHandling.dieInternal(tc,
                    String.format("Don't know how to pass a %s as a native array", v))
        }
    }

    private fun vmarrayFromNative(tc: ThreadContext, v: SixModelObject?, seg: MemorySegment?) {
        if (seg == null || seg.address() == 0L) return
        when (v) {
            is VMArrayInstance_i8 -> MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0L, v.slots!!, 0, v.slots!!.size)
            is VMArrayInstance_u8 -> MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0L, v.slots!!, 0, v.slots!!.size)
            is VMArrayInstance_i16 -> MemorySegment.copy(seg, ValueLayout.JAVA_SHORT, 0L, v.slots!!, 0, v.slots!!.size)
            is VMArrayInstance_u16 -> MemorySegment.copy(seg, ValueLayout.JAVA_SHORT, 0L, v.slots!!, 0, v.slots!!.size)
            is VMArrayInstance_i32 -> MemorySegment.copy(seg, ValueLayout.JAVA_INT, 0L, v.slots!!, 0, v.slots!!.size)
            is VMArrayInstance_u32 -> MemorySegment.copy(seg, ValueLayout.JAVA_INT, 0L, v.slots!!, 0, v.slots!!.size)
            is VMArrayInstance_i -> MemorySegment.copy(seg, ValueLayout.JAVA_LONG, 0L, v.slots!!, 0, v.slots!!.size)
            is VMArrayInstance_n -> MemorySegment.copy(seg, ValueLayout.JAVA_DOUBLE, 0L, v.slots!!, 0, v.slots!!.size)
            /* Strings went out as a copy; JNA didn't read them back either. */
            else -> {}
        }
    }

    private val callbackStubs = HashMap<SixModelObject, MemorySegment>()
    private val CALL_FUNCTION: MethodHandle = MethodHandles.lookup().findVirtual(
        CallbackHandler::class.java, "callFunction",
        MethodType.methodType(Any::class.java, Array<Any?>::class.java))

    /**
     * An upcall stub the foreign side can call, which routes into the NQP
     * function it was built for. The linker binds a MethodHandle, so one
     * handle onto CallbackHandler.callFunction, adapted to the signature at
     * hand, serves every callback.
     */
    private fun callbackStubFor(function: SixModelObject, infos: SixModelObject, tc: ThreadContext): MemorySegment {
        val existing = callbackStubs.get(function)
        if (existing != null) return existing

        /* The first element of the list is the return type and any following
         * items are the argument types. We collect the data needed when it's
         * time to call back into NQP: type objects for argument and return
         * types, and the ArgTypes for all of them. */
        val numInfo = infos.elems(tc).toInt()
        val argumentTypes = arrayOfNulls<SixModelObject>(numInfo - 1)
        val argumentInfo = arrayOfNulls<ArgType>(numInfo - 1)
        for (i in 1 until numInfo) {
            val info = infos.at_pos_boxed(tc, i.toLong())!!
            argumentTypes[i - 1] = info.at_key_boxed(tc, "typeobj")
            argumentInfo[i - 1] = getArgType(tc, info, false)
        }

        val info = infos.at_pos_boxed(tc, 0L)!!
        val returnInfo = getArgType(tc, info, true)

        @Suppress("UNCHECKED_CAST")
        val handler = CallbackHandler(tc.gc, function, returnInfo, argumentTypes, argumentInfo as Array<ArgType>)
        val descriptor = descriptorFor(tc, returnInfo, argumentInfo as Array<ArgType>)
        val target = CALL_FUNCTION.bindTo(handler)
            .asCollector(Array<Any?>::class.java, numInfo - 1)
            .asType(descriptor.toMethodType())

        val stub = NativeSupport.LINKER.upcallStub(target, descriptor, Arena.ofAuto())
        callbackStubs.put(function, stub)
        return stub
    }

    class CallbackHandler(
        @JvmField var gc: GlobalContext,
        @JvmField var function: SixModelObject,
        @JvmField var returnInfo: ArgType,
        @JvmField var argumentTypes: Array<SixModelObject?>,
        @JvmField var argumentInfo: Array<ArgType>,
    ) {
        @JvmField var callsite: CallSiteDescriptor

        init {
            val desc = ByteArray(argumentTypes.size)
            Arrays.fill(desc, CallSiteDescriptor.ARG_OBJ)
            this.callsite = CallSiteDescriptor(desc, null)
        }

        fun callFunction(args: Array<Any?>): Any? {
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
                return toNativeType(tc, Ops.decont(Ops.result_o(tc.resultFrame()), tc), returnInfo, null)
            }
        }
    }
}
