package hprof

import kotlinx.serialization.Serializable
import java.util.Locale

/** Automatic diagnosis: [key] is the i18n suffix (`health.<key>`), [anchor] the report section it points to. */
@Serializable
data class HealthFinding(val severity: String, val key: String, val args: List<String>, val anchor: String)

const val CRITICAL = "critical"
const val WARNING = "warning"
const val INFO = "info"

// ponytail: fixed thresholds from common practice; CLI flags if real dumps disagree
private const val BIG_DOMINATOR = 0.30
private const val LOW_FILL = 0.25
private const val MIN_FILL_INSTANCES = 1000
private const val FINALIZER_QUEUE = 10_000L
private const val POOL_QUEUE = 10_000
private const val WEBAPP_LOADERS = 2
private const val DUP_STRINGS = 0.10
private const val EMPTY_COLLECTIONS = 0.05

private val WEBAPP_LOADER_NAMES = listOf("WebappClassLoader", "org.eclipse.jetty.webapp.WebAppClassLoader")

private fun pct(part: Double, whole: Double) = String.format(Locale.ROOT, "%.0f", if (whole > 0) 100 * part / whole else 0.0)

internal fun health(r: HeapReport): List<HealthFinding> = buildList {
    fun add(severity: String, key: String, anchor: String, vararg args: Any) =
        add(HealthFinding(severity, key, args.map(Any::toString), anchor))
    val s = r.summary
    val heap = (s.reachableBytes ?: s.totalShallow).toDouble()

    r.retainedObjects.firstOrNull()?.let { o ->
        if (s.reachableBytes != null && o.retained > heap * BIG_DOMINATOR) add(CRITICAL, "bigDominator", "objects", pct(o.retained.toDouble(), heap), o.className)
    }
    r.references?.finalizerQueue?.let { if (it > FINALIZER_QUEUE) add(CRITICAL, "finalizerQueue", "references", it) }
    r.concurrency?.pools?.filter { (it.queued ?: 0) > POOL_QUEUE }?.forEach { add(CRITICAL, "poolQueue", "concurrency", it.className, it.queued!!) }
    r.leakSuspects?.groups?.count { it.kind == "application" }?.let { if (it > 0) add(WARNING, "leakSuspects", "leaks", it) }
    r.waste?.collections?.filter { it.count >= MIN_FILL_INSTANCES && it.capacity > 0 }?.let { big ->
        val size = big.sumOf { it.size }.toDouble()
        val capacity = big.sumOf { it.capacity }.toDouble()
        if (capacity > 0 && size / capacity < LOW_FILL) add(WARNING, "lowFill", "waste", pct(size, capacity))
    }
    val webapp = r.classLoaders.count { l -> WEBAPP_LOADER_NAMES.any { it in l.className } }
    if (webapp >= WEBAPP_LOADERS) add(WARNING, "webappLoaders", "loaders", webapp)
    val dupStrings = r.duplicateStrings.sumOf { it.wasted }.toDouble()
    if (dupStrings > s.totalShallow * DUP_STRINGS) add(WARNING, "dupStrings", "strings", pct(dupStrings, s.totalShallow.toDouble()))
    r.proxies?.groups?.count { it.suspect }?.let { if (it > 0) add(WARNING, "proxies", "proxies", it) }
    r.waste?.collections?.sumOf { it.emptyBytes }?.toDouble()?.let {
        if (it > s.totalShallow * EMPTY_COLLECTIONS) add(INFO, "emptyCollections", "waste", pct(it, s.totalShallow.toDouble()))
    }
}.sortedBy { listOf(CRITICAL, WARNING, INFO).indexOf(it.severity) }
