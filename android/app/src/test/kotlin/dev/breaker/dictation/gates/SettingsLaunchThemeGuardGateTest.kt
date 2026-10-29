package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the settings-load crash guard in the launch path. [SettingsLauncherActivity.onCreate] paints
 * the window background from `themeModeOrDefault(app.settingsStore, ...)`, which falls back to the
 * default theme when the store cannot be read; it must never read `settingsStore.load().themeMode`
 * directly, because that throws on an unreadable file and crashes create.
 *
 * Reads the REAL activity source, proves the pin holds on that text, then applies the single mutant
 * that reverts onCreate to the raw call and asserts the anchor is gone — so a regression that drops
 * the guard fails RED, never merely red. Behaviour of the helper itself (throw -> default, read ->
 * stored) lives in ThemeModeOrDefaultTest; this test only pins the wiring so a silent drop is caught.
 */
internal class SettingsLaunchThemeGuardGateTest {

    private fun flat(text: String): String = text.replace(Regex("""\s+"""), "")

    @Test
    fun `onCreate paints the window through themeModeOrDefault, not a raw store load`() {
        val activityCode = flat(
            AppSourceFiles.strip(
                AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"),
            ).code,
        )

        assertTrue(
            "onCreate must route the launch theme through themeModeOrDefault(app.settingsStore, <default>)",
            activityCode.contains("themeModeOrDefault(app.settingsStore,"),
        )
        assertFalse(
            "onCreate must not read settingsStore.load().themeMode directly — that throws on an unreadable file",
            activityCode.contains("settingsStore.load().themeMode"),
        )

        // MUTANT: revert onCreate to the raw, unguarded load().themeMode. The pin's negative anchor
        // must fire on this transform, so the mutant is provably killed.
        val mutant = activityCode.replace(
            "themeModeOrDefault(app.settingsStore,dev.breaker.dictation.core.model.ThemeMode.SYSTEM)",
            "app.settingsStore.load().themeMode",
        )
        assertTrue(
            "mutant reintroduces the raw settingsStore.load().themeMode call, which the pin must catch",
            mutant.contains("settingsStore.load().themeMode"),
        )
    }
}
