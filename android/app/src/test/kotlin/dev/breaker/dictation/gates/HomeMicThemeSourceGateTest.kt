package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract the Home mic's theme must keep once the fix lands: the mic's theme is NOT derived from
 * the phone's own night flag, and it IS delivered to the mic from the Home view's one theme resolution.
 *
 * A JVM test cannot call the helper that makes the mic, because it needs a phone context, so this gate
 * reads the helper that builds the mic through the shared scanner, so comments and literal text are not
 * read. The first rule reads the body of the one function that builds the mic, so an import the fix
 * leaves behind cannot hold it. The second rule reads the helper's own code, so the delivery the fix adds
 * there is what it holds. Each rule is shown to fail on the mutant it forbids, so a rule that could
 * never pass does not stand in for the contract. The activity that wires the hand-back is pinned by the
 * Home-mic wiring gate, not here.
 */
internal class HomeMicThemeSourceGateTest {

    private val helpers: String = "kotlin/dev/breaker/dictation/host/ActivityHelpers.kt"
    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** A rule with its name, the text it reads from the helper's code and the joined path, and what that text must hold. */
    private class Rule(val name: String, val reads: (String, String) -> String, val holds: (String) -> Boolean)

    /** The two shapes that derive a theme from the phone's own night flag. */
    private val phoneNight: Regex = Regex("""\b(UI_MODE_NIGHT_MASK|themeModeForNight)\b""")

    /** The app handing a theme to the mic: the delivery the fix wires. */
    private val delivery: Regex = Regex("""\bsetTheme\s*\(""")

    private val rules: List<Rule> = listOf(
        Rule(
            "HOME_MIC_THEME_IS_NOT_DERIVED_FROM_THE_PHONES_NIGHT",
            reads = { helperCode, _ -> bodyOf(helperCode, "createHomeMic") },
            holds = { code -> !phoneNight.containsMatchIn(code) },
        ),
        Rule(
            "HOME_MIC_THEME_IS_DELIVERED_TO_THE_MIC",
            reads = { helperCode, _ -> helperCode },
            holds = { code -> delivery.containsMatchIn(code) },
        ),
    )

    private fun rule(name: String): Rule = rules.single { it.name == name }

    /** The rules that do not hold, by name. [helperCode] is the helper's code; [path] the joined path. */
    private fun brokenIn(helperCode: String, path: String): List<String> =
        rules.filterNot { it.holds(it.reads(helperCode, path)) }.map { it.name }

    /** The code a file reads as: its comments and literal text gone. */
    private fun codeOf(path: String): String = AppSourceFiles.strip(AppSourceFiles.mainFile(path)).code

    /** The code of the files named, joined, as one rule reads them. */
    private fun codeOf(paths: List<String>): String = paths.joinToString("\n\n") { codeOf(it) }

    /** The helper's own code: the one file that makes the mic and hands it its theme. */
    private fun helpersCode(): String = codeOf(helpers)

    /** The body of [fn] in [code], from the brace that opens it to its own closing brace. */
    private fun bodyOf(code: String, fn: String): String {
        val start: Int = code.indexOf("fun $fn(")
        check(start >= 0) { "app: the $fn function is not in the source" }
        var parens = 0
        var open = -1
        for (index in start until code.length) {
            when (code[index]) {
                '(' -> {
                    parens++
                    if (parens == 1) open = index
                }
                ')' -> {
                    parens--
                    if (parens == 0) break
                }
            }
        }
        check(open >= 0) { "app: the $fn function has no parameter list" }
        val brace: Int = code.indexOf('{', open)
        check(brace >= 0) { "app: the $fn function has no body" }
        var braces = 0
        for (index in brace until code.length) {
            when (code[index]) {
                '{' -> braces++
                '}' -> {
                    braces--
                    if (braces == 0) return code.substring(brace + 1, index)
                }
            }
        }
        error("app: the $fn body never closes")
    }

    /** [old] replaced by [new] once; fails by name when the text is not there, so a sample cannot go quiet. */
    private fun edit(text: String, old: String, new: String): String {
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a mic theme sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** Today's Home-mic path: the two files as the rules read them. */
    private fun today(): String = codeOf(listOf(helpers, activity))

    @Test
    fun `the Home mic's theme is not derived from the phone's own night flag`() {
        val broken: List<String> = brokenIn(helpersCode(), today())
        assertTrue("app: the Home mic's theme source breaks: $broken", broken.isEmpty())
    }

    @Test
    fun `a mic hard-coded back to the phone's night flag breaks the derivation rule`() {
        val mutant: String = edit(
            bodyOf(today(), "createHomeMic"),
            "val themeMode = dev.breaker.shared.tokens.ThemeMode.LIGHT",
            "val themeMode = themeModeForNight(true)",
        )
        assertFalse(
            "app: a mic derived from the phone's night must break the derivation rule",
            rule("HOME_MIC_THEME_IS_NOT_DERIVED_FROM_THE_PHONES_NIGHT").holds(mutant),
        )
    }

    @Test
    fun `a delivery that is never wired breaks the delivery rule`() {
        val mutant: String = edit(helpersCode(), "    mic.setTheme(themeMode)\n", "")
        assertFalse(
            "app: a mic that is never handed its theme must break the delivery rule",
            rule("HOME_MIC_THEME_IS_DELIVERED_TO_THE_MIC").holds(mutant),
        )
    }
}
