package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one place the window background is painted: the `paintWindowBackground`
 * helper in `wiring/Insets.kt`, called from `SettingsLauncherActivity.onCreate`.
 *
 * The window colour shows through the gutter around the page, so it must come
 * from the palette, never a literal, and it must be painted through the single
 * helper so the window colour has one place to change.
 */
internal class WindowBackgroundGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"
    private val insets: String = "kotlin/dev/breaker/dictation/wiring/AndroidInsets.kt"

    private val activityCode: String = AppSourceFiles.strip(AppSourceFiles.mainFile(activity)).code
    private val insetsCode: String = AppSourceFiles.strip(AppSourceFiles.mainFile(insets)).code

    private fun flat(text: String): String = text.replace(Regex("""\s+"""), "")

    @Test
    fun `the window background is painted on create`() {
        assertTrue(
            "SettingsLauncherActivity.onCreate must call paintWindowBackground, but the code does not",
            flat(activityCode).contains("paintWindowBackground(this,"),
        )
    }

    @Test
    fun `the window background is not painted inline in the activity`() {
        assertFalse(
            "the window colour must go through the helper, not an inline setBackgroundDrawable in the activity",
            flat(activityCode).contains("setBackgroundDrawable("),
        )
    }

    @Test
    fun `the helper exists in wiring`() {
        assertTrue(
            "wiring must hold the paintWindowBackground helper",
            flat(insetsCode).contains("funpaintWindowBackground("),
        )
    }

    @Test
    fun `the helper sets the window background from the palette`() {
        val flatInsets: String = flat(insetsCode)
        assertTrue(
            "the helper must set the window background drawable",
            flatInsets.contains("setBackgroundDrawable("),
        )
        assertTrue(
            "the helper must take the colour from windowColorFor, not a literal",
            flatInsets.contains("windowColorFor("),
        )
    }

    @Test
    fun `the margins object is assigned back to the view after setMargins`() {
        assertTrue(
            "applyPadding must assign the margins object back to view.layoutParams after setMargins",
            flat(insetsCode).contains("view.layoutParams=lp"),
        )
    }

    @Test
    fun `the window background call passes the stored theme mode`() {
        assertTrue(
            "paintWindowBackground must receive the stored themeMode, not a hardcoded mode",
            flat(activityCode).contains("paintWindowBackground(this,app.settingsStore.load().themeMode)"),
        )
    }
}
