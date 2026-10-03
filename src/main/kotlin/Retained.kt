package hprof

import kotlinx.serialization.Serializable

@Serializable
data class RetainedReport(
    /** ClassLoader → package → class, same "not dominated by the same class" rule as the class histogram. */
    val byLoader: TreeNode?,
    /** Static fields whose value is dominated by the class itself: memory freed if the field were cleared. */
    val staticFields: List<StaticFieldStat>,
    /** Who holds the instances of the biggest classes: class of the immediate dominator of each instance. */
    val dominators: List<DominatedClass>,
)

@Serializable
data class StaticFieldStat(val owner: String, val field: String, val valueClass: String, val retained: Long)

@Serializable
data class DominatedClass(val className: String, val instances: Long, val retained: Long, val by: List<DomStat>)

@Serializable
data class DomStat(val className: String, val count: Long, val retained: Long)

// ponytail: fixed fan-out per sunburst level and table sizes
private const val SUNBURST_WIDTH = 10
private const val DOMINATED_CLASSES = 10
private const val DOMINATORS_PER_CLASS = 10
const val GC_ROOT_LABEL = "<GC root>"

internal fun retainedViews(h: Heap, loaderLabels: List<String>, top: Int, msg: Messages): RetainedReport {
    val dom = h.dom!!
    val retained = h.retained!!
    val n = h.n

    // ---- loader → package → class ----
    val byLoader = HashMap<Int, HashMap<Int, Long>>()
    for (k in 1 until dom.order.size) {
        val v = dom.order[k]
        val p = dom.idom[v]
        if (p == n || h.clsOf[p] != h.clsOf[v]) byLoader.getOrPut(h.loaderOf[v]) { HashMap() }.merge(h.clsOf[v], retained[v], Long::plus)
    }
    fun <K> top(m: Map<K, Long>) = m.entries.sortedByDescending { it.value }.take(SUNBURST_WIDTH)
    val loaders = top(byLoader.mapValues { it.value.values.sum() }).map { (loader, total) ->
        val classes = byLoader[loader]!!
        val packages = classes.entries.groupBy({ packageOf(h.classNames[it.key], msg) }) { it }
        val pkgNodes = top(packages.mapValues { e -> e.value.sumOf { it.value } }).map { (pkg, pkgTotal) ->
            val leaves = packages[pkg]!!.sortedByDescending { it.value }.take(SUNBURST_WIDTH)
                .map { TreeNode(h.classNames[it.key], it.value) }
            TreeNode(pkg, pkgTotal, leaves)
        }
        TreeNode(loaderLabels[loader], total, pkgNodes)
    }
    val tree = TreeNode("heap", retained[n], loaders)

    // ---- static fields ----
    val statics = ArrayList<StaticFieldStat>()
    for (c in h.graph.classes) {
        val cIdx = c.objectIndex
        for (f in c.readStaticFields()) {
            val id = f.value.asNonNullObjectId ?: continue
            val target = h.graph.findObjectByIdOrNull(id) ?: continue
            val t = h.graph.indexOf(target)
            if (dom.idom[t] == cIdx) statics.add(StaticFieldStat(c.name, f.name, h.label(t), retained[t]))
        }
    }

    // ---- immediate dominators of the biggest classes ----
    val classAcc = h.classNames.indexOf("java.lang.Class")
    val retainedByAcc = HashMap<Int, Long>()
    for (k in 1 until dom.order.size) {
        val v = dom.order[k]
        val p = dom.idom[v]
        if (p == n || h.clsOf[p] != h.clsOf[v]) retainedByAcc.merge(h.clsOf[v], retained[v], Long::plus)
    }
    val targets = retainedByAcc.entries.filter { it.key != classAcc }.sortedByDescending { it.value }
        .take(DOMINATED_CLASSES).associate { it.key to it.value }
    class D { var count = 0L; var retained = 0L }
    val held = HashMap<Int, HashMap<String, D>>()
    val instances = HashMap<Int, Long>()
    for (k in 1 until dom.order.size) {
        val v = dom.order[k]
        val acc = h.clsOf[v]
        if (acc !in targets) continue
        instances.merge(acc, 1, Long::plus)
        val p = dom.idom[v]
        if (p != n && h.clsOf[p] == acc) continue // inside a same-class chain (linked list): counted at its head
        val d = held.getOrPut(acc) { HashMap() }.getOrPut(if (p == n) GC_ROOT_LABEL else h.label(p)) { D() }
        d.count++; d.retained += retained[v]
    }
    val dominated = targets.map { (acc, total) ->
        DominatedClass(h.classNames[acc], instances[acc] ?: 0, total,
            held[acc].orEmpty().entries.sortedByDescending { it.value.retained }.take(DOMINATORS_PER_CLASS)
                .map { DomStat(it.key, it.value.count, it.value.retained) })
    }
    return RetainedReport(tree, topN(top, statics.asSequence()) { it.retained }, dominated)
}
