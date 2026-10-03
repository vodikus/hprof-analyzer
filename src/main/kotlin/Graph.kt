package hprof

import kotlinx.serialization.Serializable

/** Shape of the reference graph. Instance → class edges (added so classes stay alive) are left out. */
@Serializable
data class GraphReport(
    /** Most referenced objects ("hubs"). */
    val fanIn: List<ObjectDegree>,
    /** Objects with the most outgoing references. */
    val fanOut: List<ObjectDegree>,
    /** Objects by distance (hops) from the GC roots along the shortest path. */
    val depths: List<Bucket>,
    val maxDepth: Int,
    /** References between classes (A has a field/element pointing to B), most frequent first. */
    val classEdges: List<ClassEdge>,
)

@Serializable
data class ObjectDegree(val id: String, val className: String, val degree: Int, val retained: Long?)

@Serializable
data class ClassEdge(val source: String, val target: String, val count: Long)

// ponytail: fixed caps; the class-edge map boxes a Long per edge lookup (seconds on 100M+ edges), a primitive map if that hurts
private const val CLASS_EDGES = 50
private const val EXACT_DEPTHS = 20

/** Depth buckets: 1..20 one each, then log2 ranges (21-32, 33-64, ...). */
internal fun depthBuckets(counts: LongArray): List<Bucket> {
    val out = LinkedHashMap<String, Long>()
    for (d in 1 until counts.size) {
        if (counts[d] == 0L) continue
        val label = if (d <= EXACT_DEPTHS) "$d" else {
            val hi = Integer.highestOneBit(d - 1) shl 1
            "${maxOf(EXACT_DEPTHS, hi / 2) + 1}-$hi"
        }
        out.merge(label, counts[d], Long::plus)
    }
    return out.map { Bucket(it.key, it.value) }
}

internal fun graphReport(h: Heap, offsets: IntArray, targets: IntArray, depthCounts: LongArray, top: Int): GraphReport {
    val n = h.n
    val classAcc = h.classNames.indexOf("java.lang.Class")
    // only instances get the artificial edge to their class (arrays and class objects do not)
    val isInstanceAcc = BooleanArray(h.classNames.size) { it != classAcc && !h.classNames[it].endsWith("]") }
    val fanIn = IntArray(n)
    class Counter { var count = 0L }
    val edges = HashMap<Long, Counter>()
    for (v in 0 until n) for (e in offsets[v] until offsets[v + 1]) {
        val t = targets[e]
        if (h.clsOf[t] == classAcc) continue
        fanIn[t]++
        edges.getOrPut((h.clsOf[v].toLong() shl 32) or h.clsOf[t].toLong()) { Counter() }.count++
    }
    fun fanOut(v: Int) = offsets[v + 1] - offsets[v] - (if (isInstanceAcc[h.clsOf[v]]) 1 else 0)
    fun degrees(key: (Int) -> Int) = topN(top, (0 until n).asSequence().filter { key(it) > 1 }) { key(it).toLong() }
        .map { ObjectDegree(hex(h.ids[it]), h.label(it), key(it), h.retained?.get(it)) }
    return GraphReport(
        fanIn = degrees { fanIn[it] },
        fanOut = degrees(::fanOut),
        depths = depthBuckets(depthCounts),
        maxDepth = depthCounts.size - 1,
        classEdges = edges.entries.sortedByDescending { it.value.count }.take(CLASS_EDGES).map {
            ClassEdge(h.classNames[(it.key ushr 32).toInt()], h.classNames[(it.key and 0xffffffffL).toInt()], it.value.count)
        },
    )
}
