package hprof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class I18nTest {
    private val languages = listOf("pt-BR", "en")

    @Test
    fun everyLanguageHasTheSameKeysAsTheDefault() {
        val base = Messages.read(Messages.DEFAULT)!!.stringPropertyNames()
        for (tag in languages) {
            val keys = Messages.read(tag)!!.stringPropertyNames()
            assertEquals(emptySet(), base - keys, "missing in $tag")
            assertEquals(emptySet(), keys - base, "unknown keys in $tag")
        }
    }

    @Test
    fun resolvesTagsAndFallsBack() {
        assertEquals("en", Messages.load("en-US") { error("no warning expected") }.tag)
        assertEquals("pt-BR", Messages.load("pt_br") { error("no warning expected") }.tag)
        val warnings = mutableListOf<String>()
        val fr = Messages.load("fr") { warnings += it }
        assertEquals("pt-BR", fr.tag)
        assertTrue(warnings.single().contains("fr"))
    }

    @Test
    fun formatsArgumentsAndKeepsUnknownKeys() {
        val en = Messages.load("en")
        assertEquals("Heap dump: a.hprof", en["report.title", "a.hprof"])
        assertEquals("no.such.key", en["no.such.key"])
        assertTrue(en["cli.usage"].contains("\n  --out"), "usage keeps indentation")
    }
}
