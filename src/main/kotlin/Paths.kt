package hprof

import kotlinx.serialization.Serializable
import shark.HeapObject

/**
 * Shortest paths from the GC roots to every instance of a class, merged by class (like MAT's "merge shortest paths").
 * Node names are "<hops to the instance>|<label>", so the same class at different distances stays a separate node
 * and the graph is acyclic (a Sankey requirement).
 */
@Serializable
data class MergedPaths(val className: String, val instances: Int, val sampled: Int, val nodes: List<String>, val links: List<PathLink>)

@Serializable
data class PathLink(val source: String, val target: String, val value: Int)

// ponytail: fixed caps keep the Sankey readable and the walk bounded; flags if needed
private const val MAX_INSTANCES = 10_000
private const val MAX_HOPS = 8
private const val MAX_LINKS = 40
private const val MIN_SHARE = 0.02

/** [parent]: BFS tree parent per node, -1 if unreachable (parent[root] = root). [depthCounts][d]: nodes d hops from the roots. */
internal class Bfs(val parent: IntArray, val depthCounts: LongArray)

/** BFS from the virtual root [root] over the CSR graph. */
internal fun bfs(n: Int, root: Int, offsets: IntArray, targets: IntArray): Bfs {
    val parent = IntArray(n) { -1 }
    val queue = IntArray(n)
    val depth = IntArray(n)
    parent[root] = root
    queue[0] = root
    var head = 0
    var tail = 1
    var maxDepth = 0
    while (head < tail) {
        val v = queue[head++]
        for (e in offsets[v] until offsets[v + 1]) {
            val w = targets[e]
            if (parent[w] == -1) {
                parent[w] = v; depth[w] = depth[v] + 1; queue[tail++] = w
                if (depth[w] > maxDepth) maxDepth = depth[w]
            }
        }
    }
    val counts = LongArray(maxDepth + 1)
    for (i in 1 until tail) counts[depth[queue[i]]]++ // depth 0 = virtual root, not counted
    return Bfs(parent, counts)
}

internal fun mergedPaths(h: Heap, parent: IntArray, rootTypes: Map<Int, String>, accs: List<Int>): List<MergedPaths> = accs.map { acc ->
    val links = HashMap<Pair<String, String>, Int>()
    var instances = 0
    var sampled = 0
    for (v in 0 until h.n) {
        if (h.clsOf[v] != acc) continue
        instances++
        if (sampled >= MAX_INSTANCES || parent[v] < 0) continue
        sampled++
        var cur = v
        var d = 0
        var name = "0|" + h.label(v)
        while (true) {
            val p = parent[cur]
            val cut = p != h.n && d + 1 >= MAX_HOPS
            val pName = "${d + 1}|" + when {
                p == h.n -> "[${rootTypes[cur] ?: "GC root"}]"
                cut -> "…"
                else -> h.label(p)
            }
            links.merge(pName to name, 1, Int::plus)
            if (p == h.n || cut) break
            cur = p; name = pName; d++
        }
    }
    val min = maxOf(1, (sampled * MIN_SHARE).toInt())
    val kept = links.entries.filter { it.value >= min }.sortedByDescending { it.value }.take(MAX_LINKS)
    MergedPaths(
        className = h.classNames[acc], instances = instances, sampled = sampled,
        nodes = kept.flatMap { listOf(it.key.first, it.key.second) }.distinct(),
        links = kept.map { PathLink(it.key.first, it.key.second, it.value) },
    )
}

/** Collections grouped by the path that reaches them: stable across dumps, unlike object ids. */
@Serializable
data class CollectionAtPath(val signature: String, val count: Int, val size: Long)

private const val MAX_SIGNATURE_HOPS = 12

/**
 * "[Root] Owner.field → Owner.field → … (Collection)" for the BFS path from a GC root to [v]. Array slots are written
 * as "Type[]" (indexes are not stable). Paths longer than [MAX_SIGNATURE_HOPS] keep their last hops after "…".
 */
internal fun pathSignature(h: Heap, parent: IntArray, rootTypes: Map<Int, String>, v: Int): String? {
    val parts = ArrayList<String>()
    var cur = v
    while (parts.size < MAX_SIGNATURE_HOPS) {
        val p = parent[cur]
        if (p < 0) return null // unreachable
        if (p == h.n) { parts.add("[${rootTypes[cur] ?: "GC root"}] ${h.label(cur)}"); break }
        parts.add(edgeName(h, p, h.ids[cur]))
        cur = p
    }
    if (parts.size == MAX_SIGNATURE_HOPS && parent[cur] != h.n) parts.add("…")
    return parts.reversed().joinToString(" → ") + " (${h.label(v)})"
}

/** How object [p] references the object with id [childId]: "Class.field", "Class.static field" or "Type[]". */
private fun edgeName(h: Heap, p: Int, childId: Long): String = when (val o = h.graph.findObjectById(h.ids[p])) {
    is HeapObject.HeapInstance -> o.readFields().firstOrNull { it.value.asObjectId == childId }
        ?.let { "${o.instanceClassSimpleName}.${it.name}" } ?: o.instanceClassSimpleName
    is HeapObject.HeapClass -> o.readStaticFields().firstOrNull { it.value.asObjectId == childId }
        ?.let { "${o.simpleName}.${it.name}" } ?: o.simpleName
    is HeapObject.HeapObjectArray -> o.arrayClassName.substringAfterLast('.')
    is HeapObject.HeapPrimitiveArray -> o.arrayClassName
}

/** The [big] collections (index, size) grouped by path signature. */
internal fun collectionsByPath(h: Heap, parent: IntArray, rootTypes: Map<Int, String>, big: List<Pair<Int, Long>>): List<CollectionAtPath> =
    big.mapNotNull { (idx, size) -> pathSignature(h, parent, rootTypes, idx)?.let { it to size } }
        .groupBy({ it.first }) { it.second }
        .map { (sig, sizes) -> CollectionAtPath(sig, sizes.size, sizes.sum()) }
        .sortedByDescending { it.size }
