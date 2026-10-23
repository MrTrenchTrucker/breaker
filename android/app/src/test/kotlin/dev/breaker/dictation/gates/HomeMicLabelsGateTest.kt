package dev.breaker.dictation.gates

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home mic's three labels are ui's, never the app's: `createHomeMic` passes ui's public
 * `HomeControl` constants to `createScreenMic`, and no app main source retypes the three label
 * words as its own string literal. A test can call the helper but cannot see that it carries the
 * constants rather than the words, and a retyped word compiles and passes a shape check, so both
 * facts are read from source. The positive rule reads the comment-stripped helper body and needs
 * the three `HomeControl` constants in the `createScreenMic` call, spacing-tolerant, so it holds
 * whether or not the body is reformatted; the negative rule reads every app main source with the
 * string literals kept and needs none of the three to appear. Each rule runs on a made-up body
 * that obeys it (quiet), then on a made-up body that breaks it (fires), and only then on the real
 * app source (quiet). [AppSourceFiles] is the scanner: its `strip` blanks comments and literal
 * text and returns `literals`, the text of every string literal, which is what the negative rule
 * reads; the positive rule reads the same `strip`'s `code`, where the `HomeControl` names survive.
 */
internal class HomeMicLabelsGateTest {

    private val helpers: String = "kotlin/dev/breaker/dictation/host/ActivityHelpers.kt"

    /** The three labels, exactly as ui spells them; the negative rule forbids these as app literals. */
    private val labels: List<String> = listOf("Tap to talk", "Cancel", "Done")

    /** The `createScreenMic` call that passes ui's three constants, spacing-tolerant. */
    private val call: Regex =
        Regex("""\bcreateScreenMic\s*\(\s*context\s*,\s*themeMode\s*,\s*HomeControl\s*\.\s*TAP_TO_TALK\s*,\s*HomeControl\s*\.\s*CANCEL\s*,\s*HomeControl\s*\.\s*DONE\s*\)""")

    /** The positive rule holds when the body's `createScreenMic` call still passes the three constants. */
    private fun passesLabels(source: String): Boolean = call.containsMatchIn(AppSourceFiles.strip(source).code)

    /** The label words retyped as string literals in [source] (comments blanked), or none. */
    private fun retypedIn(source: String): List<String> =
        labels.filter { it in AppSourceFiles.strip(source).literals }

    /** Every app main source with the label words retyped, as "path: 'word'"; empty when none does. */
    private fun realOffences(): List<String> =
        AppSourceFiles.mainKotlinSources().toSortedMap().flatMap { (path, text) ->
            retypedIn(text).map { "$path: '$it'" }
        }

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    // Made-up bodies for the in-memory controls. The two that break a rule carry the words on purpose.
    private val helperObeys: String =
        "fun f() = takeWiring.createScreenMic(context, themeMode, HomeControl.TAP_TO_TALK, HomeControl.CANCEL, HomeControl.DONE)"
    private val helperSpaced: String =
        "fun f() = takeWiring.createScreenMic( context , themeMode , HomeControl.TAP_TO_TALK , HomeControl.CANCEL , HomeControl.DONE )"
    private val helperEmpty: String = "fun f() = takeWiring.createScreenMic(context, themeMode, \"\", \"\", \"\")"
    private val sourceObeys: String = "fun f() = listOf(\"Not downloaded\", \"Menu\", \"About\")"
    private val sourceBreaks: String = "fun f() = listOf(\"Not downloaded\", \"Cancel\", \"About\")"

    @Test
    fun `the Home mic labels hold on the real app source`() {
        val broken = buildList {
            if (!passesLabels(AppSourceFiles.mainFile(helpers))) add("createHomeMic does not pass ui's three HomeControl labels")
            realOffences().forEach { add(it) }
        }
        assertTrue("the Home mic labels break: $broken", broken.isEmpty())
    }

    @Test
    fun `the positive rule fires when the constants are not passed`() {
        assertTrue("a body that passes the constants must stay quiet", passesLabels(helperObeys))
        assertTrue("a body that passes empty strings must fire", !passesLabels(edit(AppSourceFiles.mainFile(helpers), "HomeControl.TAP_TO_TALK", "\"\"")))
        assertTrue("a body that passes the words as literals must fire", !passesLabels("fun f() = takeWiring.createScreenMic(context, themeMode, \"Tap to talk\", \"Cancel\", \"Done\")"))
        assertTrue("the real helper body must pass the positive rule", passesLabels(AppSourceFiles.mainFile(helpers)))
    }

    @Test
    fun `the positive rule stays quiet on a harmless spacing change`() {
        assertTrue("a spaced call that still passes the constants must stay quiet", passesLabels(helperSpaced))
    }

    @Test
    fun `the negative rule fires on a retyped label`() {
        assertTrue("a source with no label must stay quiet", retypedIn(sourceObeys).isEmpty())
        assertTrue("a source that retypes a label must fire", retypedIn(sourceBreaks).isNotEmpty())
    }

    @Test
    fun `the negative rule is quiet on the real app sources`() {
        assertTrue("no app main source retypes a Home mic label", realOffences().isEmpty())
    }

}
