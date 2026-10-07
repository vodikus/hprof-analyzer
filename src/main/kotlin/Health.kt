package hprof

import kotlinx.serialization.Serializable
import java.util.Locale

/** Automatic diagnosis: [key] is the i18n suffix (`health.<key>`), [anchor] the report section it points to. */
@Serializable
data class HealthFinding(
    val severity: String, val key: String, val args: List<String>, val anchor: String,
    /** Key in [DEFAULT_THRESHOLDS] of the limit this finding crossed; null for rules without a threshold. */
    val threshold: String? = null,
)

/** Finding keys whose threshold has another name; any other finding key that is a threshold key uses itself. */
private val THRESHOLD_OF = mapOf("offHeapLarge" to "offHeapRatio", "poolProliferation" to "poolsPerClass")

const val CRITICAL = "critical"
const val WARNING = "warning"
const val INFO = "info"

/**
 * Rule thresholds, overridable with `--threshold key=value`. Ratios are fractions (0.30 = 30%).
 * Defaults come from common practice; tune them per application.
 */
val DEFAULT_THRESHOLDS: Map<String, Double> = linkedMapOf(
    "bigDominator" to 0.30,        // one object retains more than this share of the reachable heap
    "lowFill" to 0.25,             // big collections use less than this share of their capacity
    "minFillInstances" to 1000.0,  // ...counting only collection types with at least this many instances
    "finalizerQueue" to 10_000.0,
    "poolQueue" to 10_000.0,       // tasks waiting in one pool
    "objectMappers" to 10.0,
    "heapGrowth" to 0.20,          // heap growth between the first and the last dump
    "webappLoaders" to 2.0,
    "dupStrings" to 0.10,          // duplicate strings' share of the heap
    "emptyCollections" to 0.05,    // empty collections with an allocated array, share of the heap
    "poolsPerClass" to 10.0,
    "offHeapRatio" to 1.0,         // direct buffers above this multiple of the reachable heap
    "duplicateClasses" to 1.0,     // class names defined by more than one ClassLoader
    "softRefs" to 0.10,            // bytes kept alive only by SoftReferences, share of the reachable heap
    "openFiles" to 500.0,          // open RandomAccessFile + ZipFile
    "sockets" to 1000.0,
    "bigStaticCollection" to 100_000.0, // elements of one collection held by a static field
    "unreachable" to 0.10,         // unreachable share of the dump (taken without :live)
    "libraryVersions" to 1.0,      // libraries present in more than one version
)

private val WEBAPP_LOADER_NAMES = listOf("WebappClassLoader", "org.eclipse.jetty.webapp.WebAppClassLoader")
private val FILE_CLASSES = setOf("java.io.RandomAccessFile", "java.util.zip.ZipFile")
private val SOCKET_CLASSES = setOf("java.net.Socket", "sun.nio.ch.SocketChannelImpl")

private fun pct(part: Double, whole: Double) = String.format(Locale.ROOT, "%.0f", if (whole > 0) 100 * part / whole else 0.0)

internal fun health(r: HeapReport, t: Map<String, Double> = DEFAULT_THRESHOLDS): List<HealthFinding> = buildList {
    fun add(severity: String, key: String, anchor: String, vararg args: Any) =
        add(HealthFinding(severity, key, args.map(Any::toString), anchor, THRESHOLD_OF[key] ?: key.takeIf { it in DEFAULT_THRESHOLDS }))
    fun th(key: String) = t[key] ?: DEFAULT_THRESHOLDS.getValue(key)
    val s = r.summary
    val heap = (s.reachableBytes ?: s.totalShallow).toDouble()
    val ins = r.insights

    if (ins?.context?.afterOom == true) add(CRITICAL, "afterOom", "summary")
    r.retainedObjects.firstOrNull()?.let { o ->
        if (s.reachableBytes != null && o.retained > heap * th("bigDominator")) add(CRITICAL, "bigDominator", "objects", pct(o.retained.toDouble(), heap), o.className)
    }
    r.references?.finalizerQueue?.let { if (it > th("finalizerQueue")) add(CRITICAL, "finalizerQueue", "references", it) }
    r.concurrency?.pools?.filter { (it.queued ?: 0) > th("poolQueue") }?.forEach { add(CRITICAL, "poolQueue", "concurrency", it.className, it.queued!!) }
    r.offHeap?.direct?.ownerBytes?.let { direct ->
        if (s.reachableBytes != null && direct > s.reachableBytes * th("offHeapRatio")) add(WARNING, "offHeapLarge", "offheap", bytes(direct), bytes(s.reachableBytes))
    }
    r.concurrency?.poolsByClass?.filter { it.count >= th("poolsPerClass") }?.forEach { add(WARNING, "poolProliferation", "concurrency", it.count, it.name) }
    r.concurrency?.unboundedPools?.let { if (it > 0) add(INFO, "unboundedPools", "concurrency", it) }
    r.diff?.let { d ->
        // with only two dumps any growth looks monotonic: require 3+
        val growing = d.collections.count { it.growing }
        if (d.dumps.size >= 3 && growing > 0) add(WARNING, "growingCollections", "diff", growing, d.dumps.size)
        fun heap(p: DumpPoint) = (p.reachableBytes ?: p.totalShallow).toDouble()
        val first = heap(d.dumps.first())
        val last = heap(d.dumps.last())
        if (first > 0 && last > first * (1 + th("heapGrowth"))) add(INFO, "heapGrowth", "diff", pct(last - first, first), d.dumps.size)
    }
    fun fwMetric(section: String, key: String) = r.inspections.firstOrNull { it.key == section }?.metrics?.firstOrNull { it.key == key }?.value
    fwMetric("fw.sessions", "fw.m.expired")?.let { if (it > 0) add(WARNING, "expiredSessions", "fw-fw.sessions", it) }
    fwMetric("fw.jackson", "fw.m.objectMappers")?.let { if (it >= th("objectMappers")) add(INFO, "objectMappers", "fw-fw.jackson", it) }
    r.leakSuspects?.groups?.count { it.kind == "application" }?.let { if (it > 0) add(WARNING, "leakSuspects", "leaks", it) }
    r.waste?.collections?.filter { it.count >= th("minFillInstances") && it.capacity > 0 }?.let { big ->
        val size = big.sumOf { it.size }.toDouble()
        val capacity = big.sumOf { it.capacity }.toDouble()
        if (capacity > 0 && size / capacity < th("lowFill")) add(WARNING, "lowFill", "waste", pct(size, capacity))
    }
    val webapp = r.classLoaders.count { l -> WEBAPP_LOADER_NAMES.any { it in l.className } }
    if (webapp >= th("webappLoaders")) add(WARNING, "webappLoaders", "loaders", webapp)
    val dupStrings = (r.duplicateStringsTotal?.wasted ?: r.duplicateStrings.sumOf { it.wasted }).toDouble()
    // every duplicate string (not only the top N) against the reachable heap, like the other heap-share rules
    if (dupStrings > heap * th("dupStrings")) add(WARNING, "dupStrings", "strings", pct(dupStrings, heap))
    r.proxies?.groups?.count { it.suspect }?.let { if (it > 0) add(WARNING, "proxies", "proxies", it) }
    // plugin isolation (one ClassLoader per plugin) repeats classes by design: only a version conflict makes it a warning
    val versionConflict = ins?.libraries?.any { it.versions.size > 1 } == true
    ins?.duplicateClasses?.size?.let { if (it >= th("duplicateClasses")) add(if (versionConflict) WARNING else INFO, "duplicateClasses", "loaders", it) }
    ins?.softOnlyBytes?.let { if (s.reachableBytes != null && it > heap * th("softRefs")) add(WARNING, "softRefs", "references", bytes(it), pct(it.toDouble(), heap)) }
    ins?.staticCollections?.firstOrNull()?.let { c ->
        if (c.size >= th("bigStaticCollection")) add(WARNING, "bigStaticCollection", "retained", "${c.owner} (${c.type})", c.size)
    }
    ins?.libraries?.count { it.versions.size > 1 }?.let { if (it >= th("libraryVersions")) add(WARNING, "libraryVersions", "libraries", it) }
    r.offHeap?.resources?.let { res ->
        fun open(names: Set<String>) = res.filter { it.className in names }.sumOf { it.open ?: it.count }
        open(FILE_CLASSES).let { if (it > th("openFiles")) add(INFO, "openFiles", "offheap", it) }
        open(SOCKET_CLASSES).let { if (it > th("sockets")) add(INFO, "sockets", "offheap", it) }
    }
    r.waste?.collections?.sumOf { it.emptyBytes }?.toDouble()?.let {
        if (it > s.totalShallow * th("emptyCollections")) add(INFO, "emptyCollections", "waste", pct(it, s.totalShallow.toDouble()))
    }
    s.reachableBytes?.let { live ->
        val unreachable = (s.totalShallow - live).toDouble()
        if (s.totalShallow > 0 && unreachable > s.totalShallow * th("unreachable")) add(INFO, "unreachable", "summary", pct(unreachable, s.totalShallow.toDouble()), bytes(unreachable.toLong()))
    }
}.sortedBy { listOf(CRITICAL, WARNING, INFO).indexOf(it.severity) }

/** `a=1,b=0.5` merged over [DEFAULT_THRESHOLDS]; error text (unknown key or bad number) on failure. */
internal fun parseThresholds(spec: String, base: Map<String, Double> = DEFAULT_THRESHOLDS): Result<Map<String, Double>> = runCatching {
    base + spec.split(',').map(String::trim).filter(String::isNotEmpty).associate { kv ->
        val key = kv.substringBefore('=').trim()
        require(key in DEFAULT_THRESHOLDS) { key }
        key to (kv.substringAfter('=', "").trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException(kv))
    }
}
