package org.raku.nqp.runtime

import java.lang.invoke.CallSite
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodHandles.Lookup
import java.lang.invoke.MethodType
import java.lang.invoke.MutableCallSite

import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SixModelObject

object IndyBootstrap {
    @JvmStatic
    fun wval_noa(caller: Lookup, name: String, type: MethodType): CallSite {
        try {
            /* Look up wval resolver method. */
            val resType = MethodType.methodType(SixModelObject::class.java,
                    MutableCallSite::class.java, String::class.java, Integer.TYPE, ThreadContext::class.java)
            val res = caller.findStatic(IndyBootstrap::class.java, "wvalResolve_noa", resType)

            /* Create a mutable callsite, and curry the resolver with it. */
            val cs = MutableCallSite(type)
            cs.setTarget(MethodHandles.insertArguments(res, 0, cs))

            /* Produce callsite; it'll be updated with the resolved WVal upon the
             * first invocation. */
            return cs
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    fun wvalResolve_noa(cs: MutableCallSite, sc: String, idx: Int, tc: ThreadContext): SixModelObject? {
        /* Look up the WVal. */
        val res = tc.gc.scs.get(sc)!!.getObject(idx)

        /* Update this callsite, so that we never run the lookup again and instead
         * just always use the resolved object. Discards incoming arguments, as
         * they are no longer needed. */
        if (!tc.curFrame!!.codeRef.staticInfo.compUnit.shared)
            cs.setTarget(MethodHandles.dropArguments(
                        MethodHandles.constant(SixModelObject::class.java, res),
                        0, String::class.java, Integer.TYPE, ThreadContext::class.java))

        /* Hand back the resulting object, for this first call. */
        return res
    }

    @JvmStatic
    fun subcall_noa(caller: Lookup, s: String, type: MethodType): CallSite {
        try {
            /* Look up subcall resolver method. */
            val resType = MethodType.methodType(Void.TYPE,
                    Lookup::class.java, MutableCallSite::class.java, String::class.java,
                    Integer.TYPE, ThreadContext::class.java, Array<Any>::class.java)
            val res = caller.findStatic(IndyBootstrap::class.java, "subcallResolve_noa", resType)

            /* Create a mutable callsite, and curry the resolver with it and
             * the sub name. */
            val cs = MutableCallSite(type)
            cs.setTarget(MethodHandles
                .insertArguments(res, 0, caller, cs)
                .asCollector(Array<Any>::class.java, type.parameterCount() - 3)
                .asType(type))

            /* Produce callsite; it'll be updated with the resolved call upon the
             * first invocation. */
            return cs
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    fun subcallResolve_noa(caller: Lookup, cs: MutableCallSite, name: String, csIdx: Int, tc: ThreadContext, vararg argsIn: Any?) {
        var args: Array<out Any?> = argsIn
        /* Locate the thing to call. */
        var invokee = Ops.getlex(name, tc)
        if (Ops.isnull(invokee) == 1L)
            throw ExceptionHandling.dieInternal(tc, "Can not invoke object '$name'")

        /* Don't update callsite in cases where it's not safe. */
        var shared = tc.curFrame!!.codeRef.staticInfo.compUnit.shared
        if (invokee!!.stInitialized && invokee!!.st.ContainerSpec != null) {
            invokee = Ops.decont(invokee, tc)
            shared = true
        }

        /* Resolve callsite descriptor. */
        var csd = if (csIdx >= 0)
            tc.curFrame!!.codeRef.staticInfo.compUnit.callSites!![csIdx]
        else
            Ops.emptyCallSite
        val csdOrig = csd

        /* Otherwise, get the code ref. */
        val cr: CodeRef
        if (invokee is CodeRef) {
            cr = invokee
        }
        else {
            val ispec = invokee!!.st.InvocationSpec
                ?: throw ExceptionHandling.dieInternal(tc, "Can not invoke this object")
            if (Ops.isnull(ispec.ClassHandle) == 0L)
                cr = invokee!!.get_attribute_boxed(tc, ispec.ClassHandle, ispec.AttrName, ispec.Hint) as CodeRef
            else {
                cr = ispec.InvocationHandler as CodeRef
                @Suppress("UNCHECKED_CAST")
                csd = csd.injectInvokee(tc, args as Array<Any?>, invokee)
                args = tc.flatArgs!!
            }
        }

        /* Now need to adapt to the target callsite by binding the CodeRef
         * and callsite with what they've been resolved to. Don't do it if
         * it's a compiler stub, though. */
        if (!cr.isCompilerStub && !shared) {
            try {
                val invType = MethodType.methodType(Void.TYPE,
                    MethodHandle::class.java, String::class.java, CallSiteDescriptor::class.java,
                    ThreadContext::class.java, Array<Any>::class.java)
                val inv = caller.findStatic(IndyBootstrap::class.java, "subInvoker", invType)
                cs.setTarget(MethodHandles
                    .dropArguments(
                        MethodHandles.insertArguments(inv, 0, cr.staticInfo.mh, name, csdOrig),
                        0, String::class.java, Integer.TYPE)
                    .asVarargsCollector(Array<Any>::class.java)
                    .asType(cs.getTarget().type()))
            }
            catch (t: Throwable) {
                throw ExceptionHandling.dieInternal(tc, t)
            }
        }

        /* Make the sub call directly for this initial call. */
        try {
            ArgsExpectation.invokeByExpectation(tc, cr, csd, args)
        }
        catch (e: ControlException) {
            throw e
        }
        catch (e: Throwable) {
            ExceptionHandling.dieInternal(tc, e)
        }
    }

    @JvmStatic
    @Throws(Throwable::class)
    fun subInvoker(mh: MethodHandle, name: String, csdIn: CallSiteDescriptor, tc: ThreadContext, argsIn: Array<Any?>) {
        var csd = csdIn
        var args: Array<out Any?> = argsIn
        val invokee = Ops.getlex(name, tc)
        if (Ops.isnull(invokee) == 1L)
            throw ExceptionHandling.dieInternal(tc, "Can not invoke object '$name'")

        val cr: CodeRef
        if (invokee is CodeRef) {
            cr = invokee
        }
        else {
            val ispec = invokee!!.st.InvocationSpec
                ?: throw ExceptionHandling.dieInternal(tc, "Can not invoke this object")
            if (Ops.isnull(ispec.ClassHandle) == 0L)
                cr = invokee!!.get_attribute_boxed(tc, ispec.ClassHandle, ispec.AttrName, ispec.Hint) as CodeRef
            else {
                cr = ispec.InvocationHandler as CodeRef
                @Suppress("UNCHECKED_CAST")
                csd = csd.injectInvokee(tc, args as Array<Any?>, invokee)
                args = tc.flatArgs!!
            }
        }
        ArgsExpectation.invokeByExpectation(tc, cr, csd, args)
    }

    @JvmStatic
    fun subcallstatic_noa(caller: Lookup, s: String, type: MethodType): CallSite {
        try {
            /* Look up subcall resolver method. */
            val resType = MethodType.methodType(Void.TYPE,
                    Lookup::class.java, MutableCallSite::class.java, String::class.java,
                    Integer.TYPE, ThreadContext::class.java, Array<Any>::class.java)
            val res = caller.findStatic(IndyBootstrap::class.java, "subcallstaticResolve_noa", resType)

            /* Create a mutable callsite, and curry the resolver with it and
             * the sub name. */
            val cs = MutableCallSite(type)
            cs.setTarget(MethodHandles
                .insertArguments(res, 0, caller, cs)
                .asCollector(Array<Any>::class.java, type.parameterCount() - 3)
                .asType(type))

            /* Produce callsite; it'll be updated with the resolved call upon the
             * first invocation. */
            return cs
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    fun subcallstaticResolve_noa(caller: Lookup, cs: MutableCallSite, name: String, csIdx: Int, tc: ThreadContext, vararg argsIn: Any?) {
        var args: Array<out Any?> = argsIn
        /* Locate the thing to call. */
        var invokee = Ops.getlex(name, tc)
        if (Ops.isnull(invokee) == 1L)
            throw ExceptionHandling.dieInternal(tc, "Can not invoke object '$name'")

        /* Don't update callsite in cases where it's not safe. */
        var shared = tc.curFrame!!.codeRef.staticInfo.compUnit.shared
        if (invokee!!.stInitialized && invokee!!.st.ContainerSpec != null) {
            invokee = Ops.decont(invokee, tc)
            shared = true
        }

        /* Resolve callsite descriptor. */
        var csd = if (csIdx >= 0)
            tc.curFrame!!.codeRef.staticInfo.compUnit.callSites!![csIdx]
        else
            Ops.emptyCallSite
        val csdOrig = csd

        /* Otherwise, get the code ref. */
        val cr: CodeRef
        if (invokee is CodeRef) {
            cr = invokee
        }
        else {
            val ispec = invokee!!.st.InvocationSpec
                ?: throw ExceptionHandling.dieInternal(tc, "Can not invoke this object")
            if (Ops.isnull(ispec.ClassHandle) == 0L)
                cr = invokee!!.get_attribute_boxed(tc, ispec.ClassHandle, ispec.AttrName, ispec.Hint) as CodeRef
            else {
                cr = ispec.InvocationHandler as CodeRef
                @Suppress("UNCHECKED_CAST")
                csd = csd.injectInvokee(tc, args as Array<Any?>, invokee)
                args = tc.flatArgs!!
                shared = true
            }
        }

        /* Now need to adapt to the target callsite by binding the CodeRef
         * and callsite with what they've been resolved to. Don't do it if
         * it's a compiler stub, though. */
        if (!cr.isCompilerStub && !shared) {
            try {
                var updated = false
                when (cr.staticInfo.argsExpectation) {
                    ArgsExpectation.NO_ARGS ->
                        if (csd.argFlags.size == 0) {
                            cs.setTarget(MethodHandles
                                .dropArguments(
                                    MethodHandles.insertArguments(cr.staticInfo.mh, 1, cr, csdOrig),
                                    0, String::class.java, Integer.TYPE)
                                .asType(cs.getTarget().type()))
                            updated = true
                        }
                    ArgsExpectation.OBJ ->
                        if (csd.argFlags.size == 1 && csd.argFlags[0] == CallSiteDescriptor.ARG_OBJ) {
                            cs.setTarget(MethodHandles
                                .dropArguments(
                                    MethodHandles.insertArguments(cr.staticInfo.mh, 1, cr, csdOrig),
                                    0, String::class.java, Integer.TYPE)
                                .asType(cs.getTarget().type()))
                            updated = true
                        }
                    ArgsExpectation.OBJ_OBJ ->
                        if (csd.argFlags.size == 2 && csd.argFlags[0] == CallSiteDescriptor.ARG_OBJ &&
                                csd.argFlags[1] == CallSiteDescriptor.ARG_OBJ) {
                            cs.setTarget(MethodHandles
                                .dropArguments(
                                    MethodHandles.insertArguments(cr.staticInfo.mh, 1, cr, csdOrig),
                                    0, String::class.java, Integer.TYPE)
                                .asType(cs.getTarget().type()))
                            updated = true
                        }
                }
                if (!updated) {
                    val invType = MethodType.methodType(Void.TYPE,
                        CallSiteDescriptor::class.java, CodeRef::class.java, ThreadContext::class.java,
                        Array<Any>::class.java)
                    val inv = caller.findStatic(IndyBootstrap::class.java, "substaticInvoker", invType)
                    cs.setTarget(MethodHandles
                        .dropArguments(
                            MethodHandles.insertArguments(inv, 0, csdOrig, cr),
                            0, String::class.java, Integer.TYPE)
                        .asVarargsCollector(Array<Any>::class.java)
                        .asType(cs.getTarget().type()))
                }
            }
            catch (t: Throwable) {
                throw ExceptionHandling.dieInternal(tc, t)
            }
        }

        /* Make the sub call directly for this initial call. */
        try {
            ArgsExpectation.invokeByExpectation(tc, cr, csd, args)
        }
        catch (e: ControlException) {
            throw e
        }
        catch (e: Throwable) {
            ExceptionHandling.dieInternal(tc, e)
        }
    }

    @JvmStatic
    @Throws(Throwable::class)
    fun substaticInvoker(csd: CallSiteDescriptor, cr: CodeRef, tc: ThreadContext, args: Array<Any?>) {
        ArgsExpectation.invokeByExpectation(tc, cr, csd, args)
    }

    @JvmStatic
    fun indcall_noa(caller: Lookup, s: String, type: MethodType): CallSite {
        try {
            /* Look up indirect call invoker method. */
            val resType = MethodType.methodType(Void.TYPE,
                    MutableCallSite::class.java, Integer.TYPE, ThreadContext::class.java,
                    SixModelObject::class.java, Array<Any>::class.java)
            val res = caller.findStatic(IndyBootstrap::class.java, "indcallInvoker_noa", resType)

            /* Create a mutable callsite, and curry the resolver with it and
             * the sub name. */
            val cs = MutableCallSite(type)
            cs.setTarget(MethodHandles
                .insertArguments(res, 0, cs)
                .asCollector(Array<Any>::class.java, type.parameterCount() - 3)
                .asType(type))

            /* Produce callsite. */
            return cs
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    fun indcallInvoker_noa(cs: MutableCallSite, csIdx: Int,
            tc: ThreadContext, invokeeIn: SixModelObject?, vararg argsIn: Any?) {
        var args: Array<out Any?> = argsIn
        /* Resolve callsite descriptor. */
        var csd = if (csIdx >= 0)
            tc.curFrame!!.codeRef.staticInfo.compUnit.callSites!![csIdx]
        else
            Ops.emptyCallSite

        /* Get the code ref. */
        val cr: CodeRef
        val invokee = Ops.decont(invokeeIn, tc)
        if (invokee is CodeRef) {
            cr = invokee
        }
        else {
            val ispec = invokee!!.st.InvocationSpec
                ?: throw ExceptionHandling.dieInternal(tc, "Can not invoke this object")
            if (Ops.isnull(ispec.ClassHandle) == 0L)
                cr = invokee!!.get_attribute_boxed(tc, ispec.ClassHandle, ispec.AttrName, ispec.Hint) as CodeRef
            else {
                cr = ispec.InvocationHandler as CodeRef
                @Suppress("UNCHECKED_CAST")
                csd = csd.injectInvokee(tc, args as Array<Any?>, invokee)
                args = tc.flatArgs!!
            }
        }

        /* Make the call. */
        try {
            ArgsExpectation.invokeByExpectation(tc, cr, csd, args)
        }
        catch (e: ControlException) {
            throw e
        }
        catch (e: Throwable) {
            ExceptionHandling.dieInternal(tc, e)
        }
    }

    @JvmStatic
    fun methcall_noa(caller: Lookup, s: String, type: MethodType): CallSite {
        try {
            /* Look up methcall resolver method. */
            val resType = MethodType.methodType(Void.TYPE,
                    Lookup::class.java, MutableCallSite::class.java, String::class.java,
                    Integer.TYPE, ThreadContext::class.java, Array<Any>::class.java)
            val res = caller.findStatic(IndyBootstrap::class.java, "methcallResolve_noa", resType)

            /* Create a mutable callsite, and curry the resolver with it and
             * the method name. */
            val cs = MutableCallSite(type)
            cs.setTarget(MethodHandles
                .insertArguments(res, 0, caller, cs)
                .asCollector(Array<Any>::class.java, type.parameterCount() - 3)
                .asType(type))

            /* Produce callsite; it'll build up a PIC over various polymorphic
             * invocations. */
            return cs
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    fun methcallResolve_noa(caller: Lookup, cs: MutableCallSite, name: String, csIdx: Int,
            tc: ThreadContext, vararg argsIn: Any?) {
        var args: Array<out Any?> = argsIn
        /* Resolve callsite descriptor. */
        var csd = if (csIdx >= 0)
            tc.curFrame!!.codeRef.staticInfo.compUnit.callSites!![csIdx]
        else
            Ops.emptyCallSite

        /* Don't update callsite in cases where it's not safe. */
        var shared = tc.curFrame!!.codeRef.staticInfo.compUnit.shared

        /* Try to resolve method to a coderef. */
        val invocant = args[0] as SixModelObject
        val invokee = Ops.findmethod(invocant, name, tc)
        val cr: CodeRef
        if (invokee is CodeRef) {
            cr = invokee
        }
        else {
            val ispec = invokee!!.st.InvocationSpec
                ?: throw ExceptionHandling.dieInternal(tc, "Can not invoke this object")
            if (Ops.isnull(ispec.ClassHandle) == 0L)
                cr = invokee!!.get_attribute_boxed(tc, ispec.ClassHandle, ispec.AttrName, ispec.Hint) as CodeRef
            else {
                cr = ispec.InvocationHandler as CodeRef
                @Suppress("UNCHECKED_CAST")
                csd = csd.injectInvokee(tc, args as Array<Any?>, invokee)
                args = tc.flatArgs!!
                shared = true
            }
        }

        /* If not shared, then we'll optimize on the assumption that most
         * method callsites are monomorphic. */
        if (!cr.isCompilerStub && !shared) {
            try {
                val resType = MethodType.methodType(Void.TYPE,
                        String::class.java, CallSiteDescriptor::class.java, STable::class.java,
                        CodeRef::class.java, ThreadContext::class.java, Array<Any>::class.java)
                val res = caller.findStatic(IndyBootstrap::class.java,
                    "methcallCacheMono_noa", resType)
                cs.setTarget(MethodHandles
                    .dropArguments(
                        MethodHandles.insertArguments(res, 1, csd,
                            Ops.decont(invocant, tc)!!.st, cr),
                        1, Integer.TYPE)
                    .asCollector(Array<Any>::class.java, cs.type().parameterCount() - 3)
                    .asType(cs.type()))
            }
            catch (e: Throwable) {
                ExceptionHandling.dieInternal(tc, e)
            }
        }

        /* Make the call directly for this initial call. */
        try {
            ArgsExpectation.invokeByExpectation(tc, cr, csd, args)
        }
        catch (e: ControlException) {
            throw e
        }
        catch (e: Throwable) {
            ExceptionHandling.dieInternal(tc, e)
        }
    }

    @JvmStatic
    fun methcallCacheMono_noa(name: String, csdIn: CallSiteDescriptor,
            assumedST: STable, assumedCR: CodeRef, tc: ThreadContext, vararg argsIn: Any?) {
        var csd = csdIn
        var args: Array<out Any?> = argsIn
        /* Try to resolve method to a coderef. */
        val invocant = Ops.decont(args[0] as SixModelObject?, tc)
        val cr: CodeRef
        if (invocant!!.st === assumedST) {
            cr = assumedCR
        }
        else {
            val invokee = Ops.findmethod(invocant, name, tc)

            if (invokee is CodeRef) {
                cr = invokee
            }
            else {
                val ispec = invokee!!.st.InvocationSpec
                    ?: throw ExceptionHandling.dieInternal(tc, "Can not invoke this object")
                if (Ops.isnull(ispec.ClassHandle) == 0L)
                    cr = invokee!!.get_attribute_boxed(tc, ispec.ClassHandle, ispec.AttrName, ispec.Hint) as CodeRef
                else {
                    cr = ispec.InvocationHandler as CodeRef
                    @Suppress("UNCHECKED_CAST")
                    csd = csd.injectInvokee(tc, args as Array<Any?>, invokee)
                    args = tc.flatArgs!!
                }
            }
        }

        /* Make the call directly for this initial call. */
        try {
            ArgsExpectation.invokeByExpectation(tc, cr, csd, args)
        }
        catch (e: ControlException) {
            throw e
        }
        catch (e: Throwable) {
            ExceptionHandling.dieInternal(tc, e)
        }
    }

    @JvmStatic
    fun indmethcall_noa(caller: Lookup, s: String, type: MethodType): CallSite {
        try {
            /* Look up methcall invoker method. */
            val resType = MethodType.methodType(Void.TYPE,
                    MutableCallSite::class.java, Integer.TYPE,
                    ThreadContext::class.java, String::class.java, Array<Any>::class.java)
            val res = caller.findStatic(IndyBootstrap::class.java, "indmethcallInvoker_noa", resType)

            /* Create a mutable callsite, and curry the resolver with it and
             * the method name. */
            val cs = MutableCallSite(type)
            cs.setTarget(MethodHandles
                .insertArguments(res, 0, cs)
                .asCollector(Array<Any>::class.java, type.parameterCount() - 3)
                .asType(type))

            /* Produce callsite. */
            return cs
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    fun indmethcallInvoker_noa(cs: MutableCallSite, csIdx: Int,
            tc: ThreadContext, name: String, vararg argsIn: Any?) {
        var args: Array<out Any?> = argsIn
        /* Resolve callsite descriptor. */
        var csd = if (csIdx >= 0)
            tc.curFrame!!.codeRef.staticInfo.compUnit.callSites!![csIdx]
        else
            Ops.emptyCallSite

        /* Try to resolve method to a coderef. */
        val invocant = args[0] as SixModelObject
        val invokee = Ops.findmethod(invocant, name, tc)
        val cr: CodeRef
        if (invokee is CodeRef) {
            cr = invokee
        }
        else {
            val ispec = invokee!!.st.InvocationSpec
                ?: throw ExceptionHandling.dieInternal(tc, "Can not invoke this object")
            if (Ops.isnull(ispec.ClassHandle) == 0L)
                cr = invokee!!.get_attribute_boxed(tc, ispec.ClassHandle, ispec.AttrName, ispec.Hint) as CodeRef
            else {
                cr = ispec.InvocationHandler as CodeRef
                @Suppress("UNCHECKED_CAST")
                csd = csd.injectInvokee(tc, args as Array<Any?>, invokee)
                args = tc.flatArgs!!
            }
        }

        /* Make the call. */
        try {
            ArgsExpectation.invokeByExpectation(tc, cr, csd, args)
        }
        catch (e: ControlException) {
            throw e
        }
        catch (e: Throwable) {
            ExceptionHandling.dieInternal(tc, e)
        }
    }
}
