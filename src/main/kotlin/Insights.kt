package hprof

import kotlinx.serialization.Serializable
import shark.HeapObject.HeapInstance
import java.util.BitSet
import java.util.Locale

/** A localized sentence: [key] is an i18n key, [args] its arguments; an arg starting with '@' is itself an i18n key. */
@Serializable
data class Sentence(val key: String, val args: List<String> = emptyList())

/** Automatic analyses built on top of the other sections (see [insights] and [conclude]). */
@Serializable
data class Insights(
    val budget: Budget? = null,
    val wasteByOwner: List<OwnerWaste> = emptyList(),
    val stringsByOwner: List<OwnerStrings> = emptyList(),
    val duplicateClasses: List<DupClass> = emptyList(),
    /** Bytes kept alive only through SoftReferences (not strongly reachable); null with --no-retained. */
    val softOnlyBytes: Long? = null,
    val staticCollections: List<StaticCollection> = emptyList(),
    val libraries: List<Library> = emptyList(),
    val context: DumpContext? = null,
    val sizing: Sizing? = null,
    val suspects: List<SuspectScore> = emptyList(),
    val executive: List<Sentence> = emptyList(),
)

/** Reachable heap split so that every byte counts once; [components] names starting with '@' are i18n keys. */
@Serializable
data class Budget(val components: List<NamedBytes>, val total: Long, val offHeap: Long)

@Serializable
data class NamedBytes(val name: String, val bytes: Long)

/** Collections with free slots grouped by the field that holds them. */
@Serializable
data class OwnerWaste(
    val owner: String, val type: String, val count: Long, val size: Long, val capacity: Long, val unusedBytes: Long,
    val ownerClass: String = "", val jdk: Boolean = false,
)

/** Duplicated strings grouped by the field that holds them; [bytes] = their share of the duplicate-string waste. */
@Serializable
data class OwnerStrings(val owner: String, val copies: Long, val bytes: Long, val ownerClass: String = "", val jdk: Boolean = false)

@Serializable
data class DupClass(val name: String, val loaders: Int)

@Serializable
data class StaticCollection(val owner: String, val type: String, val size: Long, val retained: Long?)

/** [versions]: distinct versions found ("-" = none in the file name); more than one = conflict. */
@Serializable
data class Library(val name: String, val versions: List<String>, val jars: List<String>)

/** [afterOom]: strong evidence of a dump taken on OutOfMemoryError; [live]: false when much of the dump is garbage. */
@Serializable
data class DumpContext(val afterOom: Boolean, val evidence: List<Sentence>, val live: Boolean?)

/** Heuristic sizes: -Xmx 3-4x the live set, direct memory 1.5x the direct buffers. */
@Serializable
data class Sizing(val liveSet: Long, val xmxMin: Long, val xmxMax: Long, val currentXmx: Long?, val directMin: Long?)

/**
 * Factors in 0..1 and their weighted [score] (0..100). [share] is retained / reachable heap (the score counts it full
 * from 30%). [growth] needs `--baseline`.
 */
@Serializable
data class SuspectScore(
    val className: String, val score: Double, val retained: Long, val instances: Long,
    val share: Double, val growth: Double, val staticHeld: Double, val collectionHeld: Double, val depth: Double,
)

const val BUDGET_APP = "@budget.app"
const val BUDGET_JDK = "@budget.jdk"
const val BUDGET_GEN = "@budget.gen"
const val BUDGET_OTHER = "@budget.other"

// ponytail: friendly names for the common library roots; anything else shows its package prefix
private val LIB_NAMES = mapOf(
    "org.springframework" to "Spring", "io.netty" to "Netty", "org.h2" to "H2", "org.hibernate" to "Hibernate",
    "org.apache" to "Apache", "com.fasterxml" to "Jackson", "kotlin" to "Kotlin", "kotlinx" to "Kotlin",
    "scala" to "Scala", "groovy" to "Groovy", "org.codehaus.groovy" to "Groovy", "io.quarkus" to "Quarkus",
    "io.micronaut" to "Micronaut", "io.vertx" to "Vert.x", "org.eclipse" to "Eclipse", "com.google" to "Google",
    "org.jboss" to "JBoss", "org.glassfish" to "GlassFish", "ch.qos.logback" to "Logback", "org.slf4j" to "SLF4J",
    "io.micrometer" to "Micrometer", "reactor" to "Reactor", "io.projectreactor" to "Reactor", "net.bytebuddy" to "ByteBuddy",
    "com.zaxxer" to "HikariCP", "org.postgresql" to "PostgreSQL", "com.mysql" to "MySQL", "oracle" to "Oracle",
    "com.mongodb" to "MongoDB", "io.lettuce" to "Lettuce", "io.grpc" to "gRPC", "io.opentelemetry" to "OpenTelemetry",
    "okhttp3" to "OkHttp", "com.github.benmanes" to "Caffeine", "io.ktor" to "Ktor", "org.jetbrains" to "JetBrains",
    "jakarta" to "Jakarta EE",
)
private const val BUDGET_TOP = 7
private const val MAX_OWNER_HOPS = 8
private const val MAX_OWNER_ITEMS = 100_000
private const val MAX_DEPTH_SAMPLES = 10_000
private val JAR_VERSION = Regex("""^(.+?)-(\d+(?:\.\d+)*(?:[-.][A-Za-z0-9]+)*)\.jar$""")
private val XMX = Regex("""^-(?:Xmx|XX:MaxHeapSize=)(\d+)([kKmMgGtT]?)$""")
private const val MIB = 1024L * 1024

private fun ratio(v: Double) = String.format(Locale.ROOT, "%.0f", 100 * v)

/** Budget component of a class name (arrays by element type, "class X" by X). */
internal fun component(label: String, appPrefixes: List<String>): String {
    val name = label.removePrefix("class ").substringBefore('[')
    if ('.' !in name) return BUDGET_JDK // primitive arrays, default package
    if ("$$" in name || generatedOf(name) != null) return BUDGET_GEN
    if (isAppClass(name, appPrefixes)) return BUDGET_APP
    if (JDK_PREFIXES.any { name.startsWith("$it.") }) return BUDGET_JDK
    LIB_PREFIXES.filter { name.startsWith(it) }.maxByOrNull { it.length }?.let { p ->
        val root = p.removeSuffix(".")
        return LIB_NAMES[root] ?: root
    }
    return name.split('.').take(2).joinToString(".") // unknown library (with --app-package)
}

/**
 * Exclusive attribution over the dominator tree: an object belongs to the component of its nearest non-JDK dominator,
 * else to its own class's component. Every reachable byte counts once, so the parts add up to the reachable heap.
 */
internal fun budget(h: Heap, appPrefixes: List<String>, offHeap: Long): Budget? {
    val dom = h.dom ?: return null
    val names = ArrayList<String>()
    val index = HashMap<String, Int>()
    fun id(name: String) = index.getOrPut(name) { names.add(name); names.size - 1 }
    val jdk = id(BUDGET_JDK)
    val byAcc = HashMap<Int, Int>()
    val classAcc = h.classNames.indexOf("java.lang.Class")
    fun own(v: Int) = if (h.clsOf[v] == classAcc) id(component(h.label(v), appPrefixes))
        else byAcc.getOrPut(h.clsOf[v]) { id(component(h.classNames[h.clsOf[v]], appPrefixes)) }
    val comp = IntArray(h.n + 1) { -1 }
    var sizes = LongArray(64)
    for (k in 1 until dom.order.size) {
        val v = dom.order[k]
        val p = dom.idom[v]
        val inherited = if (p != h.n && p >= 0 && comp[p] >= 0 && comp[p] != jdk) comp[p] else -1
        val c = if (inherited >= 0) inherited else own(v)
        comp[v] = c
        if (c >= sizes.size) sizes = sizes.copyOf(c * 2)
        sizes[c] += h.shallow[v]
    }
    val all = names.indices.map { NamedBytes(names[it], sizes[it]) }.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
    val rest = all.drop(BUDGET_TOP).sumOf { it.bytes }
    return Budget(all.take(BUDGET_TOP) + listOfNotNull(NamedBytes(BUDGET_OTHER, rest).takeIf { rest > 0 }), all.sumOf { it.bytes }, offHeap)
}

private fun isContainer(name: String) =
    name.endsWith("[]") || name.startsWith("java.util.") || name.startsWith("jdk.internal.util.")

/** Field holding an object: [label] "Foo.cache", [className] the full class of the holder (for the origin filter). */
internal class Owner(val label: String, val className: String)

/**
 * The field that holds [v], skipping collection internals: "Foo.cache" for a HashMap in Foo.cache or a String in it.
 * Climbs the BFS tree ([parent]) while the holder is an array or a java.util class, up to [MAX_OWNER_HOPS].
 */
internal fun ownerOf(h: Heap, parent: IntArray, v: Int): Owner? {
    fun owner(p: Int, child: Int) = Owner(edgeName(h, p, h.ids[child]), h.label(p).removePrefix("class "))
    var cur = v
    repeat(MAX_OWNER_HOPS) {
        val p = parent[cur]
        if (p < 0) return null
        if (p == h.n) return Owner("${h.label(cur)} (GC root)", h.label(cur).removePrefix("class "))
        if (!isContainer(h.classNames[h.clsOf[p]])) return owner(p, cur)
        cur = p
    }
    val p = parent[cur]
    return if (p >= 0 && p != h.n) owner(p, cur) else null
}

/** Top [top] owners outside the JDK plus top [top] JDK ones (hidden by default by the renderers). */
private fun <T> splitTop(list: List<T>, top: Int, jdk: (T) -> Boolean) =
    list.filterNot(jdk).take(top) + list.filter(jdk).take(top)

internal fun wasteByOwner(h: Heap, parent: IntArray, idle: List<LongArray>, refSize: Int, appPrefixes: List<String>, top: Int): List<OwnerWaste> {
    class A(val ownerClass: String) { var count = 0L; var size = 0L; var capacity = 0L }
    val groups = HashMap<Pair<String, String>, A>()
    for (e in idle.sortedByDescending { it[2] - it[1] }.take(MAX_OWNER_ITEMS)) {
        val v = e[0].toInt()
        val owner = ownerOf(h, parent, v) ?: continue
        groups.getOrPut(owner.label to h.classNames[h.clsOf[v]].substringAfterLast('.')) { A(owner.className) }
            .let { it.count++; it.size += e[1]; it.capacity += e[2] }
    }
    val all = groups.map { (k, a) ->
        OwnerWaste(k.first, k.second, a.count, a.size, a.capacity, (a.capacity - a.size) * refSize, a.ownerClass,
            component(a.ownerClass, appPrefixes) == BUDGET_JDK)
    }.sortedByDescending { it.unusedBytes }
    return splitTop(all, top) { it.jdk }
}

/**
 * Second pass over the Strings whose value is duplicated ([strings] count > 1). Each copy contributes its share of the
 * waste, bytes x (count - 1) / count, so the owners add up to the duplicate-string waste.
 */
internal fun stringsByOwner(h: Heap, parent: IntArray, strings: Map<String, StrAcc>, appPrefixes: List<String>, top: Int): List<OwnerStrings> {
    val cls = h.graph.findClassByName("java.lang.String") ?: return emptyList()
    class A(val ownerClass: String) { var copies = 0.0; var wasted = 0.0 }
    val groups = HashMap<String, A>()
    var seen = 0
    for (s in cls.instances) {
        val acc = s.readAsJavaString()?.let { strings[it] } ?: continue
        if (acc.count < 2) continue
        if (++seen > MAX_OWNER_ITEMS) break
        val owner = ownerOf(h, parent, h.graph.indexOf(s)) ?: continue
        groups.getOrPut(owner.label) { A(owner.className) }.let { val excess = (acc.count - 1.0) / acc.count; it.copies += excess; it.wasted += acc.bytes * excess }
    }
    val all = groups.map { (k, a) -> OwnerStrings(k, Math.round(a.copies), Math.round(a.wasted), a.ownerClass, component(a.ownerClass, appPrefixes) == BUDGET_JDK) }
        .filter { it.copies > 0 }.sortedByDescending { it.bytes }
    return splitTop(all, top) { it.jdk }
}

/** Class names defined by more than one ClassLoader (duplicate JARs, version conflicts); generated classes excluded. */
internal fun duplicateClasses(h: Heap, top: Int): List<DupClass> {
    val loaders = HashMap<String, MutableSet<Long>>()
    for (c in h.graph.classes) {
        val name = c.name
        if ("$$" in name || "\$Lambda" in name || "LambdaForm$" in name || '/' in name || '[' in name || generatedOf(name) != null) continue
        loaders.getOrPut(name) { HashSet() }.add(c.readRecord().classLoaderId)
    }
    return loaders.filterValues { it.size > 1 }.map { DupClass(it.key, it.value.size) }
        .sortedWith(compareByDescending<DupClass> { it.loaders }.thenBy { it.name }).take(top)
}

/** Bytes reachable from SoftReference referents through objects that are not strongly reachable (BFS over the CSR graph). */
internal fun softOnlyBytes(h: Heap, offsets: IntArray, targets: IntArray, parent: IntArray, referents: List<Long>): Long {
    val seen = BitSet(h.n)
    val queue = IntArray(h.n)
    var tail = 0
    for (id in referents) {
        val v = h.graph.findObjectByIdOrNull(id)?.let(h.graph::indexOf) ?: continue
        if (parent[v] == -1 && !seen[v]) { seen.set(v); queue[tail++] = v }
    }
    var head = 0
    var bytes = 0L
    while (head < tail) {
        val v = queue[head++]
        bytes += h.shallow[v]
        for (e in offsets[v] until offsets[v + 1]) {
            val w = targets[e]
            if (w < h.n && parent[w] == -1 && !seen[w]) { seen.set(w); queue[tail++] = w }
        }
    }
    return bytes
}

private fun HeapInstance.elementCount(): Long? =
    (field("size") ?: field("baseCount") ?: field("count") ?: field("elementCount"))?.let { it.asInt?.toLong() ?: it.asLong }

private fun isCollection(name: String) = name.startsWith("java.util.") &&
    (name.endsWith("Map") || name.endsWith("List") || name.endsWith("Set") || name.endsWith("Queue") || name == "java.util.Hashtable" || name == "java.util.Vector")

/** Collections of at least [min] elements held directly by a static field. */
internal fun staticCollections(h: Heap, min: Long, top: Int): List<StaticCollection> {
    val out = ArrayList<StaticCollection>()
    for (c in h.graph.classes) for (f in c.readStaticFields()) {
        val o = f.value.asObject as? HeapInstance ?: continue
        if (!isCollection(o.instanceClassName)) continue
        val size = o.elementCount() ?: continue
        if (size >= min) out += StaticCollection("${c.name}.${f.name}", o.instanceClassName.substringAfterLast('.'), size, h.retained?.get(h.graph.indexOf(o)))
    }
    return out.sortedByDescending { it.size }.take(top)
}

/** JAR file names from the open ZipFiles and the class path, grouped by artifact name (versions from the file name). */
internal fun libraries(h: Heap, classPath: String?, pathSeparator: String?): List<Library> {
    val paths = LinkedHashSet<String>()
    h.graph.findClassByName("java.util.zip.ZipFile\$Source")?.instances?.forEach { s ->
        s.refField("key")?.asInstance?.refField("file")?.asInstance?.get("java.io.File", "path")?.value?.readAsJavaString()?.let(paths::add)
    }
    classPath?.split(pathSeparator ?: java.io.File.pathSeparator)?.forEach(paths::add)
    return libraryList(paths.map { it.substringAfterLast('/').substringAfterLast('\\') })
}

// ponytail: version = the digits after the last name-ish segment; "log4j-1.2-api-2.17.jar" reads as log4j 1.2-api-2.17
internal fun libraryList(fileNames: List<String>): List<Library> = fileNames.filter { it.endsWith(".jar") }.distinct()
    .groupBy { jar -> JAR_VERSION.matchEntire(jar)?.groupValues?.get(1) ?: jar.removeSuffix(".jar") }
    .map { (name, list) -> Library(name, list.map { JAR_VERSION.matchEntire(it)?.groupValues?.get(2) ?: "-" }.distinct().sorted(), list.sorted()) }
    .sortedWith(compareByDescending<Library> { it.versions.size > 1 }.thenBy { it.name })

internal fun dumpContext(vmArgs: List<String>, threads: List<ThreadInfo>, inspections: List<FwSection>, live: Boolean?): DumpContext {
    val evidence = ArrayList<Sentence>()
    if (vmArgs.any { it == "-XX:+HeapDumpOnOutOfMemoryError" }) evidence += Sentence("ctx.flag")
    val inFrame = threads.filter { t -> t.frames.any { f -> f.locals.any { "OutOfMemoryError" in it } } }.map { it.name }
    if (inFrame.isNotEmpty()) evidence += Sentence("ctx.frame", listOf(inFrame.take(3).joinToString()))
    // only the in-use table: pre-allocated OOMs are created by the JVM at startup
    val thrown = inspections.firstOrNull { it.key == "fw.throwables" }?.tables?.firstOrNull { !it.collapsed }?.rows
        ?.filter { it[0]?.endsWith("OutOfMemoryError") == true }?.sumOf { it[2]?.toLongOrNull() ?: 0 } ?: 0
    if (thrown > 0) evidence += Sentence("ctx.thrown", listOf(thrown.toString()))
    return DumpContext(inFrame.isNotEmpty() || thrown > 0, evidence, live)
}

private fun roundUp(v: Long, step: Long) = (v + step - 1) / step * step

internal fun parseXmx(vmArgs: List<String>): Long? = vmArgs.asReversed().firstNotNullOfOrNull { a ->
    XMX.matchEntire(a)?.let { m ->
        val unit = when (m.groupValues[2].lowercase()) { "k" -> 1024L; "m" -> MIB; "g" -> MIB * 1024; "t" -> MIB * 1024 * 1024; else -> 1L }
        m.groupValues[1].toLong() * unit
    }
}

// ponytail: rule of thumb (3-4x the live set leaves room for allocation and GC); a hint, not a measurement
internal fun sizing(liveSet: Long?, directBytes: Long, vmArgs: List<String>): Sizing? {
    val live = liveSet?.takeIf { it > 0 } ?: return null
    fun round(v: Long) = roundUp(v, if (v < 1024 * MIB) 32 * MIB else 256 * MIB)
    return Sizing(live, round(3 * live), round(4 * live), parseXmx(vmArgs), directBytes.takeIf { it > 0 }?.let { round(it * 3 / 2) })
}

/**
 * Score factors of the [candidates] class accumulators (growth is filled by [conclude]): retained share, share of the
 * retained size whose immediate dominator is a class object (static field) or a JDK container, and average BFS depth.
 */
internal fun suspectFactors(h: Heap, depth: IntArray, candidates: List<Int>, retainedOf: (Int) -> Long, countOf: (Int) -> Long): List<SuspectScore> {
    val dom = h.dom ?: return emptyList()
    val ret = h.retained!!
    val heap = ret[h.n].toDouble().coerceAtLeast(1.0)
    val classAcc = h.classNames.indexOf("java.lang.Class")
    val want = candidates.toHashSet()
    class A { var total = 0L; var static = 0L; var coll = 0L; var depthSum = 0L; var depthN = 0 }
    val accs = HashMap<Int, A>()
    for (k in 1 until dom.order.size) {
        val v = dom.order[k]
        val c = h.clsOf[v]
        if (c !in want) continue
        val p = dom.idom[v]
        if (p != h.n && h.clsOf[p] == c) continue // inside a same-class chain: counted at its head
        val a = accs.getOrPut(c) { A() }
        a.total += ret[v]
        if (p != h.n && h.clsOf[p] == classAcc) a.static += ret[v]
        else if (p != h.n && isContainer(h.classNames[h.clsOf[p]])) a.coll += ret[v]
        if (a.depthN < MAX_DEPTH_SAMPLES) { a.depthSum += depth[v]; a.depthN++ }
    }
    return candidates.mapNotNull { c ->
        val a = accs[c] ?: return@mapNotNull null
        val total = a.total.coerceAtLeast(1).toDouble()
        SuspectScore(h.classNames[c], 0.0, retainedOf(c), countOf(c),
            share = retainedOf(c) / heap, growth = 0.0,
            staticHeld = a.static / total, collectionHeld = a.coll / total,
            depth = if (a.depthN == 0) 0.0 else (a.depthSum.toDouble() / a.depthN / 20).coerceIn(0.0, 1.0))
    }
}

// ponytail: fixed weights; expose them as thresholds if users need to tune them
private const val W_SHARE = 0.35
private const val W_GROWTH = 0.25
private const val W_STATIC = 0.15
private const val W_COLLECTION = 0.15
private const val W_DEPTH = 0.10
private const val BENIGN_FACTOR = 0.3
/** Below this score the executive summary lists the biggest retainers instead of calling them suspects. */
private const val STRONG_SUSPECT = 40.0
/** Retaining this share of the reachable heap scores the full retained factor. */
private const val FULL_SHARE = 0.30

internal fun score(s: SuspectScore, diff: DiffReport?): SuspectScore {
    val d = diff?.classes?.firstOrNull { it.name == s.className }
    val growth = when {
        d == null -> 0.0
        d.growing -> 1.0
        else -> (d.delta.toDouble() / (d.values.firstOrNull() ?: 0L).coerceAtLeast(1)).coerceIn(0.0, 1.0)
    }
    var v = W_SHARE * (s.share / FULL_SHARE).coerceIn(0.0, 1.0) + W_GROWTH * growth + W_STATIC * s.staticHeld + W_COLLECTION * s.collectionHeld + W_DEPTH * s.depth
    if (benignReason(s.className) != null) v *= BENIGN_FACTOR
    return s.copy(growth = growth, score = Math.round(v * 1000) / 10.0)
}

/** Inputs of [insights] gathered by [analyze]. */
internal class InsightInputs(
    val h: Heap, val bfs: Bfs, val offsets: IntArray, val targets: IntArray, val appPrefixes: List<String>,
    val jvm: Map<String, String>, val vmArgs: List<String>, val threads: List<ThreadInfo>, val inspections: List<FwSection>,
    val offHeap: OffHeapReport?, val waste: WasteCollector, val softReferents: List<Long>, val strings: Map<String, StrAcc>,
    val summary: Summary, val candidates: List<Int>, val retainedOf: (Int) -> Long, val countOf: (Int) -> Long,
)

/** Everything computed from the heap graph; scores, health and the executive summary come later in [conclude]. */
internal fun insights(i: InsightInputs, opt: Options, warnings: MutableList<String>): Insights {
    fun <T> g(section: String, fallback: T, block: () -> T): T = try { block() } catch (e: Exception) {
        val w = opt.msg["warn.section", opt.msg[section], e.brief()]
        opt.log(w); warnings.add(w); fallback
    }
    val h = i.h
    val top = opt.top
    val s = i.summary
    val direct = i.offHeap?.direct?.ownerBytes ?: 0L
    val unreachable = opt.thresholds["unreachable"] ?: DEFAULT_THRESHOLDS.getValue("unreachable")
    val live = s.reachableBytes?.let { (s.totalShallow - it) <= s.totalShallow * unreachable }
    return Insights(
        budget = g("section.budget", null) { budget(h, i.appPrefixes, direct) },
        wasteByOwner = g("section.waste", emptyList()) { wasteByOwner(h, i.bfs.parent, i.waste.idle, i.waste.refSize, i.appPrefixes, top) },
        stringsByOwner = g("section.strings", emptyList()) { stringsByOwner(h, i.bfs.parent, i.strings, i.appPrefixes, top) },
        duplicateClasses = g("section.loaders", emptyList()) { duplicateClasses(h, top) },
        softOnlyBytes = if (h.dom == null) null else g("section.references", null) { softOnlyBytes(h, i.offsets, i.targets, i.bfs.parent, i.softReferents) },
        staticCollections = g("section.retainedViews", emptyList()) {
            staticCollections(h, (opt.thresholds["bigStaticCollection"] ?: DEFAULT_THRESHOLDS.getValue("bigStaticCollection")).toLong(), top)
        },
        libraries = g("section.libraries", emptyList()) { libraries(h, i.jvm["java.class.path"], i.jvm["path.separator"]) },
        context = g("section.summary", null) { dumpContext(i.vmArgs, i.threads, i.inspections, live) },
        sizing = sizing(s.reachableBytes, direct, i.vmArgs),
        suspects = g("section.suspects", emptyList()) { suspectFactors(h, i.bfs.depth, i.candidates, i.retainedOf, i.countOf) },
    )
}

/** Final pass, also after `--baseline` adds the diff: suspect scores, health findings and the executive summary. */
fun conclude(r: HeapReport, thresholds: Map<String, Double> = DEFAULT_THRESHOLDS): HeapReport {
    val ins = r.insights?.let { i -> i.copy(suspects = i.suspects.map { score(it, r.diff) }.sortedByDescending { it.score }) }
    val scored = r.copy(insights = ins)
    val findings = health(scored, thresholds)
    return scored.copy(health = findings, thresholds = thresholds, insights = ins?.copy(executive = executive(scored, findings)))
}

internal fun executive(r: HeapReport, findings: List<HealthFinding>): List<Sentence> = buildList {
    val s = r.summary
    val ins = r.insights ?: return@buildList
    val direct = r.offHeap?.direct?.ownerBytes ?: 0
    add(Sentence("exec.heap", listOf(bytes(s.reachableBytes ?: s.totalShallow), bytes(s.totalShallow), bytes(direct))))
    if (ins.context?.live == false) s.reachableBytes?.let {
        add(Sentence("exec.notLive", listOf(ratio((s.totalShallow - it).toDouble() / s.totalShallow.coerceAtLeast(1)))))
    }
    ins.budget?.let { b ->
        val parts = b.components.filter { it.name != BUDGET_OTHER }.take(3)
            .flatMap { listOf(it.name, ratio(it.bytes.toDouble() / b.total.coerceAtLeast(1))) }
        if (parts.isNotEmpty()) add(Sentence("exec.budget${parts.size / 2}", parts))
    }
    ins.context?.let { c -> add(if (c.afterOom) Sentence("exec.oom", listOf(c.evidence.size.toString())) else Sentence("exec.noOom")) }
    val appLeaks = r.leakSuspects?.groups?.count { it.kind == "application" } ?: 0
    val growing = r.diff?.collections?.count { it.growing } ?: 0
    add(if (appLeaks + growing > 0) Sentence("exec.leaks", listOf(appLeaks.toString(), growing.toString())) else Sentence("exec.noLeaks"))
    ins.suspects.take(3).takeIf { it.isNotEmpty() }?.let { top ->
        val names = top.joinToString { sc -> sc.className.substringAfterLast('.') }
        add(Sentence(if (top.first().score >= STRONG_SUSPECT) "exec.start" else "exec.noStrongSuspect", listOf(names)))
    }
    val critical = findings.count { it.severity == CRITICAL }
    val warning = findings.count { it.severity == WARNING }
    val sizing = ins.sizing
    add(if (sizing != null) Sentence("exec.findingsSizing", listOf(critical.toString(), warning.toString(), bytes(sizing.xmxMin), bytes(sizing.xmxMax)))
        else Sentence("exec.findings", listOf(critical.toString(), warning.toString())))
}
