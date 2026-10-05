package hprof

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Compact, complete histogram of one dump; input for comparing dumps (`--baseline`). */
@Serializable
data class Snapshot(
    val summary: Summary,
    val classes: List<ClassStat>,
    val packages: List<PackageStat>,
    /** Biggest collections by path signature (absent in snapshots from older versions). */
    val collections: List<CollectionAtPath> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

fun toSnapshot(r: HeapReport) = Snapshot(r.summary, r.histogram, r.packages, r.pathCollections)

fun toSnapshotJson(r: HeapReport): String = r.scrub(Json.encodeToString(toSnapshot(r)))

fun readSnapshot(file: File): Snapshot = json.decodeFromString(file.readText())
