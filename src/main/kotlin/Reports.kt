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

/** System properties shown up front; the rest goes in a collapsed table. */
private val KEY_PROPS = listOf(
    "java.version", "java.vendor", "java.vm.name", "java.vm.version", "java.runtime.version", "os.name", "os.version",
    "os.arch", "user.dir", "user.timezone", "file.encoding", "java.home", "sun.java.command",
)

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
    r.appScope?.let { appendLine("> ${md(msg["report.appScope", it])}\n") }
    if (r.warnings.isNotEmpty()) {
        section("section.warnings")
        appendLine("> ${msg["note.warnings"]}\n")
        r.warnings.forEach { appendLine("- ⚠️ ${md(it)}") }
        appendLine()
    }
    if (r.appScope == null) {
        section("section.health")
        if (r.health.isEmpty()) appendLine("✅ ${msg["health.ok"]}\n")
        r.health.forEach { h ->
            val icon = when (h.severity) { CRITICAL -> "🔴"; WARNING -> "🟠"; else -> "🔵" }
            appendLine("- $icon **${msg["health.${h.severity}"]}**: ${md(msg.get("health.${h.key}", *h.args.toTypedArray()))}")
        }
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
        listOf(msg["summary.unreachable"], b(s.reachableBytes?.let { s.totalShallow - it })),
        listOf(msg["summary.analysisTime"], "${n(s.analysisMillis)} ms"),
    ))
    appendLine("> ${msg["note.sizes"]}\n")

    section("section.jvm")
    appendLine("**${msg["jvm.frameworks"]}:** ${md(r.frameworks.joinToString().ifEmpty { msg["jvm.none"] })}\n")
    table(listOf("col.property", "col.value"), KEY_PROPS.mapNotNull { k -> r.jvm[k]?.let { listOf(k, it) } })
    appendLine("**${msg["jvm.vmArgs"]}:**\n")
    if (r.vmArgs.isEmpty()) appendLine("_${msg["note.vmArgs"]}_\n")
    else appendLine("```\n${r.vmArgs.joinToString("\n")}\n```\n")
    if (r.jvm.isNotEmpty()) {
        appendLine("<details><summary>${msg["jvm.allProps", r.jvm.size]}</summary>\n")
        table(listOf("col.property", "col.value"), r.jvm.map { listOf(it.key, it.value) })
        appendLine("</details>\n")
    }

    r.proxies?.let { p ->
        section("section.proxies")
        appendLine("${msg["note.proxies"]}\n")
        val pct = if (s.classCount > 0) String.format(msg.locale, "%.1f", 100.0 * p.generatedClasses / s.classCount) else "0"
        appendLine("**${msg["proxies.total", n(p.generatedClasses), pct]}** " +
            p.byGenerator.joinToString(" · ") { "${it.name}: ${n(it.count)}" } + "\n")
        val suspects = p.groups.filter { it.suspect }
        if (suspects.isNotEmpty()) {
            appendLine("> ⚠️ **${msg["proxies.suspects", suspects.size]}**")
            suspects.forEach { appendLine("> - ${md(it.generator)} · `${md(it.baseClass)}`: ${n(it.classes)} classes, ${n(it.loaders)} loaders") }
            appendLine(">\n> ${msg["note.proxiesTip"]}\n")
        }
        table(listOf("col.generator", "col.baseClass", "col.classes", "col.loaders", "col.instances", "col.example", "col.suspect"),
            p.groups.map { listOf(it.generator, it.baseClass, n(it.classes), n(it.loaders), n(it.instances), it.example,
                if (it.suspect) "⚠️ ${msg["misc.yes"]}" else msg["misc.no"]) })
    }

    section("section.classes")
    table(listOf("col.class", "col.instances", "col.shallow", "col.retained"),
        r.classes.map { listOf(it.name, n(it.count), b(it.shallow), b(it.retained)) })

    section("section.packages")
    table(listOf("col.package", "col.objects", "col.shallow", "col.retained"),
        r.packages.take(50).map { listOf(it.name, n(it.count), b(it.shallow), b(it.retained)) })

    section("section.objects")
    table(listOf("col.id", "col.class", "col.shallow", "col.retained", "col.detail"),
        r.retainedObjects.map { listOf(it.id, it.className, b(it.shallow), b(it.retained), it.detail) })

    r.leakSuspects?.let { lk ->
        section("section.leaks")
        appendLine("> ${msg["note.leaks"]}\n")
        appendLine("**${msg["leaks.objects"]}:** ${n(lk.leakingObjects)}\n")
        table(listOf("col.kind", "col.description", "col.occurrences", "col.retained"),
            lk.groups.map { listOf(msg["leaks.kind.${it.kind}"], it.description, n(it.occurrences), b(it.retained)) })
        lk.groups.forEachIndexed { i, g ->
            appendLine("**${i + 1}. ${md(g.trace.title)}** (${md(msg["paths.gcRoot", g.trace.gcRoot])})\n")
            appendLine("```")
            g.trace.nodes.forEach { node ->
                node.reference?.let { appendLine("    ↓ $it") }
                appendLine("${node.className} [${node.status}${if (node.reason.isNotEmpty()) ": " + node.reason else ""}]")
            }
            appendLine("```\n")
        }
    }

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

    val full = r.appScope == null // the application view drops sections that cannot be split by class
    if (r.mergedPaths.isNotEmpty()) {
        section("section.merged")
        appendLine("> ${msg["note.merged"]}\n")
        r.mergedPaths.forEach { m ->
            appendLine("### ${md(m.className)}\n")
            appendLine("${msg["merged.sampled", n(m.instances), n(m.sampled)]}\n")
            table(listOf("col.dominator", "col.class", "col.instances"),
                m.links.map { listOf(it.source.substringAfter('|'), it.target.substringAfter('|'), n(it.value)) })
        }
    }
    r.retainedViews?.let { rv ->
        section("section.retainedViews")
        appendLine("> ${msg["note.retainedViews"]}\n")
        appendLine("### ${msg["retained.byLoader"]}\n")
        table(listOf("col.loaders", "col.package", "col.retained"),
            rv.byLoader?.children.orEmpty().flatMap { l -> l.children.orEmpty().map { p -> listOf(l.name, p.name, b(p.value)) } })
        appendLine("### ${msg["retained.staticFields"]}\n")
        table(listOf("col.owner", "col.field", "col.valueClass", "col.retained"),
            rv.staticFields.map { listOf(it.owner, it.field, it.valueClass, b(it.retained)) })
        appendLine("### ${msg["retained.dominators"]}\n")
        table(listOf("col.class", "col.dominator", "col.count", "col.retained"),
            rv.dominators.flatMap { c -> c.by.map { listOf(c.className, it.className, n(it.count), b(it.retained)) } })
    }
    if (full) {
        section("section.gcRoots")
        table(listOf("col.type", "col.count"), r.gcRoots.map { listOf(it.name, n(it.count)) })
    }

    section("section.threads")
    table(listOf("col.name", "col.id", "col.state", "col.daemon", "col.priority", "col.retained", "col.localsRetained",
        "col.tlRetained", "col.threadLocals", "col.stale", "col.frames"),
        r.threads.map { t ->
            val daemon = t.daemon?.let { if (it) msg["misc.yes"] else msg["misc.no"] }
            listOf(t.name, t.id, t.state, daemon, t.priority, b(t.retained), b(t.localsRetained), b(t.threadLocalsRetained),
                n(t.threadLocals), n(t.staleThreadLocals), t.frames.size)
        })
    r.threads.filter { it.frames.isNotEmpty() }.forEach { t ->
        appendLine("<details><summary>${md(t.name).replace("<", "&lt;")}</summary>\n\n```")
        t.frames.forEach { f ->
            appendLine("at ${f.text}")
            f.locals.forEach { appendLine("    local: $it") }
        }
        appendLine("```\n</details>\n")
    }

    r.concurrency?.let { c ->
        section("section.concurrency")
        appendLine("> ${msg["note.concurrency"]}\n")
        appendLine("### ${msg["conc.states"]}\n")
        table(listOf("col.state", "col.count"), c.states.map { listOf(it.name, n(it.count)) })
        appendLine("**${msg["conc.virtual"]}:** ${n(c.virtualThreads)} · ${msg["conc.virtualRetained"]}: ${b(c.virtualRetained)}\n")
        appendLine("### ${msg["conc.pools"]}\n")
        table(listOf("col.type", "col.class", "col.id", "col.core", "col.max", "col.threads", "col.queue", "col.queued", "col.completed"),
            c.pools.map { listOf(it.kind, it.className, it.id, it.core, it.max, it.threads, it.queueType, n(it.queued), n(it.completed)) })
        appendLine("### ${msg["conc.tlValues"]}\n")
        table(listOf("col.class", "col.count"), c.threadLocalValues.map { listOf(it.name, n(it.count)) })
        appendLine("### ${msg["conc.stackGroups"]}\n")
        if (c.stackGroups.isEmpty()) appendLine("_${msg["misc.noData"]}_\n")
        c.stackGroups.forEach { g ->
            appendLine("<details><summary>${msg["conc.group", g.count]}: ${md(g.threads.joinToString()).replace("<", "&lt;")}</summary>\n\n```")
            g.frames.forEach { appendLine("at $it") }
            appendLine("```\n</details>\n")
        }
    }

    if (full) {
        section("section.strings")
        table(listOf("col.value", "col.copies", "col.bytesEach", "col.wasted"),
            r.duplicateStrings.map { listOf("\"${it.value}\"", n(it.count), b(it.bytesEach), b(it.wasted)) })
    }

    section("section.arrays")
    table(listOf("col.id", "col.type", "col.length", "col.bytes", "col.retained"),
        r.largestArrays.map { listOf(it.id, it.className, n(it.length), b(it.bytes), b(it.retained)) })

    if (full) {
        section("section.loaders")
        table(listOf("col.id", "col.class", "col.classesLoaded", "col.retained"),
            r.classLoaders.map { listOf(it.id, it.className, n(it.classesLoaded), b(it.retained)) })
    }

    fun sub(key: String) = appendLine("### ${msg[key]}\n")
    if (r.inspections.isNotEmpty()) {
        section("section.inspections")
        appendLine("> ${msg["note.inspections"]}\n")
        r.inspections.forEach { fw ->
            appendLine("### ${msg[fw.key]}\n")
            appendLine(fw.metrics.joinToString(" · ") { "**${msg[it.key]}:** ${if (it.bytes) b(it.value) else n(it.value)}" } + "\n")
            fw.tables.forEach { tb ->
                appendLine("**${msg[tb.title]}**\n")
                table(tb.columns.map { it.key }, tb.rows.map { row ->
                    row.mapIndexed { i, cell ->
                        when (tb.columns[i].type) {
                            "bytes" -> b(cell?.toLongOrNull())
                            "num" -> cell?.toLongOrNull()?.let(::n) ?: cell
                            else -> cell
                        }
                    }
                })
            }
        }
    }

    r.graph?.let { g ->
        section("section.graph")
        appendLine("> ${msg["note.graph"]}\n")
        appendLine("**${msg["graph.maxDepth"]}:** ${n(g.maxDepth)}\n")
        appendLine("### ${msg["graph.depths"]}\n")
        table(listOf("graph.maxDepth", "col.objects"), g.depths.map { listOf(it.label, n(it.count)) })
        appendLine("### ${msg["graph.fanIn"]}\n")
        table(listOf("col.id", "col.class", "col.degree", "col.retained"), g.fanIn.map { listOf(it.id, it.className, n(it.degree), b(it.retained)) })
        appendLine("### ${msg["graph.fanOut"]}\n")
        table(listOf("col.id", "col.class", "col.degree", "col.retained"), g.fanOut.map { listOf(it.id, it.className, n(it.degree), b(it.retained)) })
        appendLine("### ${msg["graph.classEdges"]}\n")
        table(listOf("col.source", "col.target", "col.count"), g.classEdges.map { listOf(it.source, it.target, n(it.count)) })
    }

    r.metadata?.let { m ->
        section("section.metadata")
        appendLine("> ${msg["note.metadata"]}\n")
        val ratio = if (s.totalShallow > 0) String.format(msg.locale, "%.2f", s.fileSize.toDouble() / s.totalShallow) else "-"
        appendLine("**${msg["meta.ratio"]}:** $ratio · **${msg["meta.gcs"]}:** ${md(m.garbageCollectors.joinToString().ifEmpty { "-" })}\n")
        if (m.memoryFlags.isNotEmpty()) appendLine("**${msg["meta.flags"]}:** `${m.memoryFlags.joinToString(" ")}`\n")
        appendLine("### ${msg["meta.records"]}\n")
        table(listOf("col.type", "col.count"), m.records.map { listOf(it.name, n(it.count)) })
    }

    r.waste?.let { w ->
        section("section.waste")
        appendLine("> ${msg["note.waste"]}\n")
        sub("waste.collections")
        table(listOf("col.type", "col.count", "col.empty", "col.emptyBytes", "col.size", "col.capacity", "col.unused"),
            w.collections.map { listOf(it.type, n(it.count), n(it.empty), b(it.emptyBytes), n(it.size), n(it.capacity), b(it.unusedBytes)) })
        sub("waste.fill")
        table(listOf("col.size", "col.count"), w.fillRatio.map { listOf(it.label, n(it.count)) })
        sub("waste.sizes")
        table(listOf("col.size", "col.count"), w.sizes.map { listOf(it.label, n(it.count)) })
        sub("waste.arrays")
        table(listOf("col.kind", "col.type", "col.count", "col.bytes"),
            w.arrays.map { listOf(msg["waste.kind.${it.kind}"], it.type, n(it.count), b(it.bytes)) })
        sub("waste.dupArrays")
        table(listOf("col.type", "col.length", "col.copies", "col.bytesEach", "col.wasted"),
            w.duplicateArrays.map { listOf(it.type, n(it.length), n(it.count), b(it.bytesEach), b(it.wasted)) })
        sub("waste.boxing")
        appendLine("${msg["note.boxing"]}\n")
        table(listOf("col.type", "col.instances", "col.bytes", "col.redundant"),
            w.boxing.map { listOf(it.type, n(it.count), b(it.bytes), n(it.redundant)) })
        sub("waste.nullFields")
        table(listOf("col.class", "col.instances", "col.field", "col.nullPct"),
            w.nullFields.flatMap { c -> c.fields.filter { it.nullPct >= 90 }.map { f ->
                listOf(c.className, n(c.instances), f.name, String.format(msg.locale, "%.1f%%", f.nullPct)) } })
        sub("waste.overhead")
        val o = w.overhead
        table(listOf("summary.metric", "col.bytes"), listOf(
            listOf(msg["overhead.data"], b(o.data)), listOf(msg["overhead.header"], b(o.header)), listOf(msg["overhead.padding"], b(o.padding))))
        sub("waste.strings")
        val st = w.strings
        table(listOf("summary.metric", "col.count"), listOf(
            listOf(msg["strings.latin1"], n(st.latin1)), listOf(msg["strings.utf16"], n(st.utf16)),
            listOf(msg["strings.empty"], n(st.empty)), listOf(msg["strings.long"], n(st.longStrings))))
        sub("waste.prefixes")
        table(listOf("col.prefix", "col.count", "col.bytes"), st.prefixes.map { listOf("\"${it.prefix}\"", n(it.count), b(it.bytes)) })
    }

    r.references?.let { rf ->
        section("section.references")
        appendLine("> ${msg["note.references"]}\n")
        table(listOf("col.type", "col.count", "col.withReferent", "col.referentBytes"),
            rf.kinds.map { listOf(it.kind, n(it.count), n(it.withReferent), b(it.referentBytes)) })
        appendLine("**${msg["refs.finalizerQueue"]}:** ${rf.finalizerQueue?.let(::n) ?: msg["misc.unknown"]}\n")
        sub("refs.byClass")
        table(listOf("col.class", "col.count"), rf.byClass.map { listOf(it.name, n(it.count)) })
        sub("refs.finalizer")
        table(listOf("col.class", "col.count"), rf.finalizable.map { listOf(it.name, n(it.count)) })
        if (rf.cleaners.isNotEmpty()) {
            sub("refs.cleaners")
            table(listOf("col.class", "col.count"), rf.cleaners.map { listOf(it.name, n(it.count)) })
        }
    }

    r.offHeap?.let { oh ->
        section("section.offHeap")
        appendLine("> ${msg["note.offHeap"]}\n")
        val d = oh.direct
        table(listOf("col.type", "col.count", "col.bytes"), listOf(
            listOf(msg["offheap.owners"], n(d.owners), b(d.ownerBytes)),
            listOf(msg["offheap.views"], n(d.views), b(d.viewBytes)),
            listOf(msg["offheap.mapped"], n(d.mapped), b(d.mappedBytes))))
        oh.netty?.let { appendLine(msg["offheap.netty", n(it.chunks), b(it.allocated), b(it.used)] + "\n") }
        sub("offheap.resources")
        table(listOf("col.class", "col.count", "col.open"), oh.resources.map { listOf(it.className, n(it.count), n(it.open)) })
    }

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
