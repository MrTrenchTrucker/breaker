package dev.breaker.dictation.ui.gate

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text gate for the theme seam. It reads [SettingsEntry] and
 * [ThemeBinding] from their real paths, flattens whitespace to a single token
 * before matching (so formatting never hides or reveals a pin), and pins:
 *
 *   (a) createSettingsView's signature carries `onThemeChanged: (() -> Unit)?`
 *       with a default of null.
 *   (b) the binding call `bindThemeChanges(themes, onThemeChanged)` appears
 *       exactly once, and before the SettingsHostView( construction,
 *   (c) ThemeBinding.kt's body registers exactly one observer.
 *
 * M1 (drop the bindThemeChanges line) and M2 (observer body stops calling
 * onThemeChanged) are each shown to FAIL a pin — an "edited-sample" transform of
 * the real text, asserted RED, so this is a test kill, never a compiler error.
 */
class SettingsThemeSeamGateTest {

    private companion object {
        private const val MODULE_MARKER = "src/main/kotlin/dev/breaker/dictation/ui"

        /** The ui module directory, found the same way [GateSelfTest] finds its own. */
        fun findModuleDir(): File {
            var level: File? = File("").absoluteFile
            while (level != null) {
                if (File(level, MODULE_MARKER).isDirectory) return level
                val nested = File(level, "android/ui/$MODULE_MARKER")
                if (nested.isDirectory) return File(level, "android/ui")
                level = level.parentFile
            }
            error("the ui module was not found at or above ${File("").absolutePath}")
        }
    }

    private val MODULE_DIR: File = findModuleDir()

    @Test
    fun `createSettingsView carries onThemeChanged with a default`() {
        val entry = File(MODULE_DIR, "src/main/kotlin/dev/breaker/dictation/ui/SettingsEntry.kt")
            .readText()
        val flat = flat(entry)
        assertTrue(
            "onThemeChanged: (() -> Unit)? with default null not found in createSettingsView signature",
            flat.contains("onThemeChanged: (() -> Unit)? = null"),
        )
    }

    @Test
    fun `bindThemeChanges occurs exactly once and before SettingsHostView`() {
        val entry = File(MODULE_DIR, "src/main/kotlin/dev/breaker/dictation/ui/SettingsEntry.kt")
            .readText()
        val flat = flat(entry)

        val bindCount = countOccurrences(flat, "bindThemeChanges(themes, onThemeChanged)")
        assertEquals(
            "bindThemeChanges should occur exactly once; found $bindCount",
            1,
            bindCount,
        )

        val bindPos = flat.indexOf("bindThemeChanges(themes, onThemeChanged)")
        val hostPos = flat.indexOf("SettingsHostView(")
        assertTrue(
            "bindThemeChanges (line $bindPos) must come before SettingsHostView (line $hostPos)",
            bindPos < hostPos && bindPos >= 0,
        )
    }

    @Test
    fun `themeBinding registers exactly one observer`() {
        val binding = File(MODULE_DIR, "src/main/kotlin/dev/breaker/dictation/ui/theme/ThemeBinding.kt")
            .readText()
        // The real text is `themes.addObserver { onThemeChanged() }`; stripped it reads as
        // exactly one occurrence of the anchor. On correct code this holds; that's the pin.
        val stripped = binding.replace(Regex("\\s+"), "")
        assertEquals(
            "addObserver{onThemeChanged()} should occur exactly once in ThemeBinding.kt",
            1,
            countOccurrences(stripped, "addObserver{onThemeChanged()}"),
        )
    }

    @Test
    fun `M1 dropped bindThemeChanges line fails the one-occurrence pin`() {
        val entry = File(MODULE_DIR, "src/main/kotlin/dev/breaker/dictation/ui/SettingsEntry.kt")
            .readText()
        // M1: remove the binding call entirely — a transform of the real text.
        val mutant = flat(entry).replace("bindThemeChanges(themes, onThemeChanged)", "")

        val bindCount = countOccurrences(mutant, "bindThemeChanges(themes, onThemeChanged)")
        // A test kill: the pin must NOT hold on the mutant (RED), never a compiler error.
        assertTrue(
            "M1 dropped bindThemeChanges but the one-occurrence pin wrongly passed",
            bindCount != 1,
        )

        val bindPos = mutant.indexOf("bindThemeChanges(themes, onThemeChanged)")
        val hostPos = mutant.indexOf("SettingsHostView(")
        // The ordering pin: a dropped call cannot sit before SettingsHostView.
        assertTrue(
            "M1 drop of bindThemeChanges must break the before-SettingsHostView ordering",
            !(bindPos >= 0 && bindPos < hostPos),
        )
    }

    @Test
    fun `M2 observer body stops calling onThemeChanged fails the single-observer pin`() {
        val binding = File(MODULE_DIR, "src/main/kotlin/dev/breaker/dictation/ui/theme/ThemeBinding.kt")
            .readText()
        // M2: transform the observer lambda so it no longer calls onThemeChanged. The exact
        // flattened anchor `addObserver{onThemeChanged()}` is then ABSENT, which is the kill.
        val mutant = binding.replace("addObserver { onThemeChanged() }", "addObserver {}")

        // Anchor present once on correct text (test 3 keeps passing), absent in the M2 mutant.
        assertTrue(
            "the anchor must be present exactly once on real ThemeBinding.kt",
            countOccurrences(binding.replace(Regex("\\s+"), ""), "addObserver{onThemeChanged()}") == 1,
        )
        assertEquals(
            "M2 observer-body drop must make the anchor absent (test kill)",
            0,
            countOccurrences(mutant.replace(Regex("\\s+"), ""), "addObserver{onThemeChanged()}"),
        )
    }

    /** Flatten to a single tokenised string so whitespace never hides or reveals a pin. */
    private fun flat(text: String) =
        text.split("\n").joinToString(" ").replace(Regex("\\s+"), " ")

    /** How many non-overlapping occurrences of [needle] appear in [haystack]. */
    private fun countOccurrences(haystack: String, needle: String): Int {
        var index = 0
        var hits = 0
        while (true) {
            val found = haystack.indexOf(needle, index)
            if (found < 0) return hits
            hits++
            index = found + needle.length
        }
    }
}
