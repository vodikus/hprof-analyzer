package hprof

import kotlinx.serialization.Serializable

/** Comparison of this dump with earlier snapshots (`--baseline`), oldest first, this dump last. */
@Serializable
data class DiffReport(
    val dumps: List<DumpPoint>,
    /** Shallow bytes per class. */
    val classes: List<Delta>,
    /** Shallow bytes per package. */
    val packages: List<Delta>,
    /** Elements of the collections reached by the same path (see [pathSignature]). */
    val collections: List<Delta>,
    /** Some dump was measured with another size model (e.g. a snapshot from a version with raw hprof sizes). */
    val mixedSizes: Boolean = false,
)

@Serializable
data class DumpPoint(val file: String, val timestamp: String, val totalShallow: Long, val reachableBytes: Long?, val objectCount: Int)

/**
 * [values] / [counts]: one per dump (null = absent in that dump). For classes and packages values are bytes and
 * counts are objects; for collections values are elements and counts are collections at that path.
 * [growing]: present in every dump and larger in each one than in the previous.
 */
@Serializable
data class Delta(val name: String, val values: List<Long?>, val counts: List<Long?>, val delta: Long, val growing: Boolean)

internal fun isGrowing(values: List<Long?>): Boolean =
    values.size >= 2 && values.all { it != null } && values.zipWithNext().all { (a, b) -> b!! > a!! }

/** [baselines] in any order; they are sorted by dump timestamp and [current] goes last. */
internal fun diff(current: Snapshot, baselines: List<Snapshot>, top: Int): DiffReport {
    val all = baselines.sortedBy { it.summary.timestamp } + current
    fun <T> deltas(rows: (Snapshot) -> List<T>, name: (T) -> String, value: (T) -> Long, count: (T) -> Long): List<Delta> {
        val maps = all.map { s -> rows(s).associateBy(name) }
        return maps.flatMap { it.keys }.toSet().map { key ->
            val values = maps.map { m -> m[key]?.let(value) }
            Delta(key, values, maps.map { m -> m[key]?.let(count) }, (values.last() ?: 0) - (values.first() ?: 0), isGrowing(values))
        }.sortedByDescending { kotlin.math.abs(it.delta) }.take(top)
    }
    return DiffReport(
        dumps = all.map { DumpPoint(it.summary.file, it.summary.timestamp, it.summary.totalShallow, it.summary.reachableBytes, it.summary.objectCount) },
        classes = deltas({ it.classes }, { it.name }, { it.shallow }, { it.count }),
        packages = deltas({ it.packages }, { it.name }, { it.shallow }, { it.count }),
        collections = deltas({ it.collections }, { it.signature }, { it.size }, { it.count.toLong() }),
        mixedSizes = all.map { it.summary.refSize }.distinct().size > 1,
    )
}
