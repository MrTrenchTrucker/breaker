package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract [dev.breaker.dictation.wiring.HomeScreenMic] must keep: every push it is given is
 * forwarded to the [dev.breaker.dictation.overlay.ScreenMic] it wraps.
 *
 * A JVM test cannot call these, because the mic is made by `ScreenMic.create`, which needs a phone
 * context; so this gate reads the class through the shared scanner, where comments and literal text
 * are not read. Each rule names the one forward it holds and the exact call it must find, so a body
 * emptied to `Unit` cannot pass. Every rule is shown to fail on the mutant it forbids, so a rule that
 * could never fail does not stand in for the contract.
 *
 * All three pushes are covered, not just the theme: `setTheme`, `setState` and `setLevel` have the
 * same one-expression shape, so the same emptied body is a live mutant against each of them. The
 * forwards past the mic - `ScreenMic.setTheme` to the view's own `setModelTheme` - are the overlay
 * module's, and are pinned there.
 */
internal class HomeMicForwardGateTest {

    private val mic: String = "kotlin/dev/breaker/dictation/wiring/HomeScreenMic.kt"

    /** A rule with its name, the call it must find, and the text that must hold it. */
    private class Rule(val name: String, val call: Regex)

    /**
     * One forward per push: the receiver is the wrapped `mic`, the argument is the rule's own
     * parameter, so a body that drops either cannot match.
     */
    private val rules: List<Rule> = listOf(
        Rule(
            "HOME_MIC_FORWARDS_THE_MODE_TO_THE_MIC",
            Regex("""\bfun\s+setTheme\s*\(\s*mode\s*:\s*ThemeMode\s*\)\s*=\s*mic\s*\.\s*setTheme\s*\(\s*mode\s*\)"""),
        ),
        Rule(
            "HOME_MIC_FORWARDS_THE_STATE_TO_THE_MIC",
            Regex("""\bfun\s+setState\s*\(\s*state\s*:\s*TileState\s*\)\s*=\s*mic\s*\.\s*setState\s*\(\s*state\s*\)"""),
        ),
        Rule(
            "HOME_MIC_FORWARDS_THE_LEVEL_TO_THE_MIC",
            Regex("""\bfun\s+setLevel\s*\(\s*level\s*:\s*Float\s*\)\s*=\s*mic\s*\.\s*setLevel\s*\(\s*level\s*\)"""),
        ),
    )

    /** The three bodies as the mutant that empties them writes them: the push is never forwarded. */
    private val emptied: Map<String, Pair<String, String>> = mapOf(
        "HOME_MIC_FORWARDS_THE_MODE_TO_THE_MIC" to
            ("fun setTheme(mode: ThemeMode) = mic.setTheme(mode)" to "fun setTheme(mode: ThemeMode) = Unit"),
        "HOME_MIC_FORWARDS_THE_STATE_TO_THE_MIC" to
            ("fun setState(state: TileState) = mic.setState(state)" to "fun setState(state: TileState) = Unit"),
        "HOME_MIC_FORWARDS_THE_LEVEL_TO_THE_MIC" to
            ("fun setLevel(level: Float) = mic.setLevel(level)" to "fun setLevel(level: Float) = Unit"),
    )

    private fun codeOf(old: String? = null, new: String = ""): String {
        val raw: String = AppSourceFiles.mainFile(mic)
        return AppSourceFiles.strip(if (old == null) raw else edit(raw, old, new)).code
    }

    private fun edit(text: String, old: String, new: String): String {
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a HomeScreenMic sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** Every push the class is given reaches the mic it wraps. */
    @Test
    fun `every push the Home mic is given is forwarded to the mic`() {
        assertTrue("app: $mic was read as empty", codeOf().isNotBlank())
        for (rule in rules) {
            assertTrue("app: HomeScreenMic breaks rule ${rule.name}", rule.call.containsMatchIn(codeOf()))
        }
    }

    /** An emptied body cannot pass: each rule fails on the mutant that stops the forward. */
    @Test
    fun `an emptied body fails the rule that holds its forward`() {
        assertEquals("app: a rule has no emptied sample", rules.map { it.name }.toSet(), emptied.keys)
        for (rule in rules) {
            val (old, new) = emptied.getValue(rule.name)
            val broken: String = codeOf(old, new)
            assertFalse(
                "app: a ${rule.name} body emptied to Unit must break its rule",
                rule.call.containsMatchIn(broken),
            )
        }
    }

    /** The three rules are distinct, so one forward cannot stand in for another. */
    @Test
    fun `each push has its own rule`() {
        val names: List<String> = rules.map { it.name }
        assertEquals("app: a HomeScreenMic rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: the HomeScreenMic rules must cover all three pushes", 3, names.size)
    }
}
