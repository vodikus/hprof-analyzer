package hprof

import kotlinx.serialization.Serializable
import shark.HeapObject.HeapInstance
import shark.HeapValue

/** One framework/technology inspector's output: headline numbers plus tables. Texts are i18n keys. */
@Serializable
data class FwSection(val key: String, val metrics: List<FwMetric>, val tables: List<FwTable>)

@Serializable
data class FwMetric(val key: String, val value: Long, val bytes: Boolean = false)

/**
 * [rows]: cells as text; numeric columns hold plain numbers (formatted by the renderer from [FwColumn.type]).
 * [collapsed]: secondary data, rendered closed.
 */
@Serializable
data class FwTable(val title: String, val columns: List<FwColumn>, val rows: List<List<String?>>, val collapsed: Boolean = false)

/** [type]: text, num or bytes. */
@Serializable
data class FwColumn(val key: String, val type: String = "text")

/** Watched base classes per inspector; instances of subclasses count too. */
private val WATCHED = listOf(
    "session" to "org.apache.catalina.session.StandardSession",
    "jettySession" to "org.eclipse.jetty.server.session.Session",
    "jettySession" to "org.eclipse.jetty.session.ManagedSession",
    "hibernate" to "org.hibernate.engine.internal.StatefulPersistenceContext",
    "spring" to "org.springframework.beans.factory.support.DefaultListableBeanFactory",
    "hikari" to "com.zaxxer.hikari.pool.HikariPool",
    "dbcp" to "org.apache.commons.dbcp2.BasicDataSource",
    "caffeine" to "com.github.benmanes.caffeine.cache.BoundedLocalCache",
    "caffeine" to "com.github.benmanes.caffeine.cache.UnboundedLocalCache",
    "guava" to "com.google.common.cache.LocalCache",
    "ehcache" to "org.ehcache.core.Ehcache",
    "jackson" to "com.fasterxml.jackson.databind.ObjectMapper",
    "throwable" to "java.lang.Throwable",
)

// ponytail: fixed caps
private const val MAX_WATCHED = 100_000
private const val MIN_STATIC_MAP = 100
private const val MAX_MESSAGE = 120

private val JDBC_PREFIXES = listOf(
    "org.postgresql.", "com.mysql.", "org.mariadb.", "oracle.jdbc.", "com.microsoft.sqlserver.", "org.h2.", "org.hsqldb.",
    "com.zaxxer.hikari.pool.", "org.apache.commons.dbcp2.",
)

/** GC roots that belong to a running thread: an exception reached from them is in use, not a VM/library constant. */
private val THREAD_ROOTS = setOf("JavaFrame", "ThreadObject", "NativeStack", "ThreadBlock", "JniLocal")

// ponytail: pre-allocated = shortest path goes through a static field or starts at a non-thread root (JVM's OOMs,
// "/ by zero", H2's DbException constants). An application exception cached in a static field lands here too.
internal fun isPreallocated(h: Heap, parent: IntArray, rootTypes: Map<Int, String>, v: Int): Boolean {
    val classAcc = h.classNames.indexOf("java.lang.Class")
    var cur = v
    var hops = 0
    while (hops++ < 10_000) {
        val p = parent[cur]
        if (p < 0) return false // unreachable: garbage, not a constant
        if (p == h.n) return rootTypes[cur] !in THREAD_ROOTS
        if (h.clsOf[p] == classAcc) return true
        cur = p
    }
    return false
}

private val MAP_TYPES = setOf("java.util.HashMap", "java.util.LinkedHashMap", "java.util.concurrent.ConcurrentHashMap", "java.util.Hashtable")

/** Entries of a HashMap / LinkedHashMap / Hashtable / ConcurrentHashMap / Properties (JDK 8+): walks `table` buckets. */
internal fun mapEntries(map: HeapInstance): Sequence<Pair<HeapValue, HeapValue>> = sequence {
    map.refField("map")?.asInstance?.let { yieldAll(mapEntries(it)); return@sequence } // JDK 9+ Properties wraps a CHM
    val table = map.refField("table")?.asObjectArray ?: return@sequence
    for (bucket in table.readElements()) {
        var e = bucket.asObject?.asInstance
        var hops = 0
        while (e != null && hops++ < 10_000) { // bound: a corrupt dump could loop
            val k = e.field("key")
            val v = e.field("val") ?: e.field("value")
            if (k != null && v != null) yield(k to v)
            e = e.refField("next")?.asInstance
        }
    }
}

/** HashMap.size / Hashtable.count (int), ConcurrentHashMap.baseCount (long, approximate under contention). */
private fun HeapInstance.mapSize(): Int? = (field("size") ?: field("baseCount") ?: field("count"))?.let { it.asInt ?: it.asLong?.toInt() }

internal class FrameworkCollector : Collector() {
    private val kindByClass = HashMap<Long, String>()
    private val ids = HashMap<String, MutableList<Long>>()

    fun instance(obj: HeapInstance) = safe {
        val kind = kindByClass.getOrPut(obj.instanceClassId) { WATCHED.firstOrNull { obj instanceOf it.second }?.first ?: "" }
        if (kind.isEmpty()) return@safe
        val list = ids.getOrPut(kind) { ArrayList() }
        if (list.size < MAX_WATCHED) list.add(obj.objectId)
    }

    /** Object ids per inspector kind; rethrows a failure of the main-pass hook. */
    fun watched(): Map<String, List<Long>> { checkOk(); return ids }
}

/** Runs every inspector whose classes are in the dump; [guard] isolates failures per inspector. */
internal fun inspectFrameworks(
    c: FrameworkCollector, h: Heap, parent: IntArray, rootTypes: Map<Int, String>, classCounts: Map<String, Long>,
    dumpMillis: Long, top: Int, guard: (String, () -> FwSection?) -> FwSection?,
): List<FwSection> {
    val graph = h.graph
    val watched = c.watched()
    fun instances(kind: String) = watched[kind].orEmpty().mapNotNull { graph.findObjectByIdOrNull(it)?.asInstance }
    fun retained(o: HeapInstance?) = o?.let { h.retained?.get(graph.indexOf(it)) }
    fun retainedOf(v: HeapValue?) = v?.asObject?.let { h.retained?.get(graph.indexOf(it)) }
    val out = ArrayList<FwSection?>()

    out += guard("fw.sessions") { sessions(instances("session"), instances("jettySession"), ::retained, ::retainedOf, dumpMillis, top) }
    out += guard("fw.hibernate") {
        val ctx = instances("hibernate").ifEmpty { return@guard null }
        val rows = ctx.map { p -> Triple(hex(p.objectId), p.refField("entitiesByKey")?.asInstance?.mapSize(), retained(p)) }
        FwSection("fw.hibernate",
            listOf(FwMetric("fw.m.contexts", ctx.size.toLong()), FwMetric("fw.m.entities", rows.sumOf { (it.second ?: 0).toLong() })),
            listOf(FwTable("fw.t.contexts", listOf(FwColumn("col.id"), FwColumn("fw.c.entities", "num"), FwColumn("col.retained", "bytes")),
                rows.sortedByDescending { it.second ?: 0 }.take(top).map { listOf(it.first, it.second?.toString(), it.third?.toString()) })))
    }
    out += guard("fw.spring") {
        val factories = instances("spring").ifEmpty { return@guard null }
        val beans = factories.flatMap { f ->
            f.refField("singletonObjects")?.asInstance?.let { m -> mapEntries(m).toList() }.orEmpty()
        }.map { (k, v) -> Triple(k.readAsJavaString() ?: "?", v.asObject?.className() ?: "?", retainedOf(v)) }
        FwSection("fw.spring",
            listOf(FwMetric("fw.m.factories", factories.size.toLong()), FwMetric("fw.m.singletons", beans.size.toLong())),
            listOf(FwTable("fw.t.beans", listOf(FwColumn("fw.c.bean"), FwColumn("col.class"), FwColumn("col.retained", "bytes")),
                beans.sortedByDescending { it.third ?: 0 }.take(top).map { listOf(it.first, it.second, it.third?.toString()) })))
    }
    out += guard("fw.jdbc") { jdbc(instances("hikari"), instances("dbcp"), classCounts) }
    out += guard("fw.caches") { caches(h, instances("caffeine"), instances("guava"), instances("ehcache"), ::retained, top) }
    out += guard("fw.jackson") {
        val mappers = instances("jackson").ifEmpty { return@guard null }
        FwSection("fw.jackson", listOf(FwMetric("fw.m.objectMappers", mappers.size.toLong()),
            FwMetric("fw.m.retained", mappers.sumOf { retained(it) ?: 0 }, bytes = true)), emptyList())
    }
    out += guard("fw.throwables") {
        val all = instances("throwable").ifEmpty { return@guard null }
        class T { var count = 0L; var retained = 0L }
        val groups = HashMap<Pair<String, String>, T>()
        val pre = HashMap<Pair<String, String>, T>()
        for (t in all) {
            val message = t["java.lang.Throwable", "detailMessage"]?.value?.readAsJavaString()?.take(MAX_MESSAGE) ?: ""
            val target = if (isPreallocated(h, parent, rootTypes, graph.indexOf(t))) pre else groups
            target.getOrPut(t.instanceClassName to message) { T() }.let { it.count++; it.retained += retained(t) ?: 0 }
        }
        val columns = listOf(FwColumn("col.class"), FwColumn("fw.c.message"), FwColumn("col.count", "num"), FwColumn("col.retained", "bytes"))
        fun rows(m: Map<Pair<String, String>, T>) = m.entries.sortedByDescending { it.value.retained }.take(top)
            .map { listOf(it.key.first, it.key.second, it.value.count.toString(), it.value.retained.toString()) }
        FwSection("fw.throwables",
            listOf(FwMetric("fw.m.throwables", groups.values.sumOf { it.count }), FwMetric("fw.m.preallocated", pre.values.sumOf { it.count }),
                FwMetric("fw.m.retained", groups.values.sumOf { it.retained }, bytes = true)),
            listOfNotNull(FwTable("fw.t.throwables", columns, rows(groups)),
                FwTable("fw.t.preallocated", columns, rows(pre), collapsed = true).takeIf { pre.isNotEmpty() }))
    }
    return out.filterNotNull()
}

private fun sessions(
    tomcat: List<HeapInstance>, jetty: List<HeapInstance>, retained: (HeapInstance?) -> Long?, retainedOf: (HeapValue?) -> Long?,
    dumpMillis: Long, top: Int,
): FwSection? {
    if (tomcat.isEmpty() && jetty.isEmpty()) return null
    class A { var count = 0L; var retained = 0L }
    val attrs = HashMap<String, A>()
    val rows = tomcat.map { s ->
        val valid = s.field("isValid")?.asBoolean != false
        val maxInactive = s.field("maxInactiveInterval")?.asInt ?: 0
        val idle = dumpMillis - (s.field("lastAccessedTime")?.asLong ?: dumpMillis)
        val expired = !valid || (maxInactive > 0 && idle > maxInactive * 1000L)
        val entries = s.refField("attributes")?.asInstance?.let { mapEntries(it).toList() }.orEmpty()
        for ((k, v) in entries) attrs.getOrPut(k.readAsJavaString() ?: "?") { A() }.let { it.count++; it.retained += retainedOf(v) ?: 0 }
        listOf(s.field("id")?.readAsJavaString(), entries.size.toString(), retained(s)?.toString(), if (expired) "⚠" else "", (idle / 1000).toString())
    }
    val expired = rows.count { it[3] == "⚠" }
    return FwSection("fw.sessions",
        listOf(FwMetric("fw.m.sessions", (tomcat.size + jetty.size).toLong()), FwMetric("fw.m.expired", expired.toLong()),
            FwMetric("fw.m.retained", (tomcat + jetty).sumOf { retained(it) ?: 0 }, bytes = true)),
        listOfNotNull(
            FwTable("fw.t.sessions", listOf(FwColumn("col.id"), FwColumn("fw.c.attributes", "num"), FwColumn("col.retained", "bytes"),
                FwColumn("fw.c.expired"), FwColumn("fw.c.idleSeconds", "num")),
                rows.sortedByDescending { it[2]?.toLongOrNull() ?: 0 }.take(top)).takeIf { rows.isNotEmpty() },
            FwTable("fw.t.attributes", listOf(FwColumn("fw.c.attribute"), FwColumn("col.count", "num"), FwColumn("col.retained", "bytes")),
                attrs.entries.sortedByDescending { it.value.retained }.take(top)
                    .map { listOf(it.key, it.value.count.toString(), it.value.retained.toString()) }).takeIf { attrs.isNotEmpty() },
        ))
}

private fun jdbc(hikari: List<HeapInstance>, dbcp: List<HeapInstance>, classCounts: Map<String, Long>): FwSection? {
    fun count(kind: String) = classCounts.filterKeys { name -> JDBC_PREFIXES.any { name.startsWith(it) } && kind in name.substringAfterLast('.') }.values.sum()
    val statements = count("Statement")
    val resultSets = count("ResultSet")
    if (hikari.isEmpty() && dbcp.isEmpty() && statements == 0L && resultSets == 0L) return null
    val rows = hikari.map { p ->
        // PoolEntry.state: 0 = not in use, 1 = in use
        val entries = p.refField("connectionBag")?.asInstance?.refField("sharedList")?.asInstance?.refField("array")
            ?.asObjectArray?.readElements()?.mapNotNull { it.asObject?.asInstance }?.toList().orEmpty()
        val name = p.refField("config")?.asInstance?.field("poolName")?.readAsJavaString() ?: hex(p.objectId)
        listOf("HikariCP", name, entries.size.toString(), entries.count { it.field("state")?.asInt == 0 }.toString(),
            entries.count { it.field("state")?.asInt == 1 }.toString())
    } + dbcp.map { d ->
        val pool = d.refField("connectionPool")?.asInstance
        val total = pool?.refField("allObjects")?.asInstance?.mapSize()
        val idle = pool?.refField("idleObjects")?.asInstance?.field("count")?.asInt
        listOf("DBCP2", hex(d.objectId), total?.toString(), idle?.toString(), if (total != null && idle != null) (total - idle).toString() else null)
    }
    return FwSection("fw.jdbc",
        listOf(FwMetric("fw.m.pools", rows.size.toLong()), FwMetric("fw.m.statements", statements), FwMetric("fw.m.resultSets", resultSets)),
        listOfNotNull(FwTable("fw.t.pools", listOf(FwColumn("col.type"), FwColumn("col.name"), FwColumn("fw.c.connections", "num"),
            FwColumn("fw.c.idle", "num"), FwColumn("fw.c.active", "num")), rows).takeIf { rows.isNotEmpty() }))
}

private fun caches(
    h: Heap, caffeine: List<HeapInstance>, guava: List<HeapInstance>, ehcache: List<HeapInstance>,
    retained: (HeapInstance?) -> Long?, top: Int,
): FwSection? {
    val rows = ArrayList<List<String?>>()
    for (cache in caffeine) rows += listOf("Caffeine", cache.instanceClassName, cache.refField("data")?.asInstance?.mapSize()?.toString(), retained(cache)?.toString())
    for (cache in guava) {
        val count = cache.refField("segments")?.asObjectArray?.readElements()?.sumOf { it.asObject?.asInstance?.field("count")?.asInt ?: 0 }
        rows += listOf("Guava", cache.instanceClassName, count?.toString(), retained(cache)?.toString())
    }
    for (cache in ehcache) rows += listOf("Ehcache", cache.instanceClassName, null, retained(cache)?.toString())
    // static maps: classic home-made caches
    for (cls in h.graph.classes) for (f in cls.readStaticFields()) {
        val map = f.value.asObject?.asInstance ?: continue
        if (map.instanceClassName !in MAP_TYPES) continue
        val size = map.mapSize() ?: continue
        if (size >= MIN_STATIC_MAP) rows += listOf("static " + map.instanceClassName.substringAfterLast('.'), "${cls.name}.${f.name}", size.toString(), retained(map)?.toString())
    }
    if (rows.isEmpty()) return null
    return FwSection("fw.caches",
        listOf(FwMetric("fw.m.caches", rows.size.toLong()), FwMetric("fw.m.entries", rows.sumOf { it[2]?.toLongOrNull() ?: 0 })),
        listOf(FwTable("fw.t.caches", listOf(FwColumn("col.type"), FwColumn("fw.c.where"), FwColumn("fw.c.entries", "num"), FwColumn("col.retained", "bytes")),
            rows.sortedByDescending { it[3]?.toLongOrNull() ?: it[2]?.toLongOrNull() ?: 0 }.take(top))))
}
