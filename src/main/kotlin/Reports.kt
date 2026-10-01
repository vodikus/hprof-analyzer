package hprof

import kotlinx.serialization.json.Json
import java.util.Locale

fun bytes(b: Long?, locale: Locale = Locale.ROOT): String {
    if (b == null) return "-"
    if (b < 1024) return "$b B"
    val units = "KMGTPE"
    var v = b.toDouble()
    var i = -1
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
    return String.format(locale, "%.1f %siB", v, units[i])
}

private fun md(s: String) = s.replace("|", "\\|").replace("\n", " ").replace("`", "'")

private fun mermaidLabel(s: String) = s.replace("\"", "#quot;").replace("<", "#lt;").replace(">", "#gt;")

fun toMarkdown(r: HeapReport, msg: Messages = Messages.load()): String = buildString {
    fun b(v: Long?) = bytes(v, msg.locale)
    fun n(v: Number?) = v?.let { String.format(msg.locale, "%,d", it) }
    fun table(headerKeys: List<String>, rows: List<List<Any?>>) {
        if (rows.isEmpty()) { appendLine("_${msg["misc.noData"]}_\n"); return }
        val headers = headerKeys.map { msg[it] }
        appendLine(headers.joinToString(" | ", "| ", " |"))
        appendLine(headers.joinToString(" | ", "| ", " |") { "---" })
        rows.forEach { row -> appendLine(row.joinToString(" | ", "| ", " |") { md(it?.toString() ?: "-") }) }
        appendLine()
    }
    fun section(key: String) = appendLine("## ${msg[key]}\n")

    val s = r.summary
    appendLine("# ${md(msg["report.title", s.file])}\n")
    if (r.warnings.isNotEmpty()) {
        section("section.warnings")
        appendLine("> ${msg["note.warnings"]}\n")
        r.warnings.forEach { appendLine("- ⚠️ ${md(it)}") }
        appendLine()
    }
    section("section.summary")
    table(listOf("summary.metric", "summary.value"), listOf(
        listOf(msg["summary.file"], "${s.file} (${b(s.fileSize)})"),
        listOf(msg["summary.hprofVersion"], s.hprofVersion),
        listOf(msg["summary.idSize"], "${s.identifierByteSize} bytes"),
        listOf(msg["summary.timestamp"], s.timestamp),
        listOf(msg["summary.objects"], n(s.objectCount)),
        listOf(msg["summary.classes"], n(s.classCount)),
        listOf(msg["summary.instances"], n(s.instanceCount)),
        listOf(msg["summary.objectArrays"], n(s.objectArrayCount)),
        listOf(msg["summary.primitiveArrays"], n(s.primitiveArrayCount)),
        listOf(msg["summary.gcRoots"], n(s.gcRootCount)),
        listOf(msg["summary.totalShallow"], b(s.totalShallow)),
        listOf(msg["summary.reachable"], n(s.reachableCount)),
        listOf(msg["summary.reachableBytes"], b(s.reachableBytes)),
        listOf(msg["summary.analysisTime"], "${n(s.analysisMillis)} ms"),
    ))
    appendLine("> ${msg["note.sizes"]}\n")

    section("section.classes")
    table(listOf("col.class", "col.instances", "col.shallow", "col.retained"),
        r.classes.map { listOf(it.name, n(it.count), b(it.shallow), b(it.retained)) })

    section("section.packages")
    table(listOf("col.package", "col.objects", "col.shallow", "col.retained"),
        r.packages.take(50).map { listOf(it.name, n(it.count), b(it.shallow), b(it.retained)) })

    section("section.objects")
    table(listOf("col.id", "col.class", "col.shallow", "col.retained", "col.detail"),
        r.retainedObjects.map { listOf(it.id, it.className, b(it.shallow), b(it.retained), it.detail) })

    section("section.paths")
    appendLine("${msg["note.paths"]}\n")
    if (r.leaks.isEmpty()) appendLine("_${msg["misc.noData"]}_\n")
    r.leaks.forEachIndexed { i, leak ->
        appendLine("### ${i + 1}. ${md(leak.title)}\n")
        appendLine("${md(msg["paths.gcRoot", leak.gcRoot])}\n")
        appendLine("```")
        leak.nodes.forEach { node ->
            node.reference?.let { appendLine("    ↓ $it") }
            appendLine("${node.className} [${node.type}, ${node.status}]")
        }
        appendLine("```\n")
        appendLine("```mermaid\ngraph TD")
        leak.nodes.forEachIndexed { j, node ->
            appendLine("  n$j[\"${mermaidLabel(node.className)}\"]")
            if (j > 0) appendLine("  n${j - 1} -->|\"${mermaidLabel(node.reference ?: "")}\"| n$j")
        }
        appendLine("```\n")
    }

    section("section.gcRoots")
    table(listOf("col.type", "col.count"), r.gcRoots.map { listOf(it.name, n(it.count)) })

    section("section.threads")
    table(listOf("col.name", "col.id", "col.daemon", "col.priority", "col.retained", "col.frames"),
        r.threads.map { t ->
            val daemon = t.daemon?.let { if (it) msg["misc.yes"] else msg["misc.no"] }
            listOf(t.name, t.id, daemon, t.priority, b(t.retained), t.frames.size)
        })
    r.threads.filter { it.frames.isNotEmpty() }.forEach { t ->
        appendLine("<details><summary>${md(t.name).replace("<", "&lt;")}</summary>\n\n```")
        t.frames.forEach { f ->
            appendLine("at ${f.text}")
            f.locals.forEach { appendLine("    local: $it") }
        }
        appendLine("```\n</details>\n")
    }

    section("section.strings")
    table(listOf("col.value", "col.copies", "col.bytesEach", "col.wasted"),
        r.duplicateStrings.map { listOf("\"${it.value}\"", n(it.count), b(it.bytesEach), b(it.wasted)) })

    section("section.arrays")
    table(listOf("col.id", "col.type", "col.length", "col.bytes", "col.retained"),
        r.largestArrays.map { listOf(it.id, it.className, n(it.length), b(it.bytes), b(it.retained)) })

    section("section.loaders")
    table(listOf("col.id", "col.class", "col.classesLoaded", "col.retained"),
        r.classLoaders.map { listOf(it.id, it.className, n(it.classesLoaded), b(it.retained)) })

    appendLine("---\n\n_${msg["report.generatedBy", s.toolVersion]}_")
}

private fun resource(name: String) =
    HeapReport::class.java.getResource("/$name")?.readText() ?: error("missing resource: $name")

// heap strings are untrusted: keep "</script>" inside them from closing the tag
private fun safeJson(json: String) = json.replace("</", "<\\/")

fun toHtml(r: HeapReport, msg: Messages = Messages.load()): String {
    val i18n = safeJson(Json.encodeToString(msg.all())) + "; const LOCALE = " + Json.encodeToString(msg.tag)
    val data = safeJson(Json.encodeToString(r))
    // each placeholder is split out once, so inserted content is never re-scanned
    val (a, rest1) = resource("report.html").split("/*ECHARTS*/", limit = 2)
    val (b, rest2) = rest1.split("/*I18N*/", limit = 2)
    val (c, d) = rest2.split("/*DATA*/", limit = 2)
    return a + resource("echarts.min.js") + b + i18n + c + data + d
}
