package hprof

import kotlinx.serialization.Serializable

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

/** BFS tree from the virtual root [root] over the CSR graph: parent per node, -1 if unreachable (parent[root] = root). */
internal fun bfsParents(n: Int, root: Int, offsets: IntArray, targets: IntArray): IntArray {
    val parent = IntArray(n) { -1 }
    val queue = IntArray(n)
    parent[root] = root
    queue[0] = root
    var head = 0
    var tail = 1
    while (head < tail) {
        val v = queue[head++]
        for (e in offsets[v] until offsets[v + 1]) {
            val w = targets[e]
            if (parent[w] == -1) { parent[w] = v; queue[tail++] = w }
        }
    }
    return parent
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
