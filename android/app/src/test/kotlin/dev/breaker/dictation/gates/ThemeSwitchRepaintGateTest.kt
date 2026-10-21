package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for the theme-repaint seam: the activity wires the wiring helper, the helper
 * reads the stored mode at fire time, and the SYSTEM x night colour mapping still routes
 * through `systemIsNight`. Each test reads the REAL post-change file, proves its pin holds
 * on that text, then applies a single mutant transform and asserts the anchor is gone —
 * so a regression that drops the call fails RED, never merely red.
 *
 * The behavioural SYSTEM x night coverage itself lives in WindowColorTest's
 * `SYSTEM with night returns the dark palette background` and
 * `SYSTEM night matches DARK and SYSTEM day matches LIGHT` (cited below), which this gate
 * does not re-code: it only pins the source so a silent drop is caught even without that suite.
 */
internal class ThemeSwitchRepaintGateTest {

    private fun flat(text: String): String = text.replace(Regex("""\s+"""), "")

    // 1. `the repaint helper reads the stored mode at fire time` — STALE-CAPTURED-MODE kill.
    @Test
    fun `the repaint helper reads the stored mode at fire time`() {
        val insetsCode = AppSourceFiles.strip(
            AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/AndroidInsets.kt"),
        ).code
        assertTrue(
            "paintWindowBackground must receive settings.load().themeMode, not a mode captured outside the lambda",
            flat(insetsCode).contains("paintWindowBackground(activity,settings.load().themeMode)"),
        )

        // STALE-CAPTURED-MODE: mutate in FLATTENED text (whitespace-stripped) so the replace
        // target actually exists. Real flattened anchor:
        //   ={paintWindowBackground(activity,settings.load().themeMode)}
        // Mutant  flattened:
        //   ={valmode=settings.load().themeMode;paintWindowBackground(activity,mode)}
        // The mode is now read OUTSIDE the lambda body (`{ ... }`), so the real anchor string
        // no longer occurs in the transformed text.
        val mutant = flat(insetsCode).replace(
            "={paintWindowBackground(activity,settings.load().themeMode)}",
            "={valmode=settings.load().themeMode;paintWindowBackground(activity,mode)}",
        )
        assertFalse("mutant hoists mode out of the lambda and drops the stored-mode argument",
            flat(mutant).contains("paintWindowBackground(activity,settings.load().themeMode)"))
    }

    // 2. `the activity wires the helper into the settings view` — REPAINT-CALL-DROPPED kill.
    @Test
    fun `the activity wires the helper into the settings view`() {
        val activityCode = AppSourceFiles.strip(
            AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"),
        ).code
        assertTrue(
            "onThemeChanged must delegate to repaintOnThemeChange(this, app.settingsStore)",
            flat(activityCode).contains("onThemeChanged=dev.breaker.dictation.wiring.repaintOnThemeChange(this,app.settingsStore)"),
        )

        // REPAINT-CALL-DROPPED: drop the settingsStore argument from the call.
        val mutant = flat(activityCode).replace(
            "onThemeChanged=dev.breaker.dictation.wiring.repaintOnThemeChange(this,app.settingsStore)",
            "onThemeChanged=dev.breaker.dictation.wiring.paintWindowBackground",
        )
        assertFalse("mutant drops the repaint call argument, so the wire is gone",
            mutant.contains("onThemeChanged=dev.breaker.dictation.wiring.repaintOnThemeChange(this,app.settingsStore)"))
    }

    // 3. `the activity file stays at its 300-line cap` — the cap pin (net-zero change).
    //    Counts lines the same way the banked AppLayoutGateTest does (newline count,
    //    no phantom line for a trailing newline); Kotlin String.lines() would report
    //    301 for this file and would be off by one.
    @Test
    fun `the activity file stays at its 300-line cap`() {
        val text = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt")
        val lines: Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + (if (text.endsWith("\n")) 0 else 1)
        assertTrue(
            "SettingsLauncherActivity.kt must stay exactly 300 lines (net-zero)",
            lines == 300,
        )
    }

    // 4. `the stale KDoc is gone` — AndroidInsets no longer claims it repaints on reopen.
    @Test
    fun `the stale KDoc is gone`() {
        val insetsCode = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/AndroidInsets.kt")
        assertTrue(
            "AndroidInsets KDoc must no longer claim 'repainted until the app is reopened'",
            !insetsCode!!.contains("repainted until"),
        )

        // Mutant that restores the old KDoc sentence must be caught: the stale
        // phrase is present in the mutant, so the pin's detection fires.
        assertTrue("mutant re-introduces the stale KDoc phrase, which the pin must catch",
            insetsCode!!.replace(
                "re-painted whenever [repaintOnThemeChange]'s callback fires.",
                "not repainted until the app is reopened.",
            ).contains("repainted until"))
    }

    // 5. `the night flag stays wired` — SYSTEM branch still routes through systemIsNight;
    //    behaviour covered by WindowColorTest, cited here and not re-coded.
    @Test
    fun `the night flag stays wired through systemIsNight`() {
        val insetsCode = AppSourceFiles.strip(
            AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/Insets.kt"),
        ).code
        assertTrue(
            "windowColorFor SYSTEM branch must still read systemIsNight",
            flat(insetsCode).contains("(mode==dev.breaker.dictation.core.model.ThemeMode.SYSTEM&&systemIsNight)"),
        )

        // NIGHT-IGNORED: drop systemIsNight from the SYSTEM branch.
        val mutant = flat(insetsCode).replace(
            "(mode==dev.breaker.dictation.core.model.ThemeMode.SYSTEM&&systemIsNight)",
            "(mode==dev.breaker.dictation.core.model.ThemeMode.SYSTEM)",
        )
        assertFalse("mutant drops systemIsNight from the SYSTEM branch",
            mutant.contains("(mode==dev.breaker.dictation.core.model.ThemeMode.SYSTEM&&systemIsNight)"))

        // Behavioural coverage already asserted by WindowColorTest:
        //   `SYSTEM with night returns the dark palette background`
        //   `SYSTEM night matches DARK and SYSTEM day matches LIGHT`
        // (cited for provenance only — not re-coded; the source pin above catches a silent drop)
    }
}
