package org.raku.nqp.sixmodel.reprs

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.util.ArrayList
import org.raku.nqp.runtime.ExceptionHandling
import org.raku.nqp.runtime.Ops
import org.raku.nqp.runtime.ThreadContext
import org.raku.nqp.sixmodel.Boxable
import org.raku.nqp.sixmodel.BoxedPrimitive
import org.raku.nqp.sixmodel.REPR
import org.raku.nqp.sixmodel.STable
import org.raku.nqp.sixmodel.SerializationReader
import org.raku.nqp.sixmodel.SerializationWriter
import org.raku.nqp.sixmodel.SixModelObject
import org.raku.nqp.sixmodel.StorageSpec
import org.raku.nqp.sixmodel.TypeObject

/** The P6opaque representation on the JVM: objects are RakuObjects laid
 *  out by a RakuObjectLayout. Registered under the name "P6opaque". */
class RakuObjectREPR : REPR() {

    override fun type_object_for(tc: ThreadContext, HOW: SixModelObject?): SixModelObject {
        val st = STable(this, HOW)
        st.REPRData = RakuObjectREPRData()
        val obj = TypeObject()
        obj.st = st
        st.WHAT = obj
        return st.WHAT
    }

    /** Builds the canonical layout from the per-slot lists compose and
     *  deserialize_repr_data both produce. */
    private fun install(st: STable, rd: RakuObjectREPRData, kinds: ArrayList<SlotKind>, specs: ArrayList<StorageSpec?>,
                        slotSTables: ArrayList<STable?>, autoViv: Array<SixModelObject?>, classHandles: Array<SixModelObject?>,
                        nameToSlot: ArrayList<Object2IntOpenHashMap<String>>, mi: Boolean,
                        unboxInt: Int, unboxNum: Int, unboxStr: Int, unboxObj: Int, posDel: Int, assDel: Int) {
        var refs = 0; var longs = 0
        for (k in kinds) if (k == SlotKind.INT || k == SlotKind.NUM) longs++ else refs++
        rd.layout = RakuObjectLayout(st, RakuObjectLayout.chooseClass(refs, longs), kinds.toTypedArray(),
            specs.toTypedArray(), slotSTables.toTypedArray(), autoViv, classHandles, nameToSlot, mi,
            unboxInt, unboxNum, unboxStr, unboxObj, posDel, assDel, variant = false)
    }

    private fun kindOf(tc: ThreadContext, attrSt: STable): SlotKind =
        if (attrSt.REPR.get_storage_spec(tc, attrSt).inlining == org.raku.nqp.sixmodel.Inlining.INLINED)
            attrSt.REPR.inlinedKind() ?: throw ExceptionHandling.dieInternal(tc,
                "Representation " + attrSt.REPR.name + " says it inlines but names no slot kind")
        else SlotKind.REF

    override fun compose(tc: ThreadContext, st: STable, reprInfo: SixModelObject) {
        val rd = st.REPRData as RakuObjectREPRData
        if (rd.layout != null)
            throw ExceptionHandling.dieInternal(tc, "Type " + st.debugName + " is already composed")
        val attrInfo = reprInfo.at_key_boxed(tc, "attribute")!!

        var curAttr = 0
        var mi = false
        val classHandles = ArrayList<SixModelObject?>()
        val nameToSlot = ArrayList<Object2IntOpenHashMap<String>>()
        val autoVivs = ArrayList<SixModelObject?>()
        val kinds = ArrayList<SlotKind>()
        val specs = ArrayList<StorageSpec?>()
        val slotSTables = ArrayList<STable?>()
        var unboxInt = -1; var unboxNum = -1; var unboxStr = -1; var unboxObj = -1; var posDel = -1; var assDel = -1
        val mroLength = attrInfo.elems(tc)
        for (i in mroLength - 1 downTo 0) {
            val entry = attrInfo.at_pos_boxed(tc, i)!!
            val type = entry.at_pos_boxed(tc, 0)!!
            val attrs = entry.at_pos_boxed(tc, 1)!!
            val parents = entry.at_pos_boxed(tc, 2)!!
            val numAttrs = attrs.elems(tc)
            if (numAttrs > 0) {
                val indexes = Object2IntOpenHashMap<String>()
                for (j in 0 until numAttrs) {
                    val attrHash = attrs.at_pos_boxed(tc, j)!!
                    val attrName = attrHash.at_key_boxed(tc, "name")!!.get_str(tc)
                    var attrType = attrHash.at_key_boxed(tc, "type")
                    if (Ops.isnull(attrType) == 1L) attrType = tc.gc.KnowHOW
                    indexes.put(attrName, curAttr)
                    val attrSt = attrType!!.st
                    val kind = kindOf(tc, attrSt)
                    kinds.add(kind)
                    if (kind == SlotKind.REF) { slotSTables.add(null); specs.add(null) }
                    else { slotSTables.add(attrSt); specs.add(attrSt.REPR.get_storage_spec(tc, attrSt)) }
                    autoVivs.add(attrHash.at_key_boxed(tc, "auto_viv_container"))
                    if (attrHash.exists_key(tc, "box_target") != 0L) {
                        if (kind == SlotKind.REF)
                            throw ExceptionHandling.dieInternal(tc, "A box_target must not have a reference type attribute")
                        when (attrSt.REPR.get_storage_spec(tc, attrSt).boxedPrimitive) {
                            BoxedPrimitive.INT, BoxedPrimitive.UINT -> unboxInt = curAttr
                            BoxedPrimitive.NUM -> unboxNum = curAttr
                            BoxedPrimitive.STR -> unboxStr = curAttr
                            else -> unboxObj = curAttr
                        }
                    }
                    if (attrHash.exists_key(tc, "positional_delegate") != 0L) posDel = curAttr
                    if (attrHash.exists_key(tc, "associative_delegate") != 0L) assDel = curAttr
                    curAttr++
                }
                classHandles.add(type)
                nameToSlot.add(indexes)
            }
            if (parents.elems(tc) > 1) mi = true
        }
        install(st, rd, kinds, specs, slotSTables, autoVivs.toTypedArray(), classHandles.toTypedArray(), nameToSlot, mi,
            unboxInt, unboxNum, unboxStr, unboxObj, posDel, assDel)
    }

    override fun allocate(tc: ThreadContext, st: STable): SixModelObject {
        val rd = st.REPRData as RakuObjectREPRData
        val l = rd.layout ?: throw ExceptionHandling.dieInternal(tc, "Cannot allocate an instance of the uncomposed type " + st.debugName)
        return l.newInstance()
    }

    override fun change_type(tc: ThreadContext, Object: SixModelObject, NewType: SixModelObject) {
        if (NewType.st.REPR !is RakuObjectREPR)
            throw ExceptionHandling.dieInternal(tc, "P6opaque can only rebless to another P6opaque-based type")
        val ourLayout = (Object.st.REPRData as RakuObjectREPRData).layout!!
        val targetRd = NewType.st.REPRData as RakuObjectREPRData
        val targetLayout = targetRd.layout
            ?: throw ExceptionHandling.dieInternal(tc, "Cannot rebless to the uncomposed type " + NewType.st.debugName)
        val ours = ourLayout.classHandles
        val theirs = targetLayout.classHandles
        if (ours.size > theirs.size)
            throw ExceptionHandling.dieInternal(tc, "Incompatible MROs in P6opaque rebless")
        for (i in ours.indices)
            if (ours[i] !== theirs[i])
                throw ExceptionHandling.dieInternal(tc, "Incompatible MROs in P6opaque rebless")
        val o = Object as RakuObject
        val newLayout = targetRd.layoutFor(o.javaClass)
        newLayout.growExt(o)
        o.layout = newLayout
        Object.st = NewType.st
        if (RakuObjectLayout.STATS) RakuObjectLayout.REBLESSES.incrementAndGet()
        if (RakuObjectLayout.TRACE) System.err.println("rebless: " + ourLayout.st.debugName + " -> " + NewType.st.debugName
            + " class=" + o.javaClass.simpleName + (if (newLayout.variant) " (variant)" else ""))
    }

    override fun get_storage_spec(tc: ThreadContext, st: STable): StorageSpec {
        val l = (st.REPRData as RakuObjectREPRData).layout ?: return StorageSpec()
        val canBox = buildSet<Boxable> {
            if (l.unboxIntSlot >= 0) add(Boxable.INT)
            if (l.unboxNumSlot >= 0) add(Boxable.NUM)
            if (l.unboxStrSlot >= 0) add(Boxable.STR)
        }
        return StorageSpec(canBox = canBox)
    }

    override fun hint_for(tc: ThreadContext, st: STable, classHandle: SixModelObject?, name: String?): Long {
        val rd = st.REPRData as? RakuObjectREPRData ?: return STable.NO_HINT
        val l = rd.layout ?: return STable.NO_HINT
        val s = l.slotFor(classHandle, name)
        return if (s < 0) STable.NO_HINT else s.toLong()
    }

    /* ----- REPR data: the same bytes as before ----- */

    override fun deserialize_repr_data(tc: ThreadContext, st: STable, reader: SerializationReader) {
        val rd = RakuObjectREPRData()
        st.REPRData = rd
        val numAttributes = reader.readLong().toInt()
        val flattened = arrayOfNulls<STable>(numAttributes)
        for (i in 0 until numAttributes)
            if (reader.readLong() != 0L) flattened[i] = reader.readSTableRef()
        val mi = reader.readLong() != 0L
        val autoViv = arrayOfNulls<SixModelObject>(numAttributes)
        if (reader.readLong() != 0L)
            for (i in 0 until numAttributes) autoViv[i] = reader.readRef()
        val unboxInt = reader.readLong().toInt()
        val unboxNum = reader.readLong().toInt()
        val unboxStr = reader.readLong().toInt()
        var unboxObj = -1
        if (reader.readLong() != 0L) unboxObj = reader.readLong().toInt()
        val numClasses = reader.readLong().toInt()
        val classHandles = ArrayList<SixModelObject?>()
        val nameToSlot = ArrayList<Object2IntOpenHashMap<String>>()
        for (i in 0 until numClasses) {
            val classHandle = reader.readRef()
            val nameToHintObject = reader.readRef()
            if (Ops.isnull(nameToHintObject) == 1L) {
                /* Nothing to do. */
            }
            else if (nameToHintObject is VMHashInstance) {
                val map = Object2IntOpenHashMap<String>()
                val origMap = nameToHintObject.storage
                if (origMap.size > 0) {
                    for (key in origMap.keys) map.put(key, origMap[key]!!.get_int(tc).toInt())
                    classHandles.add(classHandle)
                    nameToSlot.add(map)
                }
            }
            else throw ExceptionHandling.dieInternal(tc, "Unexpected hint map representation in deserialize")
        }
        val posDel = reader.readLong().toInt()
        val assDel = reader.readLong().toInt()

        val kinds = ArrayList<SlotKind>()
        val specs = ArrayList<StorageSpec?>()
        val slotSTables = ArrayList<STable?>()
        for (i in 0 until numAttributes) {
            val f = flattened[i]
            if (f != null) {
                /* A flattened native's storage spec is its own REPR data,
                 * and the STable table is not in dependency order. */
                reader.forceSTable(f)
                kinds.add(kindOf(tc, f)); slotSTables.add(f); specs.add(f.REPR.get_storage_spec(tc, f))
            }
            else { kinds.add(SlotKind.REF); slotSTables.add(null); specs.add(null) }
        }
        install(st, rd, kinds, specs, slotSTables, autoViv, classHandles.toTypedArray(), nameToSlot, mi,
            unboxInt, unboxNum, unboxStr, unboxObj, posDel, assDel)
    }

    override fun serialize_repr_data(tc: ThreadContext, st: STable, writer: SerializationWriter) {
        val l = (st.REPRData as RakuObjectREPRData).layout
            ?: throw ExceptionHandling.dieInternal(tc, "Representation must be composed before it can be serialized")
        val n = l.kinds.size
        writer.writeInt(n.toLong())
        for (i in 0 until n) {
            val f = l.slotSTables[i]
            if (f == null) writer.writeInt(0) else { writer.writeInt(1); writer.writeSTableRef(f) }
        }
        writer.writeInt(if (l.mi) 1 else 0)
        writer.writeInt(1)
        for (i in 0 until n) writer.writeRef(l.autoViv[i])
        writer.writeInt(l.unboxIntSlot.toLong())
        writer.writeInt(l.unboxNumSlot.toLong())
        writer.writeInt(l.unboxStrSlot.toLong())
        if (l.unboxObjSlot != -1) { writer.writeInt(1); writer.writeInt(l.unboxObjSlot.toLong()) } else writer.writeInt(0)
        writer.writeInt(l.classHandles.size.toLong())
        for (i in l.classHandles.indices) {
            writer.writeRef(l.classHandles[i])
            writer.writeIntHash(l.nameToSlot[i])
        }
        writer.writeInt(l.posDelSlot.toLong())
        writer.writeInt(l.assDelSlot.toLong())
    }

    /* ----- objects ----- */

    /** The stub is the final object. Its class comes from the STable's
     *  layout when that is known (a type from another SC, or one whose REPR
     *  data is already read), else from a peek at the serialized attribute
     *  header; the layout itself is installed at finish. */
    override fun deserialize_stub(tc: ThreadContext, st: STable, reader: SerializationReader): SixModelObject {
        val rd = st.REPRData as? RakuObjectREPRData
        val known = rd?.layout
        if (known != null) return known.newInstance()
        val shape = reader.peekAttributeShape(st)
        val classId = if (shape == null) 0 else RakuObjectLayout.chooseClass(shape[0], shape[1])
        val o: RakuObject = when (classId) {
            0 -> RakuObject4(); 1 -> RakuObject8(); 2 -> RakuObject16()
            3 -> RakuObject4L(); 4 -> RakuObject8L(); else -> RakuObject16L()
        }
        o.st = st
        return o
    }

    override fun deserialize_stub(tc: ThreadContext, st: STable): SixModelObject =
        throw ExceptionHandling.dieInternal(tc, "RakuObject stubs need the reader")

    override fun deserialize_finish(tc: ThreadContext, st: STable, reader: SerializationReader, obj: SixModelObject) {
        val rd = st.REPRData as RakuObjectREPRData
        val o = obj as RakuObject
        val l = rd.layoutFor(o.javaClass)
        l.growExt(o)
        o.layout = l
        for (s in l.kinds.indices) {
            when (l.kinds[s]) {
                SlotKind.REF -> l.setRef(o, s, reader.readRef())
                SlotKind.INT -> l.setLong(o, s, reader.readLong())
                SlotKind.NUM -> l.setLong(o, s, java.lang.Double.doubleToRawLongBits(reader.readDouble()))
                SlotKind.STR -> l.setRef(o, s, reader.readStr())
                SlotKind.BIGINT -> l.setRef(o, s, java.math.BigInteger(reader.readStr()))
                SlotKind.NCBODY -> { /* re-configured each run, as NativeCall.inlineDeserialize did */ }
            }
        }
    }

    override fun serialize(tc: ThreadContext, writer: SerializationWriter, obj: SixModelObject) {
        val o = obj as RakuObject
        val l = o.layout ?: throw ExceptionHandling.dieInternal(tc, "Representation must be composed before it can be serialized")
        for (s in l.kinds.indices) {
            when (l.kinds[s]) {
                SlotKind.REF -> writer.writeRef(l.getRef(o, s) as SixModelObject?)
                SlotKind.INT -> writer.writeInt(l.getLong(o, s))
                SlotKind.NUM -> writer.writeNum(java.lang.Double.longBitsToDouble(l.getLong(o, s)))
                SlotKind.STR -> writer.writeStr(l.getRef(o, s) as String?)
                SlotKind.BIGINT -> writer.writeStr((l.getRef(o, s) as java.math.BigInteger?)?.toString() ?: "0")
                SlotKind.NCBODY -> { /* nothing on the wire */ }
            }
        }
    }
}
