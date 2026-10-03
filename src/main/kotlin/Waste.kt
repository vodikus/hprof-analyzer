package hprof

import kotlinx.serialization.Serializable
import shark.HeapField
import shark.HeapGraph
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.ValueHolder
import java.util.PriorityQueue

@Serializable
data class WasteReport(
    val collections: List<CollectionStat>,
    /** Collections with an allocated backing array, by size / capacity. */
    val fillRatio: List<Bucket>,
    /** Collections by element count (log2 buckets). */
    val sizes: List<Bucket>,
    /** Zeroed primitive arrays, all-null and mostly-null object arrays. */
    val arrays: List<ArrayWaste>,
    val duplicateArrays: List<DupArray>,
    val boxing: List<BoxStat>,
    val nullFields: List<NullFieldClass>,
    val overhead: Overhead,
    val strings: StringStats,
)

/** [unusedBytes]: free slots of the backing array (capacity - size) times the reference size. */
@Serializable
data class CollectionStat(
    val type: String, val count: Long, val empty: Long, val emptyBytes: Long,
    val size: Long, val capacity: Long, val unusedBytes: Long,
)

@Serializable
data class Bucket(val label: String, val count: Long)

/** [kind]: zero (primitive array of zeros), allNull, sparse (>= 90% null). */
@Serializable
data class ArrayWaste(val kind: String, val type: String, val count: Long, val bytes: Long)

@Serializable
data class DupArray(val type: String, val length: Int, val count: Int, val bytesEach: Long, val wasted: Long)

/** [redundant]: instances of a cacheable value (-128..127, booleans) beyond one per value: explicit `new`. */
@Serializable
data class BoxStat(val type: String, val count: Long, val bytes: Long, val redundant: Long)

@Serializable
data class NullFieldClass(val className: String, val instances: Long, val fields: List<FieldNull>)

@Serializable
data class FieldNull(val name: String, val nullPct: Double)

/** Split of the shallow sizes (see [SizeModel]): field/element bytes, object headers, alignment padding. */
@Serializable
data class Overhead(val data: Long, val header: Long, val padding: Long)

@Serializable
data class StringStats(
    val latin1: Long, val utf16: Long, val empty: Long, val longStrings: Long,
    val lengths: List<Bucket>, val prefixes: List<PrefixStat>,
)

@Serializable
data class PrefixStat(val prefix: String, val count: Long, val bytes: Long)

// ponytail: fixed thresholds, CLI flags if real dumps need tuning
private const val MIN_SCAN_BYTES = 64   // primitive arrays smaller than this are not read
private const val MIN_DEDUP_BYTES = 256 // bounds the duplicate-array hash map
private const val MIN_NULL_INSTANCES = 1000
private const val NULL_FIELD = 0.9
private const val SPARSE_MIN_LENGTH = 8
private const val PREFIX_LEN = 12
private const val NULL_FIELD_CLASSES = 15
private const val BIG_COLLECTIONS = 500

/** Size / backing array field per collection class; null size = derived (ArrayDeque head/tail). */
private class CollType(val sizeField: String?, val arrayField: String)

// ponytail: exact class names only; HashSet shows up as its inner HashMap, subclasses are not counted.
// ConcurrentHashMap size = baseCount, approximate under contention (counterCells ignored).
private val COLLECTIONS = mapOf(
    "java.util.ArrayList" to CollType("size", "elementData"),
    "java.util.Vector" to CollType("elementCount", "elementData"),
    "java.util.HashMap" to CollType("size", "table"),
    "java.util.LinkedHashMap" to CollType("size", "table"),
    "java.util.WeakHashMap" to CollType("size", "table"),
    "java.util.Hashtable" to CollType("count", "table"),
    "java.util.concurrent.ConcurrentHashMap" to CollType("baseCount", "table"),
    "java.util.ArrayDeque" to CollType(null, "elements"),
)

private val BOXES = setOf(
    "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Character",
    "java.lang.Boolean", "java.lang.Float", "java.lang.Double",
)

private val FILL_LABELS = listOf("0%", "1-25%", "26-50%", "51-75%", "76-100%")

/** Bucket 0 = 0, 1 = 1, 2 = 2, 3 = 3-4, 4 = 5-8, ... */
internal fun log2Bucket(v: Long): Int = if (v <= 0) 0 else 1 + (64 - java.lang.Long.numberOfLeadingZeros(v - 1))

internal fun log2Label(k: Int): String = when (k) {
    0 -> "0"; 1 -> "1"; 2 -> "2"
    else -> "${(1L shl (k - 2)) + 1}-${1L shl (k - 1)}"
}

private fun buckets(counts: LongArray): List<Bucket> {
    val last = counts.indexOfLast { it > 0 }
    return (0..last).map { Bucket(log2Label(it), counts[it]) }
}

private fun HeapField.isReference() = value.holder is ValueHolder.ReferenceHolder

internal fun List<HeapField>.named(name: String) = firstOrNull { it.name == name }?.value

internal class WasteCollector(private val graph: HeapGraph, n: Int, private val sizeOf: SizeOf) : Collector() {
    private val idSize = graph.identifierByteSize
    private val refSize = sizeOf.model.refSize

    private class CollAcc { var count = 0L; var empty = 0L; var emptyBytes = 0L; var size = 0L; var capacity = 0L }
    private val collections = HashMap<String, CollAcc>()
    private val fill = LongArray(FILL_LABELS.size)
    private val sizes = LongArray(64)

    private class ArrAcc { var count = 0L; var bytes = 0L }
    private val arrays = HashMap<Pair<String, String>, ArrAcc>()

    private class BoxAcc { var count = 0L; var bytes = 0L; var inRange = 0L; var distinct = 0L; val seen = BooleanArray(256) }
    private val boxes = HashMap<String, BoxAcc>()

    private class NullAcc(val name: String, val fields: List<String>) { var count = 0L; val nulls = LongArray(fields.size) }
    private val nulls = HashMap<Long, NullAcc>()

    private var data = 0L
    private var header = 0L
    private var padding = 0L

    private var latin1 = 0L
    private var utf16 = 0L
    private var emptyStrings = 0L
    private var longStrings = 0L
    private val lengths = LongArray(64)

    /** Backing arrays of Strings: already covered by duplicate strings, skipped by the array scan. */
    private val isStringValue = BooleanArray(n)

    /** [shallow] from the size model, [payload] its field/element bytes. */
    private fun overhead(shallow: Long, head: Int, payload: Long) {
        data += payload; header += head; padding += shallow - head - payload
    }

    fun instance(obj: HeapInstance, name: String, fields: List<HeapField>, size: Long) = safe {
        overhead(size, OBJ_HEADER, sizeOf.payload(obj))
        COLLECTIONS[name]?.let { collection(obj.objectIndex, name, it, fields) }
        if (name in BOXES) box(name, fields, size)
        nullFields(obj.instanceClassId, name, fields)
    }

    /** Biggest collections (size, object index), kept for matching collections across dumps by path. */
    private val big = PriorityQueue<LongArray>(compareBy { it[0] })

    /** Object index and size of the biggest collections, largest first. */
    fun bigCollections(): List<Pair<Int, Long>> = big.sortedByDescending { it[0] }.map { it[1].toInt() to it[0] }

    private fun collection(idx: Int, name: String, type: CollType, fields: List<HeapField>) {
        val array = fields.named(type.arrayField)?.asObject?.asObjectArray
        val capacity = array?.let { (it.byteSize / idSize).toLong() } ?: 0L
        val size = if (type.sizeField != null) fields.named(type.sizeField)?.let { it.asInt?.toLong() ?: it.asLong } ?: 0L
        else if (capacity == 0L) 0L
        else Math.floorMod((fields.named("tail")?.asInt ?: 0) - (fields.named("head")?.asInt ?: 0), capacity.toInt()).toLong()
        val acc = collections.getOrPut(name) { CollAcc() }
        acc.count++; acc.size += size; acc.capacity += capacity
        if (size > 0) {
            big.add(longArrayOf(size, idx.toLong()))
            if (big.size > BIG_COLLECTIONS) big.poll()
        }
        if (size == 0L && capacity > 0) { acc.empty++; acc.emptyBytes += sizeOf(array!!) }
        sizes[log2Bucket(size)]++
        if (capacity > 0) fill[if (size <= 0) 0 else Math.ceil(4.0 * size / capacity).toInt().coerceIn(1, 4)]++
    }

    private fun box(name: String, fields: List<HeapField>, size: Long) {
        val acc = boxes.getOrPut(name) { BoxAcc() }
        acc.count++; acc.bytes += size
        val v = fields.named("value") ?: return
        val x: Long = when (name) {
            "java.lang.Integer" -> v.asInt?.toLong()
            "java.lang.Long" -> v.asLong
            "java.lang.Short" -> v.asShort?.toLong()
            "java.lang.Byte" -> v.asByte?.toLong()
            "java.lang.Character" -> v.asChar?.code?.toLong()
            "java.lang.Boolean" -> v.asBoolean?.let { if (it) 1L else 0L }
            else -> null // Float/Double have no cache
        } ?: return
        if (x !in -128L..127L || (name == "java.lang.Character" && x < 0)) return
        acc.inRange++
        val slot = (x + 128).toInt()
        if (!acc.seen[slot]) { acc.seen[slot] = true; acc.distinct++ }
    }

    private fun nullFields(classId: Long, name: String, fields: List<HeapField>) {
        val acc = nulls.getOrPut(classId) { NullAcc(name, fields.filter { it.isReference() }.map { it.name }) }
        if (acc.fields.isEmpty()) return
        acc.count++
        var k = 0
        for (f in fields) if (f.isReference()) {
            if (f.value.isNullReference) acc.nulls[k]++
            k++
        }
    }

    /** Called for every java.lang.String; [value] is null when unreadable. */
    fun string(fields: List<HeapField>, value: String?, valueArray: HeapPrimitiveArray?) = safe {
        valueArray?.let { isStringValue[graph.indexOf(it)] = true }
        // JDK 9+ compact strings: coder 0 = LATIN1, 1 = UTF16; JDK 8 has char[] only (UTF-16)
        if (fields.named("coder")?.asByte == 0.toByte()) latin1++ else utf16++
        if (value == null) return@safe
        if (value.isEmpty()) emptyStrings++
        if (value.length > MAX_DEDUP_STRING) longStrings++
        lengths[log2Bucket(value.length.toLong())]++
    }

    fun objectArray(name: String, size: Long, length: Int, nullCount: Int) = safe {
        overhead(size, ARRAY_HEADER, length.toLong() * refSize)
        val kind = when {
            length > 0 && nullCount == length -> "allNull"
            length >= SPARSE_MIN_LENGTH && nullCount * 10L >= length * 9L -> "sparse"
            else -> return@safe
        }
        arrays.getOrPut(kind to name) { ArrAcc() }.let { it.count++; it.bytes += size }
    }

    fun primitiveArray(size: Long, byteSize: Int) = safe { overhead(size, ARRAY_HEADER, byteSize.toLong()) }

    private class DupAcc(val type: String, val length: Int, val bytes: Long) { var count = 0 }

    /** Second pass over primitive arrays (reads their content): zeroed and duplicated arrays. */
    fun result(top: Int, strings: Map<String, StrAcc>): WasteReport {
        checkOk()
        val dups = HashMap<Long, DupAcc>()
        for (a in graph.primitiveArrays) {
            val raw = a.byteSize.toLong()
            if (raw < MIN_SCAN_BYTES || isStringValue[a.objectIndex]) continue
            val length = (raw / a.primitiveType.byteSize).toInt()
            val bytes = sizeOf.model.primitiveArray(a.byteSize)
            // ponytail: 64-bit FNV hash identifies content; a collision merges two arrays (vanishingly rare)
            val hash = scan(a.readRecord(), a.primitiveType.ordinal * -0x61c8864680b583ebL + length)
            if (lastZero) arrays.getOrPut("zero" to a.arrayClassName) { ArrAcc() }.let { it.count++; it.bytes += bytes }
            else if (bytes >= MIN_DEDUP_BYTES) dups.getOrPut(hash) { DupAcc(a.arrayClassName, length, bytes) }.count++
        }

        val prefixes = HashMap<String, PrefixStat>()
        for ((s, acc) in strings) if (s.length >= PREFIX_LEN + 4) {
            val p = s.take(PREFIX_LEN)
            val old = prefixes[p]
            prefixes[p] = PrefixStat(p, (old?.count ?: 0) + acc.count, (old?.bytes ?: 0) + acc.count * acc.bytes)
        }

        return WasteReport(
            collections = collections.map { (name, a) ->
                CollectionStat(name, a.count, a.empty, a.emptyBytes, a.size, a.capacity, (a.capacity - a.size).coerceAtLeast(0) * refSize)
            }.sortedByDescending { it.unusedBytes },
            fillRatio = FILL_LABELS.mapIndexed { i, l -> Bucket(l, fill[i]) },
            sizes = buckets(sizes),
            arrays = arrays.map { (k, a) -> ArrayWaste(k.first, k.second, a.count, a.bytes) }.sortedByDescending { it.bytes }.take(top),
            duplicateArrays = topN(top, dups.values.asSequence().filter { it.count > 1 }) { (it.count - 1) * it.bytes }
                .map { DupArray(it.type, it.length, it.count, it.bytes, (it.count - 1) * it.bytes) },
            boxing = boxes.map { (name, a) -> BoxStat(name, a.count, a.bytes, a.inRange - a.distinct) }.sortedByDescending { it.bytes },
            nullFields = nullFieldClasses(),
            overhead = Overhead(data, header, padding),
            strings = StringStats(latin1, utf16, emptyStrings, longStrings, buckets(lengths),
                topN(top, prefixes.values.asSequence().filter { it.count > 1 }) { it.bytes }),
        )
    }

    private fun nullFieldClasses(): List<NullFieldClass> {
        fun wasted(a: NullAcc) = a.nulls.filter { it >= a.count * NULL_FIELD }.sum() * refSize
        return topN(NULL_FIELD_CLASSES, nulls.values.asSequence().filter { it.count >= MIN_NULL_INSTANCES && wasted(it) > 0 }, ::wasted)
            .map { a -> NullFieldClass(a.name, a.count, a.fields.mapIndexed { i, f -> FieldNull(f, 100.0 * a.nulls[i] / a.count) }) }
    }

    private var lastZero = false

    private inline fun hashOf(n: Int, seed: Long, value: (Int) -> Long): Long {
        var h = seed xor -0x340d631b7bdddcdbL // FNV-1a offset basis
        var any = 0L
        for (i in 0 until n) {
            val v = value(i)
            any = any or v
            h = (h xor v) * 0x100000001b3L
        }
        lastZero = any == 0L
        return h
    }

    private fun scan(r: PrimitiveArrayDumpRecord, seed: Long): Long = when (r) {
        is BooleanArrayDump -> hashOf(r.array.size, seed) { if (r.array[it]) 1L else 0L }
        is CharArrayDump -> hashOf(r.array.size, seed) { r.array[it].code.toLong() }
        is FloatArrayDump -> hashOf(r.array.size, seed) { r.array[it].toRawBits().toLong() }
        is DoubleArrayDump -> hashOf(r.array.size, seed) { r.array[it].toRawBits() }
        is ByteArrayDump -> hashOf(r.array.size, seed) { r.array[it].toLong() }
        is ShortArrayDump -> hashOf(r.array.size, seed) { r.array[it].toLong() }
        is IntArrayDump -> hashOf(r.array.size, seed) { r.array[it].toLong() }
        is LongArrayDump -> hashOf(r.array.size, seed) { r.array[it] }
    }
}
