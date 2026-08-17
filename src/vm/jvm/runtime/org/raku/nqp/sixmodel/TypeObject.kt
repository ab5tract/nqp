package org.raku.nqp.sixmodel

import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext

open class TypeObject : SixModelObject() {
    override fun get_attribute_boxed(tc: ThreadContext, classHandle: SixModelObject?,
                                     name: String?, hint: Long): SixModelObject {
        throw ExceptionHandling.dieInternal(tc, "Cannot look up attributes in a type object")
    }

    override fun get_attribute_native(tc: ThreadContext, classHandle: SixModelObject?, name: String?, hint: Long) {
        throw ExceptionHandling.dieInternal(tc, "Cannot look up attributes in a type object")
    }

    override fun bind_attribute_boxed(tc: ThreadContext, classHandle: SixModelObject?,
                                      name: String?, hint: Long, value: SixModelObject?) {
        throw ExceptionHandling.dieInternal(tc, "Cannot look up attributes in a type object")
    }

    override fun bind_attribute_native(tc: ThreadContext, classHandle: SixModelObject?, name: String?, hint: Long) {
        throw ExceptionHandling.dieInternal(tc, "Cannot look up attributes in a type object")
    }

    override fun is_attribute_initialized(tc: ThreadContext, classHandle: SixModelObject?,
                                          name: String?, hint: Long): Long {
        throw ExceptionHandling.dieInternal(tc, "Cannot look up attributes in a type object")
    }

    override fun get_int(tc: ThreadContext): Long {
        throw ExceptionHandling.dieInternal(tc, "Cannot unbox a type object in get_int")
    }

    override fun get_num(tc: ThreadContext): Double {
        throw ExceptionHandling.dieInternal(tc, "Cannot unbox a type object in get_num")
    }

    override fun get_str(tc: ThreadContext): String {
        throw ExceptionHandling.dieInternal(tc, "Cannot unbox a type object in get_str")
    }

    override fun at_pos_boxed(tc: ThreadContext, index: Long): SixModelObject =
        Ops.createNull(tc)

    override fun bind_pos_boxed(tc: ThreadContext, index: Long, value: SixModelObject?) {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation bind_pos_boxed on a type object")
    }

    override fun set_elems(tc: ThreadContext, count: Long) {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation set_elems on a type object")
    }

    override fun push_boxed(tc: ThreadContext, value: SixModelObject?) {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation push_boxed on a type object")
    }

    override fun pop_boxed(tc: ThreadContext): SixModelObject {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation pop_boxed on a type object")
    }

    override fun unshift_boxed(tc: ThreadContext, value: SixModelObject?) {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation unshift_boxed on a type object")
    }

    override fun shift_boxed(tc: ThreadContext): SixModelObject {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation shift_boxed on a type object")
    }

    override fun slice(tc: ThreadContext, dest: SixModelObject, beginning: Long, end: Long): SixModelObject {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation slice on a type object")
    }

    override fun splice(tc: ThreadContext, from: SixModelObject, offset: Long, count: Long) {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation splice on a type object")
    }

    override fun at_key_boxed(tc: ThreadContext, key: String?): SixModelObject =
        Ops.createNull(tc)

    override fun bind_key_boxed(tc: ThreadContext, key: String?, value: SixModelObject?) {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation bind_key_boxed on a type object")
    }

    override fun exists_key(tc: ThreadContext, key: String?): Long {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation exists_key on a type object")
    }

    override fun delete_key(tc: ThreadContext, key: String?) {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation delete_key on a type object")
    }

    override fun elems(tc: ThreadContext): Long {
        throw ExceptionHandling.dieInternal(tc, "Cannot do aggregate operation elems on a type object")
    }

    override fun clone(tc: ThreadContext): SixModelObject {
        try {
            val cloned = super.clone() as SixModelObject
            cloned.sc = null
            return cloned
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
    }
}
