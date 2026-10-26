package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one guard the app-screen mic keeps on its own: whether the glow is showing the live level.
 *
 * `ScreenMic.motionIsLevelPulse()` is the guard `setModelLevel` asks before it smooths the pushed level into
 * `motionAlpha`. While a recording mic is hidden, `refreshMotion` takes STEADY and `breathing.stop()` sets the
 * alpha to full strength, so the level must NOT be smoothed and written there; the stale level alpha has to
 * survive the next show untouched until a level actually arrives. The guard carries all three halves of that
 * contract - the state is RECORDING, the view is `visible`, and the system lets `BreathingPulse` animate - and
 * a body that drops any one of them breaks the contract while hidden.
 *
 * This is the guard whose visibility half (`visible &&`) only a source-level gate can read: it is the
 * second expression of the level-pulse rule the pure function [micMotion] already decides, and nothing read this
 * one. A JVM test cannot drive the view (`Canvas` and the animator are not there), so this gate reads
 * `ScreenMic.kt` through the module's own scanner ([SourceText]), where comments and KDoc are not read.
 *
 * The rule names all three halves, so a body that drops any one cannot match, and each mutant is shown to break
 * the rule on its own edited copy of the real file, with [edited] proving the text it replaces is really there.
 */
class ScreenMicLevelPulsePinTest {

    private val mic = "ScreenMic.kt"

    /** A rule with its name and the guard it must find. */
    private class Rule(val name: String, val call: Regex)

    /** The guard, whole: the recording state, the visibility half, and the system's animator switch, each by name. */
    private val rules: List<Rule> = listOf(
        Rule(
            "SCREEN_MIC_LEVEL_PULSE_NEEDS_A_VISIBLE_RECORDING_MIC",
            Regex("""\bfun\s+motionIsLevelPulse\s*\(\s*\)\s*:\s*Boolean\s*=\s*model\s*\.\s*state\s*==\s*TileState\s*\.\s*RECORDING\s*&&\s*visible\s*&&\s*BreathingPulse\s*\.\s*animationsOn\s*\(\s*\)"""),
        ),
    )

    /** The guard's real body, then the emptied body a mutant writes: the guard no longer answers the level at all. */
    private val emptied: Map<String, Pair<String, String>> = mapOf(
        "weakenedVisible" to
            ("model.state == TileState.RECORDING && visible && BreathingPulse.animationsOn()" to "true"),
    )

    /** One half of the guard dropped by a weakened rule, as a mutant of the real file. */
    private val weakened: Map<String, Pair<String, String>> = mapOf(
        // weakenedVisible: the visibility half gone, so a hidden mic still takes its level while hidden.
        "weakenedVisible" to
            ("model.state == TileState.RECORDING && visible && BreathingPulse.animationsOn()" to "model.state == TileState.RECORDING && BreathingPulse.animationsOn()"),
        "weakenedAnimations" to
            ("model.state == TileState.RECORDING && visible && BreathingPulse.animationsOn()" to "model.state == TileState.RECORDING && visible"),
        "weakenedState" to
            ("model.state == TileState.RECORDING && visible && BreathingPulse.animationsOn()" to "visible && BreathingPulse.animationsOn()"),
    )

    /** The code of the real file with [old] replaced by [new]; [old] must be there, so a stale sample is a named failure. */
    private fun edited(old: String, new: String): String {
        val raw = inFile()
        check(raw.contains(old)) { "overlay: control: ScreenMic.kt has no '$old' to edit, so the control would test nothing" }
        return raw.replace(old, new)
    }

    /** The real ScreenMic.kt from the main sources, or a failure naming the file when it is not there. */
    private fun inFile(): String =
        ModuleFiles.mainTexts()[mic] ?: throw AssertionError("android_overlay: $mic is missing from the main sources")

    /** The guard the real file holds: every half of the level-pulse contract, by name. */
    @Test
    fun `the level pulse guard requires a visible recording mic`() {
        assertTrue("overlay: $mic was read as empty", inFile().isNotBlank())
        for (rule in rules) {
            assertTrue("overlay: ScreenMic breaks rule ${rule.name}", rule.call.containsMatchIn(SourceText.code(inFile())))
        }
        // the far end of the chain: the guard is asked by the level push, and only the level push smooths the level
        for (caller in listOf("if (motionIsLevelPulse()) {", "levelSmooth = levelSmoothStep(levelSmooth, level)", "motionAlpha = levelAlpha(levelSmooth)")) {
            assertTrue("overlay: ScreenMic.kt does not carry the level path's '$caller'", inFile().contains(caller))
        }
    }

    /** An emptied body cannot pass: the rule fails on the mutant that stops the guard answering the level. */
    @Test
    fun `an emptied guard body fails the rule that holds it`() {
        assertEquals("overlay: a rule has no emptied sample", setOf("weakenedVisible"), emptied.keys)
        for ((name, sample) in emptied) {
            val (old, new) = sample
            assertFalse(
                "overlay: the $name emptied guard body must break the rule that holds it",
                rules.first().call.containsMatchIn(SourceText.code(edited(old, new))),
            )
        }
    }

    /** A body that drops one half of the contract cannot pass either: the rule requires each half by name. */
    @Test
    fun `a guard that drops one half of the contract fails the rule that holds it`() {
        assertEquals("overlay: the guard must have exactly the three weakened halves", setOf("weakenedVisible", "weakenedAnimations", "weakenedState"), weakened.keys)
        for ((name, sample) in weakened) {
            val (old, new) = sample
            assertFalse(
                "overlay: the $name guard body must break the rule that holds it",
                rules.first().call.containsMatchIn(SourceText.code(edited(old, new))),
            )
        }
    }

    /** The guard is the one level-pulse condition in the file: it is asked once, and it holds exactly once. */
    @Test
    fun `the guard is the only level-pulse condition in the screen mic source`() {
        val code = SourceText.code(inFile())
        for (rule in rules) {
            assertEquals("overlay: ${rule.name} must hold exactly once in $mic", 1, rule.call.findAll(code).count())
        }
        assertEquals("overlay: the level push must ask the guard exactly once", 1, Regex("""\bmotionIsLevelPulse\s*\(\s*\)""").findAll(code).count() - 1)
    }

    /** The scan is not reading an empty or unrelated file: the rule can find its own function, so it cannot pass silently. */
    @Test
    fun `the real ScreenMic carries the guard and this gate reads it`() {
        assertTrue("overlay: $mic was read as empty", inFile().isNotBlank())
        assertTrue("overlay: $mic does not name motionIsLevelPulse at all", SourceText.code(inFile()).contains("motionIsLevelPulse"))
        assertTrue("overlay: $mic does not name the guard's declaration", inFile().contains("private fun motionIsLevelPulse(): Boolean ="))
    }
}
