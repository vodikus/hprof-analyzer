package hprof

import com.sun.management.HotSpotDiagnosticMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeakMarker(val payload: ByteArray)

object Holder {
    @JvmStatic
    var marker: LeakMarker? = null

    /** JDK proxies, one throwaway ClassLoader each: the "uncached proxy" anti-pattern. */
    var proxies: List<Runnable> = emptyList()

    /** Waste / references / off-heap fixtures. */
    var waste: List<Any> = emptyList()

    /** Home-made static cache: shows up under frameworks / caches. */
    var cache: Map<String, String> = emptyMap()
}

class AnalyzerTest {
    @Test
    fun dominatorsMatchLengauerTarjanPaperExample() {
        val names = "RABCDEFGHIJKL"
        val edges = mapOf(
            'R' to "ABC", 'A' to "D", 'B' to "ADE", 'C' to "FG", 'D' to "L", 'E' to "H", 'F' to "I",
            'G' to "IJ", 'H' to "EK", 'I' to "K", 'J' to "I", 'K' to "IR", 'L' to "H",
        )
        val offsets = IntArray(names.length + 1)
        val targets = ArrayList<Int>()
        names.forEachIndexed { i, c ->
            edges[c].orEmpty().forEach { targets.add(names.indexOf(it)) }
            offsets[i + 1] = targets.size
        }
        val d = dominators(names.length, 0, offsets, targets.toIntArray())
        val idom = names.drop(1).map { names[d.idom[names.indexOf(it)]] }.joinToString("")
        // idom of A..L
        assertEquals("RRRRRCCRRGRD", idom)
        assertEquals(names.length, d.order.size)
    }

    @Test
    fun appClassDetection() {
        val auto = emptyList<String>()
        assertTrue(isAppClass("com.empresa.xpto.Foo", auto))
        assertTrue(isAppClass("com.empresa.xpto.Foo[]", auto))
        assertTrue(!isAppClass("java.lang.String", auto))
        assertTrue(!isAppClass("org.springframework.beans.Foo", auto))
        assertTrue(!isAppClass("int[]", auto))
        assertTrue(!isAppClass("com.empresa.xpto.Foo\$\$Lambda/0x123", auto))
        assertTrue(!isAppClass("com.empresa.xpto.Foo\$\$SpringCGLIB\$\$0", auto))
        val xpto = listOf("com.empresa.xpto")
        assertTrue(isAppClass("com.empresa.xpto.api.Foo", xpto))
        assertTrue(!isAppClass("com.empresa.xptoOther.Foo", xpto))
        assertTrue(!isAppClass("com.outra.Foo", xpto))

        assertEquals(listOf("com.empresa.xpto"), detectAppPrefixes("com.empresa.xpto.api.Main --port 8080"))
        assertEquals(listOf("com.empresa.xpto"), detectAppPrefixes("app/com.empresa.xpto.Main"))
        assertEquals(emptyList(), detectAppPrefixes("/opt/app.jar --spring.profiles.active=prod"))
        assertEquals(emptyList(), detectAppPrefixes("org.springframework.boot.loader.launch.JarLauncher"))
        assertEquals(emptyList(), detectAppPrefixes(null))
    }

    @Test
    fun generatedClassDetection() {
        fun g(name: String) = generatedOf(name)?.let { it.generator to it.base }
        assertEquals("ByteBuddy" to "com.x.Foo", g("com.x.Foo\$ByteBuddy\$aB12cD"))
        assertEquals("ByteBuddy" to "java.lang.Object", g("net.bytebuddy.renamed.java.lang.Object\$ByteBuddy\$x1"))
        assertEquals("Hibernate" to "com.x.Order", g("com.x.Order\$HibernateProxy\$Zx9"))
        assertEquals("Spring CGLIB" to "com.x.Svc", g("com.x.Svc\$\$SpringCGLIB\$\$0"))
        assertEquals("Spring CGLIB" to "com.x.Svc", g("com.x.Svc\$\$EnhancerBySpringCGLIB\$\$1a2b"))
        assertEquals("CGLIB" to "com.x.Svc", g("com.x.Svc\$\$EnhancerByCGLIB\$\$1a2b"))
        assertEquals("Mockito" to "com.x.Repo", g("com.x.Repo\$MockitoMock\$123"))
        assertEquals("Javassist" to "com.x.Foo", g("com.x.Foo_\$\$_jvst1a_0"))
        assertEquals(JDK_PROXY to "(interfaces)", g("jdk.proxy3.\$Proxy12"))
        assertEquals(JDK_PROXY to "(interfaces)", g("com.sun.proxy.\$Proxy3"))
        assertEquals(null, g("com.x.Foo\$\$Lambda/0x0000123"))
        assertEquals(null, g("com.x.Foo\$Inner"))
        assertEquals(null, g("com.x.ProxyFactory"))

        assertTrue(!isAppClass("com.x.Foo\$ByteBuddy\$abc", emptyList()))
        assertTrue(!isAppClass("com.x.Order\$HibernateProxy\$abc", listOf("com.x")))

        assertTrue(!isProxySuspect("ByteBuddy", classes = 9, loaders = 1))
        assertTrue(isProxySuspect("ByteBuddy", classes = 10, loaders = 1))
        assertTrue(!isProxySuspect(JDK_PROXY, classes = 150, loaders = 3))
        assertTrue(isProxySuspect(JDK_PROXY, classes = 200, loaders = 3))
        assertTrue(isProxySuspect(JDK_PROXY, classes = 12, loaders = 12))
    }

    @Test
    fun analyzesOwnHeapDump() {
        Holder.marker = LeakMarker(ByteArray(10 * 1024 * 1024))
        Holder.proxies = List(25) {
            val loader = object : ClassLoader(AnalyzerTest::class.java.classLoader) {}
            java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(Runnable::class.java)) { _, _, _ -> null } as Runnable
        }
        val sameInts = IntArray(1024) { it }
        @Suppress("DEPRECATION", "removal")
        Holder.waste = listOf(
            List(1000) { ArrayList<Any>(10) },                       // empty, array allocated
            List(500) { HashMap<String, String>().apply { put("k", "v") } }, // one entry
            List(100) { ByteArray(4096) },                            // zeroed
            List(50) { sameInts.copyOf() },                           // duplicated content
            List(10) { java.lang.Boolean(true) },                     // explicit new
            java.nio.ByteBuffer.allocateDirect(1024 * 1024),
            List(30) { java.lang.ref.SoftReference(Any()) },
            List(40) { java.lang.ref.WeakReference(Any()) },
            Thread {}.apply { name = "finished-worker"; start(); join() }, // terminated, still referenced: leak rule
            List(5) { RuntimeException("boom") },
        )
        Holder.cache = java.util.concurrent.ConcurrentHashMap((1..200).associate { "k$it" to "v$it" })
        val gate = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        repeat(23) { pool.execute { gate.await() } } // 3 running (identical stacks), 20 queued
        val threadLocal = ThreadLocal<ByteArray>().apply { set(ByteArray(1024)) }
        val dump = File.createTempFile("self", ".hprof").also { it.delete() }
        try {
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java).dumpHeap(dump.path, true)
            val report = analyze(dump, Options(top = 30, leakClasses = setOf(LeakMarker::class.java.name), log = {}))

            File("build/test-report.md").writeText(toMarkdown(report)); File("build/test-report.html").writeText(toHtml(report))
            File("build/test-report-en.html").writeText(toHtml(report, Messages.load("en")))
            val markerClass = report.classes.first { it.name == LeakMarker::class.java.name }
            assertEquals(1, markerClass.count)
            assertTrue(markerClass.retained!! >= 10 * 1024 * 1024, "marker retains its payload")
            assertTrue(report.retainedObjects.any { it.className == LeakMarker::class.java.name })
            assertTrue(report.largestArrays.first().bytes >= 10 * 1024 * 1024)
            val path = report.leaks.first { it.title.startsWith(LeakMarker::class.java.name + " ") }
            assertTrue(path.nodes.any { it.reference?.contains("marker") == true }, "path goes through Holder.marker")
            assertTrue(report.threads.any { it.name == "main" || it.frames.isNotEmpty() })
            assertTrue(report.gcRoots.isNotEmpty())
            assertTrue(report.warnings.isEmpty(), "healthy dump: ${report.warnings}")

            // JVM environment read from the heap
            assertEquals(System.getProperty("java.version"), report.jvm["java.version"])
            assertTrue("Kotlin" in report.frameworks, "frameworks: ${report.frameworks}")
            // application view: Gradle worker main class is a library, so exclusion mode keeps our classes only
            val app = report.app!!
            assertTrue(app.classes.any { it.name == LeakMarker::class.java.name }, "app classes: ${app.classes.map { it.name }}")
            assertTrue(app.classes.none { it.name.startsWith("java.") || it.name.startsWith("kotlin.") || '.' !in it.name })
            assertTrue(app.retainedObjects.any { it.className == LeakMarker::class.java.name })
            val appReport = report.appOnly()
            val appMd = toMarkdown(appReport)
            File("build/test-report-app.md").writeText(appMd); File("build/test-report-app.html").writeText(toHtml(appReport))
            assertContains(appMd, "Visão da aplicação")
            assertContains(appMd, LeakMarker::class.java.name)
            assertTrue("## GC roots" !in appMd && "## Strings duplicadas" !in appMd)
            assertContains(toMarkdown(report), "## Ambiente JVM")
            assertContains(toHtml(appReport), "\"appScope\":")

            // generated classes: 25 JDK proxies in 25 loaders are flagged by the loader rule
            val jdkProxies = report.proxies!!.groups.first { it.generator == JDK_PROXY }
            assertTrue(jdkProxies.classes >= 25 && jdkProxies.loaders >= 25, "jdk proxies: $jdkProxies")
            assertTrue(jdkProxies.suspect)
            val fullMd = toMarkdown(report)
            assertContains(fullMd, "## Classes geradas / proxies (metaspace)")
            assertContains(fullMd, "grupo(s) suspeito(s)")
            assertContains(toHtml(report), "\"proxies\":{")

            // waste / references / off-heap
            val w = report.waste!!
            val arrayList = w.collections.first { it.type == "java.util.ArrayList" }
            assertTrue(arrayList.empty >= 1000, "empty ArrayLists: $arrayList")
            assertTrue(w.sizes.first { it.label == "1" }.count >= 500, "size buckets: ${w.sizes}")
            assertTrue(w.arrays.any { it.kind == "zero" && it.type == "byte[]" && it.count >= 100 }, "arrays: ${w.arrays}")
            assertTrue(w.duplicateArrays.any { it.type == "int[]" && it.length == 1024 && it.count >= 50 }, "dups: ${w.duplicateArrays}")
            assertTrue(w.boxing.first { it.type == "java.lang.Boolean" }.redundant >= 10, "boxing: ${w.boxing}")
            assertTrue(w.overhead.header > 0 && w.overhead.data > 0)
            assertTrue(w.strings.latin1 > 0)
            assertTrue(report.offHeap!!.direct.ownerBytes >= 1024 * 1024, "direct: ${report.offHeap!!.direct}")
            val kinds = report.references!!.kinds.associateBy { it.kind }
            assertTrue(kinds["Soft"]!!.count >= 30 && kinds["Weak"]!!.count >= 40, "refs: $kinds")
            val appOnly = report.appOnly()
            assertTrue(appOnly.waste == null && appOnly.references == null && appOnly.offHeap == null && appOnly.health.isEmpty())
            val snapshot = kotlinx.serialization.json.Json.decodeFromString<Snapshot>(toSnapshotJson(report))
            assertTrue(snapshot.classes.size > report.classes.size, "snapshot keeps the full histogram")
            listOf("## Painel de saúde", "## Desperdício de memória", "## Referências e finalização", "## Memória off-heap (estimada)")
                .forEach { assertContains(toMarkdown(report), it) }
            assertContains(toHtml(report), "\"waste\":{")

            // phase 2: leak rules, merged paths, aggregated retained, concurrency
            val leakGroups = report.leakSuspects!!.groups
            assertTrue(leakGroups.any { g -> g.trace.nodes.last().className == "java.lang.Thread" && g.trace.nodes.last().status == "LEAKING" },
                "terminated thread: ${leakGroups.map { it.trace.title }}")
            val markerPaths = report.mergedPaths.first { it.className == LeakMarker::class.java.name }
            assertTrue(markerPaths.links.any { it.source.endsWith("|class hprof.Holder") && it.target == "0|${LeakMarker::class.java.name}" },
                "merged paths: ${markerPaths.links}")
            val rv = report.retainedViews!!
            assertTrue(rv.staticFields.any { it.owner == "hprof.Holder" && it.field == "marker" && it.retained >= 10 * 1024 * 1024 }, "statics: ${rv.staticFields.take(5)}")
            assertTrue(rv.byLoader!!.children!!.isNotEmpty())
            assertTrue(rv.dominators.isNotEmpty())
            val cc = report.concurrency!!
            assertTrue(cc.pools.any { it.kind == "ThreadPoolExecutor" && it.queued == 20 && it.threads == 3 }, "pools: ${cc.pools}")
            assertTrue(cc.states.any { it.name == "RUNNABLE" }, "states: ${cc.states}")
            val me = report.threads.first { it.name == Thread.currentThread().name }
            assertTrue((me.threadLocals ?: 0) > 0 && me.state == "RUNNABLE", "current thread: $me")
            assertTrue(cc.stackGroups.any { it.count >= 3 }, "stack groups: ${cc.stackGroups.map { it.count }}")
            listOf("## Suspeitas de leak", "## Caminhos agregados por classe", "## Retained agregado", "## Concorrência")
                .forEach { assertContains(toMarkdown(report), it) }

            // phase 3: frameworks, graph, metadata
            val caches = report.inspections.first { it.key == "fw.caches" }
            assertTrue(caches.tables.single().rows.any { it[1] == "hprof.Holder.cache" && it[2] == "200" }, "caches: ${caches.tables}")
            val throwables = report.inspections.first { it.key == "fw.throwables" }
            assertTrue(throwables.tables.single().rows.any { it[0] == "java.lang.RuntimeException" && it[1] == "boom" && it[2]!!.toInt() >= 5 },
                "throwables: ${throwables.tables.single().rows.take(5)}")
            val g = report.graph!!
            assertTrue(g.fanIn.isNotEmpty() && g.depths.isNotEmpty() && g.classEdges.isNotEmpty())
            assertTrue(g.fanOut.first().degree >= 1000, "fan-out: ${g.fanOut.take(3)}")
            val records = report.metadata!!.records.associate { it.name to it.count }
            assertTrue((records["STRING_IN_UTF8"] ?: 0) > 0 && (records["LOAD_CLASS"] ?: 0) > 0, "records: $records")
            listOf("## Frameworks e tecnologias", "## Estrutura do grafo", "## Metadados do arquivo").forEach { assertContains(toMarkdown(report), it) }

            // a failed section is flagged at the top of both reports
            val warned = report.copy(warnings = listOf("Aviso: \"Caminhos até GC roots\" falhou, seção omitida: boom"))
            assertContains(toMarkdown(warned), "## Avisos")
            assertContains(toMarkdown(warned), "boom")
            assertContains(toHtml(warned), "boom")
            assertTrue("## Avisos" !in toMarkdown(report))

            val md = toMarkdown(report)
            listOf("## Resumo", "## Histograma de classes", "## Caminhos até GC roots", "```mermaid", "## Threads")
                .forEach { assertContains(md, it) }
            val html = toHtml(report)
            assertContains(html, "const DATA = {")
            assertContains(html, "echarts")
            assertTrue("/*DATA*/" !in html && "/*ECHARTS*/" !in html)
            assertContains(html, "LOCALE = \"pt-BR\"")
            assertTrue(Regex("""\d+\.\d+\.\d+""").matches(VERSION), "version from build.gradle.kts: $VERSION")
            assertEquals(VERSION, report.summary.toolVersion)
            assertContains(md, "hprof-analyzer $VERSION")

            val en = Messages.load("en")
            val mdEn = toMarkdown(report, en)
            listOf("## Summary", "## Class histogram", "## Paths to GC roots").forEach { assertContains(mdEn, it) }
            val htmlEn = toHtml(report, en)
            assertContains(htmlEn, "LOCALE = \"en\"")
            assertContains(htmlEn, "\"section.summary\":\"Summary\"")
            assertTrue("/*I18N*/" !in htmlEn)
        } finally {
            dump.delete()
            Holder.marker = null
            Holder.proxies = emptyList()
            Holder.waste = emptyList()
            Holder.cache = emptyMap()
            gate.countDown(); pool.shutdown(); threadLocal.remove()
        }
    }

    @Test
    fun log2Buckets() {
        assertEquals(listOf(0, 1, 2, 3, 3, 4, 4, 5), listOf(0L, 1, 2, 3, 4, 5, 8, 9).map(::log2Bucket))
        assertEquals(listOf("0", "1", "2", "3-4", "5-8", "9-16"), (0..5).map(::log2Label))
    }

    @Test
    fun depthBucketLabels() {
        val counts = LongArray(70).also { it[1] = 5; it[20] = 1; it[21] = 2; it[32] = 3; it[33] = 4; it[69] = 1 }
        assertEquals(listOf("1" to 5L, "20" to 1L, "21-32" to 5L, "33-64" to 4L, "65-128" to 1L),
            depthBuckets(counts).map { it.label to it.count })
    }

    @Test
    fun healthRules() {
        val summary = Summary("a.hprof", 0, "", 8, "", 0, 0, 0, 0, 0, 0, totalShallow = 1000, reachableCount = 1,
            reachableBytes = 1000, analysisMillis = 0, toolVersion = "")
        val ok = HeapReport(summary, emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList())
        assertEquals(emptyList(), health(ok))

        val sick = ok.copy(
            retainedObjects = listOf(ObjectStat("0x1", "a.Big", 1, 400, null)),
            references = RefReport(emptyList(), emptyList(), 20_000, emptyList(), emptyList()),
            classLoaders = List(2) { LoaderStat("0x$it", "org.apache.catalina.loader.ParallelWebappClassLoader", 1, null) },
            duplicateStrings = listOf(DupString("x", 3, 100, 200)),
            waste = WasteReport(listOf(CollectionStat("java.util.ArrayList", 2000, 2000, 60, 10, 2000, 0)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Overhead(0, 0, 0),
                StringStats(0, 0, 0, 0, emptyList(), emptyList())),
        )
        val keys = health(sick).map { it.severity to it.key }
        assertEquals(listOf(CRITICAL to "bigDominator", CRITICAL to "finalizerQueue", WARNING to "lowFill",
            WARNING to "webappLoaders", WARNING to "dupStrings", INFO to "emptyCollections"), keys)
        assertEquals(listOf("40", "a.Big"), health(sick).first().args)
    }
}
