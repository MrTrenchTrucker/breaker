package dev.breaker.dictation.ui.render

/*
 * The first-run view's theme line is pinned like the home and history pins. It keeps the phone's own
 * mode as a named value [phoneIsDark] and reads the shared reader through it, so what it must do is
 * exactly one `storedTheme(settings, phoneIsDark)` read resolving the stored choice with that mode. A
 * wrong argument compiles, so createFirstRunView's body is asserted as text and shown to fire when a
 * planted sample breaks it. A system-mode literal that reaches the body is reported as well.
 * The single read is pinned by the exactly-once check above.
 */

import dev.breaker.dictation.ui.gate.mainSourceOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ENTRY = "FirstRunEntry.kt"

/** [text] with the first [old] replaced by [new]; fails when that old text is not present. */
private fun editBody(text: String, old: String, new: String): String {
    val from = text.indexOf(old)
    assertTrue("the first-run entry body does not contain: $old", from >= 0)
    return text.substring(0, from) + new + text.substring(from + old.length)
}

private fun times(body: String, token: String): Int = Regex(Regex.escape(token)).findAll(body).count()

class FirstRunThemeGateTest {

    @Test
    fun `the first-run view reads the shared reader once through its named phone mode`() {
        val body = Regex("""(?s)fun\s+createFirstRunView\s*\([^}]*\}""")
            .find(mainSourceOf(ENTRY))!!.value
        assertTrue("the phone's own mode is a named value", body.contains("val phoneIsDark"))
        assertTrue("first-run: reads storedTheme once through phoneIsDark", times(body, "storedTheme(") == 1)
        assertTrue("reads the reader resolved with the named phone mode", body.contains("storedTheme(settings, phoneIsDark)"))
    }

    @Test
    fun `a system-mode literal does not reach the first-run body`() {
        val body = Regex("""(?s)fun\s+createFirstRunView\s*\([^}]*\}""")
            .find(mainSourceOf(ENTRY))!!.value
        assertFalse("first run: a system-mode literal reached the entry body", body.contains("shownMode(StoredThemeMode.SYSTEM"))
    }

    @Test
    fun `replacing the theme line breaks its reader`() {
        val entry = mainSourceOf(ENTRY)
        val original = "val theme = storedTheme(settings, phoneIsDark)"
        assertFires(editBody(entry, original, "val theme = StoredTheme.system"), entry, original)
    }

    private fun assertFires(sample: String, entry: String, original: String) {
        assertTrue("the planted replacement must have changed the body", sample != entry)
        assertFalse("a system-literal line should not read storedTheme exactly once", times(sample, "storedTheme(") == 1)
    }
}
