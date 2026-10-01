package hprof

import java.io.File
import java.util.Properties
import kotlin.system.exitProcess

/** Filled from build.gradle.kts `version` at build time (see processResources). */
val VERSION: String = Messages::class.java.getResourceAsStream("/version.properties")
    ?.use { Properties().apply { load(it) }.getProperty("version") } ?: "dev"

private val NAME_VERSION = "hprof-analyzer $VERSION"

fun main(args: Array<String>) {
    if ("--version" in args || "-V" in args) { println(NAME_VERSION); return }
    // language first, so usage and errors come out in it
    val langAt = args.indexOf("--i18n")
    val msg = Messages.load(args.getOrNull(langAt + 1)?.takeIf { langAt >= 0 } ?: Messages.DEFAULT)
    fun fail(text: String): Nothing {
        System.err.println(msg["cli.error", text] + "\n\n" + msg["cli.usage"])
        exitProcess(2)
    }
    if (args.isEmpty() || "-h" in args || "--help" in args) { println(NAME_VERSION + "\n\n" + msg["cli.usage"]); return }

    var input: File? = null
    var out = File(".")
    var formats = setOf("html", "md")
    var top = 50
    var retained = true
    var leakClasses = emptySet<String>()
    val it = args.iterator()
    fun value(flag: String) = if (it.hasNext()) it.next() else fail(msg["cli.missingValue", flag])
    while (it.hasNext()) {
        when (val a = it.next()) {
            "--out" -> out = File(value(a))
            "--format" -> formats = value(a).split(',').map { f -> f.trim().lowercase() }.toSet()
            "--top" -> top = value(a).toIntOrNull()?.takeIf { n -> n > 0 } ?: fail(msg["cli.topInvalid"])
            "--no-retained" -> retained = false
            "--leak-class" -> leakClasses = value(a).split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            "--i18n" -> value(a) // already handled
            else -> if (a.startsWith("--") || input != null) fail(msg["cli.unknownArg", a]) else input = File(a)
        }
    }
    val file = input ?: fail(msg["cli.noInput"])
    if (!file.isFile) fail(msg["cli.fileNotFound", file])
    val unknown = formats - setOf("html", "md")
    if (unknown.isNotEmpty()) fail(msg["cli.unknownFormat", unknown])

    System.err.println(NAME_VERSION)
    val report = analyze(file, Options(top, retained, leakClasses, msg))
    out.mkdirs()
    val base = file.nameWithoutExtension
    if ("md" in formats) File(out, "$base.md").also { f -> f.writeText(toMarkdown(report, msg)); println(f.path) }
    if ("html" in formats) File(out, "$base.html").also { f -> f.writeText(toHtml(report, msg)); println(f.path) }
}
