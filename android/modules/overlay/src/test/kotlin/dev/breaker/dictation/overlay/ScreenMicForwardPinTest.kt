package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract [ScreenMic] must keep with the view it wraps: every push the app is given
 * (`setState`, `setLevel`, `setTheme`, `setDescription`) is forwarded to that view's own
 * `setModel...` setter, with the receiver `view` and the pushed value in the call.
 *
 * A JVM test cannot call these: the view is made by `ScreenMic.create`, which needs a phone
 * context, and the forwards are the far end of the Home mic's pushes - the app module pins
 * its own end (`HomeMicForwardGateTest`). So this gate reads `ScreenMic.kt` through the
 * module's own scanner ([SourceText]), where comments and KDoc are not read.
 *
 * One rule per forward, and every rule names BOTH the receiver (`view`) and the argument
 * (the rule's own parameter), so a body that drops either cannot match. Each rule is shown
 * to fail on its own emptied-body mutant - `fun setTheme(mode: ThemeMode) = Unit` - the
 * mutant that survived every suite here before this pin, so a rule that could never fail
 * does not stand in for the contract. All four pushes are covered, not just the theme: they
 * share the one-expression shape, so the same emptied body is a live mutant against each of
 * them, and pinning the theme alone would leave the same blind spot three times over.
 */
class ScreenMicForwardPinTest {

    private val mic = "ScreenMic.kt"

    /** A rule with its name and the call it must find. */
    private class Rule(val name: String, val call: Regex)

    /**
     * One forward per push: the receiver is the wrapped `view`, the argument is the rule's
     * own parameter, so a body that drops either cannot match.
     */
    private val rules: List<Rule> = listOf(
        Rule(
            "SCREEN_MIC_FORWARDS_THE_STATE_TO_THE_VIEW",
            Regex("""\bfun\s+setState\s*\(\s*state\s*:\s*TileState\s*\)\s*=\s*view\s*\.\s*setModelState\s*\(\s*state\s*\)"""),
        ),
        Rule(
            "SCREEN_MIC_FORWARDS_THE_LEVEL_TO_THE_VIEW",
            Regex("""\bfun\s+setLevel\s*\(\s*level\s*:\s*Float\s*\)\s*=\s*view\s*\.\s*setModelLevel\s*\(\s*level\s*\)"""),
        ),
        Rule(
            "SCREEN_MIC_FORWARDS_THE_THEME_TO_THE_VIEW",
            Regex("""\bfun\s+setTheme\s*\(\s*mode\s*:\s*ThemeMode\s*\)\s*=\s*view\s*\.\s*setModelTheme\s*\(\s*mode\s*\)"""),
        ),
        Rule(
            "SCREEN_MIC_FORWARDS_THE_DESCRIPTION_TO_THE_VIEW",
            Regex("""\bfun\s+setDescription\s*\(\s*text\s*:\s*String\?\s*\)\s*=\s*view\s*\.\s*setModelDescription\s*\(\s*text\s*\)"""),
        ),
    )

    /** Each forward's real line, then the emptied body a mutant writes: the push is never forwarded. */
    private val emptied: Map<String, Pair<String, String>> = mapOf(
        "SCREEN_MIC_FORWARDS_THE_STATE_TO_THE_VIEW" to
            ("fun setState(state: TileState) = view.setModelState(state)" to "fun setState(state: TileState) = Unit"),
        "SCREEN_MIC_FORWARDS_THE_LEVEL_TO_THE_VIEW" to
            ("fun setLevel(level: Float) = view.setModelLevel(level)" to "fun setLevel(level: Float) = Unit"),
        "SCREEN_MIC_FORWARDS_THE_THEME_TO_THE_VIEW" to
            ("fun setTheme(mode: ThemeMode) = view.setModelTheme(mode)" to "fun setTheme(mode: ThemeMode) = Unit"),
        "SCREEN_MIC_FORWARDS_THE_DESCRIPTION_TO_THE_VIEW" to
            ("fun setDescription(text: String?) = view.setModelDescription(text)" to "fun setDescription(text: String?) = Unit"),
    )

    /** A receiver and an argument dropped by a weakened rule, as a mutant of the real file. */
    private val weakened: Map<String, Pair<String, String>> = mapOf(
        "SCREEN_MIC_FORWARDS_THE_STATE_TO_THE_VIEW" to
            ("view.setModelState(state)" to "view.setModelState(TileState.IDLE)"),
        "SCREEN_MIC_FORWARDS_THE_LEVEL_TO_THE_VIEW" to
            ("view.setModelLevel(level)" to "view.setModelLevel(0f)"),
        "SCREEN_MIC_FORWARDS_THE_THEME_TO_THE_VIEW" to
            ("view.setModelTheme(mode)" to "view.setModelTheme(ThemeMode.LIGHT)"),
        "SCREEN_MIC_FORWARDS_THE_DESCRIPTION_TO_THE_VIEW" to
            ("view.setModelDescription(text)" to "view.setModelDescription(\"Dictation\")"),
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

    /** Every push the app is given reaches the view the mic wraps. */
    @Test
    fun `every push the app gives the mic is forwarded to its view`() {
        assertTrue("overlay: $mic was read as empty", inFile().isNotBlank())
        for (rule in rules) {
            assertTrue("overlay: ScreenMic breaks rule ${rule.name}", rule.call.containsMatchIn(SourceText.code(inFile())))
        }
        // the far end of the chain: the four setModel... setters the rules name exist on the view
        for (setter in listOf("setModelState", "setModelLevel", "setModelTheme", "setModelDescription")) {
            assertTrue("overlay: ScreenMic.kt does not carry the view's $setter", inFile().contains("fun $setter("))
        }
    }

    /** An emptied body cannot pass: each rule fails on the mutant that stops the forward. */
    @Test
    fun `an emptied body fails the rule that holds its forward`() {
        assertEquals("overlay: a rule has no emptied sample", rules.map { it.name }.toSet(), emptied.keys)
        for (rule in rules) {
            val (old, new) = emptied.getValue(rule.name)
            assertFalse(
                "overlay: a ${rule.name} body emptied to Unit must break its rule",
                rule.call.containsMatchIn(SourceText.code(edited(old, new))),
            )
        }
    }

    /** A body that keeps the view but drops the pushed value cannot pass either: the rule requires the argument by name. */
    @Test
    fun `a forward that drops the pushed value fails the rule that holds it`() {
        assertEquals("overlay: a rule has no weakened sample", rules.map { it.name }.toSet(), weakened.keys)
        for (rule in rules) {
            val (old, new) = weakened.getValue(rule.name)
            assertFalse(
                "overlay: a ${rule.name} body that drops its argument must break its rule",
                rule.call.containsMatchIn(SourceText.code(edited(old, new))),
            )
        }
    }

    /** Each forward's own line in the real file, as the rules read it. */
    private val forwardLines: Map<String, String> = mapOf(
        "SCREEN_MIC_FORWARDS_THE_STATE_TO_THE_VIEW" to "fun setState(state: TileState) = view.setModelState(state)",
        "SCREEN_MIC_FORWARDS_THE_LEVEL_TO_THE_VIEW" to "fun setLevel(level: Float) = view.setModelLevel(level)",
        "SCREEN_MIC_FORWARDS_THE_THEME_TO_THE_VIEW" to "fun setTheme(mode: ThemeMode) = view.setModelTheme(mode)",
        "SCREEN_MIC_FORWARDS_THE_DESCRIPTION_TO_THE_VIEW" to "fun setDescription(text: String?) = view.setModelDescription(text)",
    )

    /** The rules are distinct and hold the real calls: one forward cannot stand in for another. */
    @Test
    fun `each push has its own rule and each rule holds its call`() {
        val names: List<String> = rules.map { it.name }
        assertEquals("overlay: a ScreenMic rule name is used twice", names.toSet().size, names.size)
        assertEquals("overlay: the ScreenMic rules must cover all four pushes", 4, names.size)

        // each rule holds its own line, and no other rule holds it: one forward cannot stand in for another
        for (rule in rules) {
            val line = forwardLines.getValue(rule.name)
            assertTrue("overlay: ${rule.name} must hold its own line", rule.call.containsMatchIn(SourceText.code(line)))
            val others = rules.filter { it.name != rule.name }
            assertEquals("overlay: ${rule.name} must not stand in for another forward", emptyList<String>(), others.filter { it.call.containsMatchIn(SourceText.code(line)) }.map { it.name })
        }

        // the receiver half and the argument half, each proven able to fire by name: the rule requires both
        val receiverFires = listOf(
            Triple("SCREEN_MIC_FORWARDS_THE_STATE_TO_THE_VIEW", "view.setModelState(state)", "setModelState(state)"),
            Triple("SCREEN_MIC_FORWARDS_THE_LEVEL_TO_THE_VIEW", "view.setModelLevel(level)", "setModelLevel(level)"),
            Triple("SCREEN_MIC_FORWARDS_THE_THEME_TO_THE_VIEW", "view.setModelTheme(mode)", "setModelTheme(mode)"),
            Triple("SCREEN_MIC_FORWARDS_THE_DESCRIPTION_TO_THE_VIEW", "view.setModelDescription(text)", "setModelDescription(text)"),
        )
        for ((name, old, new) in receiverFires) {
            val rule = rules.first { it.name == name }
            assertTrue("overlay: $name must fire when the view receiver is dropped", !rule.call.containsMatchIn(SourceText.code(edited(old, new))))
        }
        for (rule in rules) {
            val (old, new) = weakened.getValue(rule.name)
            assertTrue("overlay: ${rule.name} must fire when its argument is dropped", !rule.call.containsMatchIn(SourceText.code(edited(old, new))))
        }
    }

    /** The real file as the rules read it, with every forward present: the four setModel... calls, each exactly once. */
    @Test
    fun `the real ScreenMic carries the four forwards exactly once`() {
        val code = SourceText.code(inFile())
        for (rule in rules) {
            assertEquals("overlay: ${rule.name} must hold exactly once in ScreenMic.kt", 1, rule.call.findAll(code).count())
        }
        assertEquals(
            "overlay: ScreenMic.kt must forward exactly these four pushes",
            4,
            rules.count { it.call.containsMatchIn(code) },
        )
    }
}
