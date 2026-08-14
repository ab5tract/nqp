package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.TypeObject

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.lang.reflect.Field

/* The ASM-generated per-type P6Opaque subclasses extend this class and
 * override the attribute accessors, deserializeFields and the delegate
 * lookups, so those all stay open. */
open class P6OpaqueBaseInstance : SixModelObject() {
    // If this is not null, all operations are delegate to it. Used when we
    // load the object from an SC or when we mix in and it causes a resize.
    @JvmField var delegate: SixModelObject? = null

    fun resolveAttribute(classHandle: SixModelObject?, name: String?): Int {
        val rd = this.st.REPRData as P6OpaqueREPRData
        val classHandles = rd.classHandles!!
        for (i in classHandles.indices) {
            if (classHandles[i] === classHandle) {
                val idx = rd.nameToHintMap!![i].getOrDefault(name, -1)
                if (idx != -1)
                    return idx
                else
                    break
            }
        }
        throw RuntimeException("No such attribute '$name' for this object")
    }

    fun autoViv(slot: Int, tc: ThreadContext): SixModelObject {
        val rd = this.st.REPRData as P6OpaqueREPRData
        val av = rd.autoVivContainers!![slot]
        if (av is TypeObject)
            return av
        else
            return av!!.clone(tc)
    }

    override fun clone(tc: ThreadContext): SixModelObject {
        try {
            val cloned: SixModelObject
            if (Ops.isnull(this.delegate) == 0L)
                cloned = this.delegate!!.clone(tc)
            else
                cloned = this.clone() as SixModelObject
            cloned.sc = null
            return cloned
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
    }

    fun instClone(): SixModelObject {
        try {
            return this.clone() as SixModelObject
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
    }

    open fun deserializeFields(tc: ThreadContext, st: STable, reader: SerializationReader) {
    }

    inner class BadNativeRuntimeException(msg: String) : RuntimeException(msg)
    fun badNative() {
        throw BadNativeRuntimeException("Cannot access a reference attribute as a native attribute")
    }

    inner class BadReferenceRuntimeException(msg: String) : RuntimeException(msg)
    fun badReference() {
        throw BadReferenceRuntimeException("Cannot access a native attribute as a reference attribute")
    }

    override fun get_attribute_boxed(tc: ThreadContext, class_handle: SixModelObject?,
                                     name: String?, hint: Long): SixModelObject? {
        if (Ops.isnull(this.delegate) == 0L)
            return this.delegate!!.get_attribute_boxed(tc, class_handle, name, hint)
        else
            return super.get_attribute_boxed(tc, class_handle, name, hint)
    }
    override fun get_attribute_native(tc: ThreadContext, class_handle: SixModelObject?, name: String?, hint: Long) {
        if (Ops.isnull(this.delegate) == 0L)
            this.delegate!!.get_attribute_native(tc, class_handle, name, hint)
        else
            super.get_attribute_native(tc, class_handle, name, hint)
    }
    override fun bind_attribute_boxed(tc: ThreadContext, class_handle: SixModelObject?,
                                      name: String?, hint: Long, value: SixModelObject?) {
        if (Ops.isnull(this.delegate) == 0L)
            this.delegate!!.bind_attribute_boxed(tc, class_handle, name, hint, value)
        else
            super.bind_attribute_boxed(tc, class_handle, name, hint, value)
    }
    override fun bind_attribute_native(tc: ThreadContext, class_handle: SixModelObject?, name: String?, hint: Long) {
        if (Ops.isnull(this.delegate) == 0L)
            this.delegate!!.bind_attribute_native(tc, class_handle, name, hint)
        else
            super.bind_attribute_native(tc, class_handle, name, hint)
    }
    override fun is_attribute_initialized(tc: ThreadContext, class_handle: SixModelObject?,
                                          name: String?, hint: Long): Long {
        if (Ops.isnull(this.delegate) == 0L)
            return this.delegate!!.is_attribute_initialized(tc, class_handle, name, hint)
        else
            return super.is_attribute_initialized(tc, class_handle, name, hint)
    }

    /* Atomic access to the reflectively-named field_N slots of the
     * runtime-generated P6Opaque subclasses, via VarHandle (formerly
     * sun.misc.Unsafe field offsets). */
    private fun attributeVarHandle(class_handle: SixModelObject?, name: String?): VarHandle {
        try {
            val field: Field = this.javaClass.getDeclaredField(
                "field_" + resolveAttribute(class_handle, name))
            field.setAccessible(true)
            return MethodHandles.lookup().unreflectVarHandle(field)
        }
        catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    override fun cas_attribute_boxed(tc: ThreadContext, class_handle: SixModelObject?,
                                     name: String?, expected: SixModelObject?, value: SixModelObject?): SixModelObject? {
        val vh = attributeVarHandle(class_handle, name)
        return if (vh.compareAndSet(this, expected, value))
            expected
        else
            vh.getVolatile(this) as SixModelObject?
    }

    override fun atomic_bind_attribute_boxed(tc: ThreadContext, class_handle: SixModelObject?,
                                             name: String?, value: SixModelObject?) {
        attributeVarHandle(class_handle, name).setVolatile(this, value)
    }

    open fun posDelegate(): SixModelObject {
        if (Ops.isnull(this.delegate) == 0L)
            return (this.delegate as P6OpaqueBaseInstance).posDelegate()
        throw RuntimeException("This type does not support positional operations")
    }

    open fun assDelegate(): SixModelObject {
        if (Ops.isnull(this.delegate) == 0L)
            return (this.delegate as P6OpaqueBaseInstance).assDelegate()
        throw RuntimeException("This type does not support associative operations")
    }

    override fun elems(tc: ThreadContext): Long {
        return posDelegate().elems(tc)
    }
    override fun at_pos_boxed(tc: ThreadContext, index: Long): SixModelObject? {
        return posDelegate().at_pos_boxed(tc, index)
    }
    override fun at_pos_native(tc: ThreadContext, index: Long) {
        posDelegate().at_pos_native(tc, index)
    }
    override fun bind_pos_boxed(tc: ThreadContext, index: Long, value: SixModelObject?) {
        posDelegate().bind_pos_boxed(tc, index, value)
    }
    override fun bind_pos_native(tc: ThreadContext, index: Long) {
        posDelegate().bind_pos_native(tc, index)
    }
    override fun set_elems(tc: ThreadContext, count: Long) {
        posDelegate().set_elems(tc, count)
    }
    override fun push_boxed(tc: ThreadContext, value: SixModelObject?) {
        posDelegate().push_boxed(tc, value)
    }
    override fun push_native(tc: ThreadContext) {
        posDelegate().push_native(tc)
    }
    override fun pop_boxed(tc: ThreadContext): SixModelObject? {
        return posDelegate().pop_boxed(tc)
    }
    override fun pop_native(tc: ThreadContext) {
        posDelegate().pop_native(tc)
    }
    override fun unshift_boxed(tc: ThreadContext, value: SixModelObject?) {
        posDelegate().unshift_boxed(tc, value)
    }
    override fun unshift_native(tc: ThreadContext) {
        posDelegate().unshift_native(tc)
    }
    override fun shift_boxed(tc: ThreadContext): SixModelObject? {
        return posDelegate().shift_boxed(tc)
    }
    override fun shift_native(tc: ThreadContext) {
        posDelegate().shift_native(tc)
    }
    override fun slice(tc: ThreadContext, dest: SixModelObject, beginning: Long, end: Long): SixModelObject? {
        return posDelegate().slice(tc, dest, beginning, end)
    }
    override fun splice(tc: ThreadContext, from: SixModelObject, offset: Long, count: Long) {
        posDelegate().splice(tc, from, offset, count)
    }

    override fun at_key_boxed(tc: ThreadContext, key: String?): SixModelObject? {
        return assDelegate().at_key_boxed(tc, key)
    }
    override fun bind_key_boxed(tc: ThreadContext, key: String?, value: SixModelObject?) {
        assDelegate().bind_key_boxed(tc, key, value)
    }
    override fun exists_key(tc: ThreadContext, key: String?): Long {
        return assDelegate().exists_key(tc, key)
    }
    override fun delete_key(tc: ThreadContext, key: String?) {
        assDelegate().delete_key(tc, key)
    }
}
