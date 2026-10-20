package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording look gate: onDraw draws the cancel and send controls only while the tile face is
 * RECORDING. Each check is a pure function from source text to a list of violations; every test runs
 * it first on short samples (a wrong one must be reported, a correct one must not) and only then on
 * the real file. Comments are blanked and string literals kept before the rule runs.
 */
class ScreenMicRecordingLookGateTest {

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("android_overlay: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("android_overlay: $what must not be reported", emptyList<String>(), problems)

    /** The single-record rule: one `if (...) { ... ControlArt.cancel ... ControlArt.send ... }` block guarding both controls. */
    private val lookRule = Regex(
        """if\s*\(\s*face\.shape\s*==\s*TileShape\.RECORDING\s*\)\s*\{[^}]*ControlArt\.cancel[^}]*ControlArt\.send[^}]*\}"""
    )

    /** A failure here means the recording-look guard is missing or weakened, so a non-recording mic could still draw its controls. */
    private fun lookRuleProblems(source: String): List<String> =
        if (lookRule.containsMatchIn(SourceText.code(source))) emptyList() else listOf("the recording look guards neither of its two drawControl calls")

    // ---- The rule holds on the real file and stays quiet on clean samples ----

    @Test
    fun `recording look guards its cancel then send under the RECORDING condition`() {
        assertQuiet(
            "two controls kept inside a single RECORDING guard",
            lookRuleProblems("""if (face.shape == TileShape.RECORDING) {
                artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)
                artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.send, look)
            }"""),
        )
        assertQuiet(
            "a comment line inside the guard does not break it",
            lookRuleProblems("""if (face.shape == TileShape.RECORDING) {
                // draw cancel then send while recording
                artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)
                artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.send, look)
            }"""),
        )

        assertEquals("android_overlay: the recording look must guard its cancel then send calls", emptyList<String>(), inFile(ModuleFiles.mainTexts(), "ScreenMic.kt", this::lookRuleProblems))
    }

    // ---- Sample 1: disabling the condition makes it go red ----

    @Test
    fun `the RECORDING guard fails when the condition is disabled`() {
        assertFires(
            "a disabled condition must be reported",
            lookRuleProblems("""if (false) {
                artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)
                artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.send, look)
            }"""),
            "the recording look guards",
        )
        assertQuiet(
            "a wider-spaced condition keeps the guard",
            lookRuleProblems("""if (  face.shape == TileShape.RECORDING ) {
                artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)
                artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.send, look)
            }"""),
        )

        assertEquals("android_overlay: the recording look must guard its cancel then send calls", emptyList<String>(), inFile(ModuleFiles.mainTexts(), "ScreenMic.kt", this::lookRuleProblems))
    }

    // ---- Sample 2: swapping the second control to cancel makes it go red ----

    @Test
    fun `the RECORDING guard fails when the send call is swapped for cancel`() {
        assertFires(
            "a send turned into cancel must be reported",
            lookRuleProblems("""if (face.shape == TileShape.RECORDING) {
                artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)
                artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.cancel, look)
            }"""),
            "the recording look guards",
        )

        assertEquals("android_overlay: the recording look must guard its cancel then send calls", emptyList<String>(), inFile(ModuleFiles.mainTexts(), "ScreenMic.kt", this::lookRuleProblems))
    }

    // ---- The helpers shared above ----

    private fun inFile(files: Map<String, String>, name: String, check: (String) -> List<String>): List<String> =
        files[name]?.let(check) ?: listOf("$name is missing from the main sources")
}
