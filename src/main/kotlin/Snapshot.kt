package hprof

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Compact, complete histogram of one dump; input for comparing dumps (`--baseline`). */
@Serializable
data class Snapshot(val summary: Summary, val classes: List<ClassStat>, val packages: List<PackageStat>)

fun toSnapshotJson(r: HeapReport): String = Json.encodeToString(Snapshot(r.summary, r.histogram, r.packages))
