package dev.breaker.dictation.ui.render

/*
 * The home screen's theme line is pinned like the setup and history pins. What it must do is read the
 * shared reader [storedTheme] once, resolving the stored choice with the phone's own mode. A wrong
 * argument in that one body compiles, so the body of createHomeView is asserted as text; a rule reads
 * the real file and is also shown to fire when a planted sample breaks it, so a rule that could never
 * fail does not pass for the wrong reason. The home entry reads the shared reader once, so a
 * system-mode literal in its body is reported.
 */

import dev.breaker.dictation.ui.gate.mainSourceOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ENTRY = "HomeEntry.kt"

/** [text] with the first [old] replaced by [new]; fails when that old text is not present. */
private fun editBody(text: String, old: String, new: String): String {
    val from = text.indexOf(old)
    assertTrue("the home entry body does not contain: $old", from >= 0)
    return text.substring(0, from) + new + text.substring(from + old.length)
}

private fun times(body: String, token: String): Int = Regex(Regex.escape(token)).findAll(body).count()

private fun containsStoredThemeOnce(body: String): Boolean = times(body, "storedTheme(") == 1

class HomeThemeGateTest {

    private val body: String =
        Regex("""(?s)fun\s+createHomeView\s*\([^}]*\}""")
            .find(mainSourceOf(ENTRY))!!.value

    @Test
    fun `the home screen reads the shared reader once and resolves it with the phone's own mode`() {
        assertTrue("the phone's own mode is resolved inline", body.contains("isNightMode(context.resources.configuration.uiMode)"))
        assertTrue("home: reads storedTheme exactly once", containsStoredThemeOnce(body))
    }

    @Test
    fun `a system-mode literal does not reach the home body`() {
        assertFalse("home: a system-mode literal reached the entry body", body.contains("shownMode(StoredThemeMode.SYSTEM"))
    }

    @Test
    fun `replacing the theme line with a system-literal theme breaks its reader`() {
        val entry = mainSourceOf(ENTRY)
        val original = "val theme = storedTheme(settings, isNightMode(context.resources.configuration.uiMode))"
        assertFires(editBody(entry, original, "val theme = StoredTheme.system"), entry, original)
    }

    @Test
    fun `dropping the settings argument breaks its reader`() {
        val entry = mainSourceOf(ENTRY)
        val original = "val theme = storedTheme(settings, isNightMode(context.resources.configuration.uiMode))"
        val sample = editBody(entry, original, "val theme = storedTheme(isNightMode(context.resources.configuration.uiMode))")
        assertTrue("the planted replacement must have changed the body", sample != entry)
        assertFalse("a storedTheme call without the settings store still reads as the reader's line",
            sample.contains("storedTheme(settings, isNightMode(context.resources.configuration.uiMode))"))
    }

    private fun assertFires(sample: String, entry: String, original: String) {
        assertTrue("the planted replacement must have changed the body", sample != entry)
        assertFalse("a system-literal or no-args line should not read storedTheme once", containsStoredThemeOnce(sample))
    }
}
