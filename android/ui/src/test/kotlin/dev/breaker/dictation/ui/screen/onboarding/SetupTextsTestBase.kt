package dev.breaker.dictation.ui.screen.onboarding

import java.lang.reflect.Modifier

/**
 * Shared fixtures for the set-up text tests: the map of every String constant of the texts object, and the
 * helpers that look up one text and count or match words in it. Each text test class extends this class.
 */
internal abstract class SetupTextsTestBase {

    protected fun wordPattern(word: String) =
        Regex("(?<![A-Za-z0-9-])" + Regex.escape(word) + "(?![A-Za-z0-9-])", RegexOption.IGNORE_CASE)

    /** Every String constant of the texts object, by name, read from its fields. */
    private fun constants(): Map<String, String> =
        SetupTexts::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { field ->
                field.setAccessible(true)
                field.name to (field.get(null) as String)
            }

    protected val texts = constants()

    protected fun text(name: String): String = texts[name] ?: error("no constant named $name")

    protected fun countOf(text: String, needle: String): Int = text.split(needle).size - 1
}
