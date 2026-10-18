package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three late hooks of the tile host (take ended, microphone taken, device failure). Each hook
 * takes the take mark when it fires, before the post, and clears with that same mark inside the posted
 * block; no hook calls the bare clear. The real host file is read with comments and literal text removed
 * by the shared scanner. The good and bad forms are literal lines, so each bad form is shown to break a
 * rule and the good form to hold them all. The real host file must hold the rules as well.
 */
internal class LateHookMarkGateTest {

    private val hostPath: String = "kotlin/dev/breaker/dictation/host/TileHost.kt"

    private class Hook(val name: String, val lambda: String, val call: String)

    private class Rule(val name: String, val holds: (String) -> Boolean)

    private val hooks: List<Hook> = listOf(
        Hook("onTakeEnded", "", "it.onTakeEnded()"),
        Hook("onMicTaken", "", "it.onTakeMicTaken()"),
        Hook("onDeviceFailure", "fallback -> ", "if (!it.onTakeFailed()) fallback()"),
    )

    /** The bare clear of the take owner, in code: the call with its open bracket. */
    private val bareClear: Regex = Regex("""\btakeOwner\s*\.\s*settled\s*\(""")

    /** The hook takes the mark before the post, and the post clears with that same mark. */
    private fun markedHook(name: String): Regex = Regex(
        """\broot\s*\.\s*$name\s*=\s*\{\s*(?:fallback\s*->\s*)?val\s+(?<m>\w+)\s*=\s*takeOwner\s*\.\s*mark\s*\(\s*\)(?:(?!root\s*\.)[\s\S])*?\btoCoordinator\s*\{\s*takeOwner\s*\.\s*settledIf\s*\(\s*\k<m>\s*\)""",
    )

    private fun markRule(hook: Hook): Rule =
        Rule("${hook.name}_TAKES_ITS_MARK_BEFORE_THE_POST_AND_CLEARS_WITH_IT") { markedHook(hook.name).findAll(it).count() == 1 }

    private val noBareRule: Rule = Rule("NO_HOOK_CALLS_THE_BARE_CLEAR") { bareClear.findAll(it).count() == 0 }

    /** The hook in the form the rules hold for. */
    private fun good(hook: Hook): String =
        "root.${hook.name} = { ${hook.lambda}val m = takeOwner.mark(); toCoordinator { takeOwner.settledIf(m); ${hook.call} } }"

    /** The hook in six forms that each break a rule: bare clear, bare clear beside the mark, mark inside the post, no mark, mark unused, other argument. */
    private fun badForms(hook: Hook): List<String> = listOf(
        "root.${hook.name} = { ${hook.lambda}toCoordinator { takeOwner.settled(); ${hook.call} } }",
        "root.${hook.name} = { ${hook.lambda}val m = takeOwner.mark(); toCoordinator { takeOwner.settled(); takeOwner.settledIf(m); ${hook.call} } }",
        "root.${hook.name} = { ${hook.lambda}toCoordinator { val m = takeOwner.mark(); takeOwner.settledIf(m); ${hook.call} } }",
        "root.${hook.name} = { ${hook.lambda}toCoordinator { ${hook.call} } }",
        "root.${hook.name} = { ${hook.lambda}val m = takeOwner.mark(); toCoordinator { ${hook.call} } }",
        "root.${hook.name} = { ${hook.lambda}val m = takeOwner.mark(); toCoordinator { takeOwner.settledIf(takeOwner.mark()); ${hook.call} } }",
    )

    /** Harmless edits of the good form: a trailing comment, and the same hook spread over lines. */
    private fun quiet(hook: Hook): List<String> = listOf(
        good(hook) + " // takeOwner.settled() root.${hook.name}",
        "root.${hook.name} = {\n    ${hook.lambda}val m = takeOwner.mark()\n    toCoordinator {\n        takeOwner.settledIf(m)\n        ${hook.call}\n    }\n}",
    )

    private fun codeOf(text: String): String = AppSourceFiles.strip(text).code

    @Test
    fun `each late hook of the real host file takes its mark and clears with it`() {
        val code = codeOf(AppSourceFiles.mainFile(hostPath))
        for (hook in hooks) {
            assertTrue("app: TileHost breaks rule ${markRule(hook).name}", markRule(hook).holds(code))
        }
        assertTrue("app: TileHost breaks rule ${noBareRule.name}", noBareRule.holds(code))
    }

    @Test
    fun `the good form of each late hook holds every rule`() {
        for (hook in hooks) {
            val code = codeOf(good(hook))
            assertTrue("app: the good form of ${hook.name} breaks a rule", markRule(hook).holds(code) && noBareRule.holds(code))
        }
    }

    @Test
    fun `each bad form of a late hook breaks a rule`() {
        for (hook in hooks) {
            for (bad in badForms(hook)) {
                val code = codeOf(bad)
                assertFalse("app: a bad form of ${hook.name} holds every rule: $bad", markRule(hook).holds(code) && noBareRule.holds(code))
            }
        }
    }

    @Test
    fun `each quiet form of a late hook holds every rule`() {
        for (hook in hooks) {
            for (text in quiet(hook)) {
                val code = codeOf(text)
                assertTrue("app: a quiet form of ${hook.name} breaks a rule: $text", markRule(hook).holds(code) && noBareRule.holds(code))
            }
        }
    }

    @Test
    fun `the bad forms cover each late hook at least twice`() {
        assertTrue("app: the gate must name three late hooks", hooks.size == 3)
        for (hook in hooks) {
            assertTrue("app: ${hook.name} needs at least two bad forms", badForms(hook).size >= 2)
        }
    }
}
