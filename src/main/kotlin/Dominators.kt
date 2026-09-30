package hprof

/**
 * Dominator tree of a graph in CSR form (edges of node v are targets[offsets[v] until offsets[v + 1]]).
 * Semi-NCA algorithm (Lengauer-Tarjan semidominators + nearest common ancestor), iterative and
 * array-based so it scales to tens of millions of heap objects.
 *
 * Needed because Shark 2.14 keeps its own dominator tree internal.
 */
class Dominators(
    /** Immediate dominator per node; -1 for root and unreachable nodes. */
    val idom: IntArray,
    /** Reachable nodes in DFS preorder (root first): every dominator comes before the nodes it dominates. */
    val order: IntArray,
)

fun dominators(n: Int, root: Int, offsets: IntArray, targets: IntArray): Dominators {
    val edgeCount = offsets[n]

    // Reverse CSR (predecessors).
    val predOff = IntArray(n + 1)
    for (e in 0 until edgeCount) predOff[targets[e] + 1]++
    for (i in 0 until n) predOff[i + 1] += predOff[i]
    val preds = IntArray(edgeCount)
    val fill = predOff.copyOf(n)
    for (v in 0 until n) for (e in offsets[v] until offsets[v + 1]) preds[fill[targets[e]]++] = v

    // Iterative DFS: dfn = preorder number, vertex = inverse, parent in dfn space.
    val dfn = IntArray(n) { -1 }
    val vertex = IntArray(n)
    val parent = IntArray(n)
    val stack = IntArray(n)
    val edgePos = IntArray(n)
    var count = 1
    dfn[root] = 0; vertex[0] = root; parent[0] = -1
    stack[0] = root; edgePos[root] = offsets[root]
    var sp = 1
    while (sp > 0) {
        val v = stack[sp - 1]
        if (edgePos[v] < offsets[v + 1]) {
            val w = targets[edgePos[v]++]
            if (dfn[w] == -1) {
                dfn[w] = count; vertex[count] = w; parent[count] = dfn[v]; count++
                edgePos[w] = offsets[w]
                stack[sp++] = w
            }
        } else sp--
    }

    // Semidominators, all indices in dfn space.
    val semi = IntArray(count) { it }
    val label = IntArray(count) { it }
    val anc = IntArray(count) { -1 }
    val path = IntArray(count)
    fun eval(v: Int): Int {
        if (anc[v] == -1) return v
        var top = 0
        var x = v
        while (anc[anc[x]] != -1) { path[top++] = x; x = anc[x] }
        while (top > 0) {
            x = path[--top]
            val a = anc[x]
            if (semi[label[a]] < semi[label[x]]) label[x] = label[a]
            anc[x] = anc[a]
        }
        return label[v]
    }
    for (w in count - 1 downTo 1) {
        val vw = vertex[w]
        for (e in predOff[vw] until predOff[vw + 1]) {
            val v = dfn[preds[e]]
            if (v == -1) continue
            val s = semi[eval(v)]
            if (s < semi[w]) semi[w] = s
        }
        anc[w] = parent[w]
    }

    // idom = nearest common ancestor of parent and semidominator.
    val idomDfn = IntArray(count)
    val idom = IntArray(n) { -1 }
    for (w in 1 until count) {
        var d = parent[w]
        while (d > semi[w]) d = idomDfn[d]
        idomDfn[w] = d
        idom[vertex[w]] = vertex[d]
    }
    return Dominators(idom, vertex.copyOf(count))
}
