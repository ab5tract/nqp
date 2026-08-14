package org.raku.nqp.sixmodel.reprs

import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.SixModelObject

class P6OpaqueDelegateInstance : P6OpaqueBaseInstance() {
    override fun get_attribute_boxed(tc: ThreadContext, class_handle: SixModelObject?,
                                     name: String?, hint: Long): SixModelObject? {
        return delegate!!.get_attribute_boxed(tc, class_handle, name, hint)
    }
    override fun get_attribute_native(tc: ThreadContext, class_handle: SixModelObject?, name: String?, hint: Long) {
        delegate!!.get_attribute_native(tc, class_handle, name, hint)
    }
    override fun bind_attribute_boxed(tc: ThreadContext, class_handle: SixModelObject?,
                                      name: String?, hint: Long, value: SixModelObject?) {
        delegate!!.bind_attribute_boxed(tc, class_handle, name, hint, value)
    }
    override fun bind_attribute_native(tc: ThreadContext, class_handle: SixModelObject?, name: String?, hint: Long) {
        delegate!!.bind_attribute_native(tc, class_handle, name, hint)
    }
    override fun is_attribute_initialized(tc: ThreadContext, class_handle: SixModelObject?,
                                          name: String?, hint: Long): Long {
        return delegate!!.is_attribute_initialized(tc, class_handle, name, hint)
    }
    override fun set_int(tc: ThreadContext, value: Long) {
        delegate!!.set_int(tc, value)
    }
    override fun get_int(tc: ThreadContext): Long {
        return delegate!!.get_int(tc)
    }
    override fun set_num(tc: ThreadContext, value: Double) {
        delegate!!.set_num(tc, value)
    }
    override fun get_num(tc: ThreadContext): Double {
        return delegate!!.get_num(tc)
    }
    override fun set_str(tc: ThreadContext, value: String?) {
        delegate!!.set_str(tc, value)
    }
    override fun get_str(tc: ThreadContext): String? {
        return delegate!!.get_str(tc)
    }
    override fun at_pos_boxed(tc: ThreadContext, index: Long): SixModelObject? {
        return delegate!!.at_pos_boxed(tc, index)
    }
    override fun bind_pos_boxed(tc: ThreadContext, index: Long, value: SixModelObject?) {
        delegate!!.bind_pos_boxed(tc, index, value)
    }
    override fun set_elems(tc: ThreadContext, count: Long) {
        delegate!!.set_elems(tc, count)
    }
    override fun push_boxed(tc: ThreadContext, value: SixModelObject?) {
        delegate!!.push_boxed(tc, value)
    }
    override fun pop_boxed(tc: ThreadContext): SixModelObject? {
        return delegate!!.pop_boxed(tc)
    }
    override fun unshift_boxed(tc: ThreadContext, value: SixModelObject?) {
        delegate!!.unshift_boxed(tc, value)
    }
    override fun shift_boxed(tc: ThreadContext): SixModelObject? {
        return delegate!!.shift_boxed(tc)
    }
    override fun slice(tc: ThreadContext, dest: SixModelObject, beginning: Long, end: Long): SixModelObject? {
        return delegate!!.slice(tc, dest, beginning, end)
    }
    override fun splice(tc: ThreadContext, from: SixModelObject, offset: Long, count: Long) {
        delegate!!.splice(tc, from, offset, count)
    }
    override fun at_key_boxed(tc: ThreadContext, key: String?): SixModelObject? {
        return delegate!!.at_key_boxed(tc, key)
    }
    override fun bind_key_boxed(tc: ThreadContext, key: String?, value: SixModelObject?) {
        delegate!!.bind_key_boxed(tc, key, value)
    }
    override fun exists_key(tc: ThreadContext, key: String?): Long {
        return delegate!!.exists_key(tc, key)
    }
    override fun delete_key(tc: ThreadContext, key: String?) {
        delegate!!.delete_key(tc, key)
    }
    override fun elems(tc: ThreadContext): Long {
        return delegate!!.elems(tc)
    }
    override fun clone(tc: ThreadContext): SixModelObject {
        return delegate!!.clone(tc)
    }
}
