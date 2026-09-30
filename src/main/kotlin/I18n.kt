package hprof

import java.util.Locale
import java.util.Properties

/**
 * UI texts loaded from `i18n/messages_<tag>.properties` (UTF-8). Adding a language = adding a file.
 * Missing keys fall back to the default language (pt-BR); `{0}`, `{1}`... are positional arguments.
 */
class Messages private constructor(val tag: String, private val props: Properties, private val base: Properties) {
    val locale: Locale = Locale.forLanguageTag(tag)

    operator fun get(key: String, vararg args: Any?): String {
        var text = props.getProperty(key) ?: base.getProperty(key) ?: key
        args.forEachIndexed { i, a -> text = text.replace("{$i}", a.toString()) }
        return text
    }

    fun all(): Map<String, String> =
        (base.stringPropertyNames() + props.stringPropertyNames()).associateWith { this[it] }

    companion object {
        const val DEFAULT = "pt-BR"

        internal fun read(tag: String): Properties? =
            Messages::class.java.getResourceAsStream("/i18n/messages_$tag.properties")?.use { input ->
                Properties().apply { load(input.reader(Charsets.UTF_8)) }
            }

        /** Resolves `pt_br`, `PT-BR`, `en-US` (→ `en`)...; unknown languages warn and use [DEFAULT]. */
        fun load(requested: String = DEFAULT, warn: (String) -> Unit = { System.err.println(it) }): Messages {
            val base = read(DEFAULT) ?: error("i18n/messages_$DEFAULT.properties missing")
            val normalized = Locale.forLanguageTag(requested.trim().replace('_', '-')).toLanguageTag()
            for (tag in listOf(normalized, normalized.substringBefore('-')).distinct()) {
                if (tag == DEFAULT) return Messages(DEFAULT, base, base)
                read(tag)?.let { return Messages(tag, it, base) }
            }
            val fallback = Messages(DEFAULT, base, base)
            warn(fallback["cli.unknownLanguage", requested, DEFAULT])
            return fallback
        }
    }
}
