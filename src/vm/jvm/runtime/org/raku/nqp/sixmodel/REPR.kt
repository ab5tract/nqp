package org.raku.nqp.sixmodel

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.ThreadContext

/**
 * Base of all 6model representations. Has default implementations of functions that
 * are not mandatory.
 */
abstract class REPR {
    /**
     * The ID of the representation. Purely internal, may vary from run to run in
     * some cases, don't persist.
     */
    @JvmField var ID = 0

    /**
     * The name of the representation. Assigned by REPRRegistry at
     * registration time, before the instance is reachable elsewhere.
     */
    lateinit var name: String

    /**
     * A name that is used to differentiate between different (sub)types that
     * have the same representation otherwise.
     * Can be left unset for most representations, but is used to detect
     * native VMArrays during deserialization.
     */
    @JvmField var subtypeName: String? = null

    /**
     * Creates a new type object of this representation, and associates it
     * with the given HOW.
     */
    abstract fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject

    /**
     * Allocates a new, but uninitialized object, based on the
     * specified s-table. */
    abstract fun allocate(tc: ThreadContext, st: STable): SixModelObject

    /**
     * Composes the representation; typically performed at type composition time.
     */
    open fun compose(tc: ThreadContext, st: STable, reprInfo: SixModelObject) {
        // By default, nothing to do.
    }

    /**
     * Gets attribute access hint for the representation.
     */
    open fun hint_for(tc: ThreadContext, st: STable, classHandle: SixModelObject?, name: String?): Long {
        return STable.NO_HINT
    }

    /**
     * Gets information on how objects of this representation like to be
     * stored (inlined into the body of another object, or referencey).
     */
    open fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec {
        return StorageSpec()
    }

    /**
     * For aggregate types, gets the storage type of values in the aggregate.
     */
    open fun get_value_storage_spec(tc: ThreadContext, st: STable): StorageSpec? {
        throw ExceptionHandling.dieInternal(tc, "This representation does not implement get_value_storage_spec")
    }

    /**
     * Handles an object changing its type. The representation is responsible
     * for doing any changes to the underlying data structure, and may reject
     * changes that it's not willing to do (for example, a representation may
     * choose to only handle switching to a subclass). It is also left to update
     * the S-Table reference as needed; while in theory this could be factored
     * out, the representation probably knows more about timing issues and
     * thread safety requirements.
     */
    open fun change_type(tc: ThreadContext, Object: SixModelObject, NewType: SixModelObject) {
        throw ExceptionHandling.dieInternal(tc, "This representation does not support type changes.")
    }

    /**
     * Object serialization. Writes the objects body out using the passed
     * serialization writer. */
    open fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        throw ExceptionHandling.dieInternal(tc, "Missing serialize function for REPR $name")
    }

    /**
     * Object deserialization. Happens in two steps. The first stub step
     * creates an object to be filled out later. Note that the STable may
     * not be fully available yet if it's in the current compilation unit.
     * The second step has the STable fully formed (though objects it
     * references may not be) and should do the rest of the work. */
    abstract fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject?
    abstract fun deserialize_finish(tc: ThreadContext, st: STable, reader: SerializationReader, obj: SixModelObject)

    /**
     * REPR data serialization. Serializes the per-type representation data that
     * is attached to the supplied STable.
     */
    open fun serialize_repr_data(tc: ThreadContext, st: STable, writer: SerializationWriter) {
        // It's fine for this to be unimplemented.
    }

    /**
     * REPR data deserialization. Deserializes the per-type representation data and
     * attaches it to the supplied STable.
     */
    open fun deserialize_repr_data(tc: ThreadContext, st: STable, reader: SerializationReader) {
        // It's fine for this to be unimplemented.
    }

    /**
     * Flattening related functions.
     */
    open fun inlineStorage(tc: ThreadContext, st: STable, cw: ClassWriter, prefix: String) {
        throw ExceptionHandling.dieInternal(tc, "This representation cannot inline itself into another")
    }
    open fun inlineBind(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        throw ExceptionHandling.dieInternal(tc, "This representation cannot inline itself into another")
    }
    open fun inlineGet(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        throw ExceptionHandling.dieInternal(tc, "This representation cannot inline itself into another")
    }
    open fun inlineDeserialize(tc: ThreadContext, st: STable, mv: MethodVisitor, className: String, prefix: String) {
        throw ExceptionHandling.dieInternal(tc, "This representation cannot inline itself into another")
    }
    open fun generateBoxingMethods(tc: ThreadContext, st: STable, cw: ClassWriter, className: String, prefix: String) {
        throw ExceptionHandling.dieInternal(tc, "This representation does not support being a box target")
    }
    open fun serialize_inlined(tc: ThreadContext, st: STable, writer: SerializationWriter,
            prefix: String, obj: SixModelObject) {
        throw ExceptionHandling.dieInternal(tc, "This representation cannot serialize an inlined representation of itself")
    }
    // These two functions are called when determining if a new class is needed; they should append a complete description
    // of the code they would use for inline* or generateBoxingMethods, in a format which is arbitrary except that it may
    // not contain imbalanced parens, and return true.  Returning false means no description is possible and the class must
    // always be created fresh.
    open fun inline_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean {
        return false
    }
    open fun box_description(tc: ThreadContext, st: STable, out: StringBuilder): Boolean {
        return false
    }
}
