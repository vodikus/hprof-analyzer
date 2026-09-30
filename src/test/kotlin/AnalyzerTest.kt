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
    fun analyzesOwnHeapDump() {
        Holder.marker = LeakMarker(ByteArray(10 * 1024 * 1024))
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

            val md = toMarkdown(report)
            listOf("## Resumo", "## Histograma de classes", "## Caminhos até GC roots", "```mermaid", "## Threads")
                .forEach { assertContains(md, it) }
            val html = toHtml(report)
            assertContains(html, "const DATA = {")
            assertContains(html, "echarts")
            assertTrue("/*DATA*/" !in html && "/*ECHARTS*/" !in html)
            assertContains(html, "LOCALE = \"pt-BR\"")

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
        }
    }
}
