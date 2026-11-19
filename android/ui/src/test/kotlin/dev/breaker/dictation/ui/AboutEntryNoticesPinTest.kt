package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.gate.mainSourceOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The About entry takes the model notices as a nullable defaulted last parameter, has no brace in its signature,
 * and hands the notices to the screen. Pinned as text: the entry needs an Android context, so no JVM test builds it.
 * Hand-traced only: nothing here has been compiled or run.
 */
class AboutEntryNoticesPinTest {
    private val entry = mainSourceOf("SettingsEntry.kt").replace(Regex("\\s+"), " ")

    /** The text of the createAboutView signature, from its name up to the closing parenthesis and the return type. */
    private fun aboutSignature(): String {
        val start = entry.indexOf("fun createAboutView(")
        val end = entry.indexOf("): android.view.View {", start)
        assertTrue("the signature of createAboutView was not found", start >= 0 && end > start)
        return entry.substring(start, end)
    }

    @Test
    fun `the about entry takes a nullable notice list with a null default as its last parameter`() {
        val signature = aboutSignature().trimEnd().removeSuffix(",").trimEnd()
        assertTrue("the about entry must take modelNotices nullable with a null default", signature.endsWith("modelNotices: List<String>? = null"))
    }

    @Test
    fun `no brace sits inside the about signature`() {
        assertFalse("createAboutView has a brace in its signature", aboutSignature().contains("{"))
    }

    @Test
    fun `the about entry hands the notices to the screen and keeps its theme line`() {
        assertTrue("the about entry must fall back to an empty list in its body", entry.contains("val notices: List<String> = modelNotices ?: emptyList()"))
        assertTrue("the about entry must give the notices to the screen", entry.contains("AboutHostView(context, AboutScreen(notices), theme, ScreenRenderer(context))"))
        assertTrue("the about entry must keep its stored theme line", entry.contains("val theme = storedTheme(settings, isNightMode(context.resources.configuration.uiMode))"))
    }
}
