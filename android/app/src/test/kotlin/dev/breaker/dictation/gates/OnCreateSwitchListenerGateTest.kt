package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source rule pinning that `SettingsLauncherActivity.onCreate` RECORDS the
 * wiring's switch listener into its own field. `SettingsLauncherActivity.kt:104` is
 * `switchListener = takeWiring.switchListener`; that field is what `onDestroy`'s
 * `switchListener?.let { takeWiring.unwatchSwitch(it) }` removes.
 *
 * THE HOLE THIS CLOSES: deleting `:104` leaves the field null, so the removal in `onDestroy` is a runtime
 * no-op - the listener is never recorded, so there is nothing to take off. The existing teardown gate
 * (`ActivityUnwatchSwitchGateTest`) scans only the onDestroy BODY text and stays green; the teardown pin
 * (`HomeTakeWiringTeardownPinTest`) calls `unwatchSwitch` DIRECTLY and never runs the activity's onCreate;
 * and no behaviour test builds the activity. So the whole suite is green while the teardown is silently
 * lost. Measured precedent: `homeLevelSink =` has 6 hits under `android/app/src/test`, `switchListener =`
 * has 0.
 *
 * SEVERITY: deleting `:104` does NOT stop the mic re-pushing -
 * `HomeTakeWiring.kt:78` (`switch.addSwitchListener(switchListener)`) registers the listener in the WIRING,
 * so the re-push still works. What is lost is the TEARDOWN: the listener is never removed in onDestroy, so
 * it is a leak - the wiring is rebuilt on the next launch and the old listener is still attached, re-pushing
 * a mic for a screen that is gone.
 *
 * The rule is scoped to the `onCreate` BODY (`matchedBody`), not to the whole file: `switchListener =`
 * occurs in the whole activity only in that body (`SettingsLauncherActivity.kt:47` is the declaration), so
 * a mutant that deletes `:104` leaves an onCreate body with no assignment while the rest of the file is
 * unchanged - and that is what makes the rule go red. A mutant that MOVES the call out of the body into a
 * sibling function leaves the file containing the text while the body no longer does, which the
 * scope-escape sample below drives.
 *
 * Read against real source with comments stripped but literals kept, per the house gate shape
 * (`ActivityUnwatchSwitchGateTest`'s `matchedBody`, `HomeMicFaceWiringGateTest`'s `stripComments`).
 *
 * MUTANTS (the ones this rule must catch):
 *   `SettingsLauncherActivity.kt:104` deleted -
 *   `        switchListener = takeWiring.switchListener` -> ``
 *   The onCreate body then holds no assignment, so `ONCREATE_RECORDS_THE_SWITCH_LISTENER` fails.
 *   The same call moved OUT of the onCreate body into a sibling function -
 *   the file still contains the text, the onCreate body no longer does, so the rule fails on scope.
 */
internal class OnCreateSwitchListenerGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** A rule holds when its pattern still matches the comment-stripped source. */
    private class OnCreateRule(val name: String, val holds: (String) -> Boolean)

    // Fires: a single targeted edit that must make this file's own rule fail.
    private class OnCreateFire(val rule: String, val old: String, val new: String)

    // Quiet: the same-file edit must keep this rule true.
    private class OnCreateQuiet(val rule: String, val old: String, val new: String)

    private val rules: Map<String, OnCreateRule> = mapOf(
        "ONCREATE_RECORDS_THE_SWITCH_LISTENER" to OnCreateRule("ONCREATE_RECORDS_THE_SWITCH_LISTENER") { code ->
            val body = matchedBody(code, "override fun onCreate")
            body != null && body.contains("switchListener = takeWiring.switchListener")
        },
    )

    private val fires: List<OnCreateFire> = listOf(
        // The mutant: the activity's own recording of the wiring's listener is deleted.
        OnCreateFire("ONCREATE_RECORDS_THE_SWITCH_LISTENER", "        switchListener = takeWiring.switchListener\n", ""),
    )

    private val quiet: List<OnCreateQuiet> = listOf(
        // Harmless comment above the assignment: the body still holds the text.
        OnCreateQuiet(
            "ONCREATE_RECORDS_THE_SWITCH_LISTENER",
            "        switchListener = takeWiring.switchListener",
            "        // held so onDestroy can take it off again\n        switchListener = takeWiring.switchListener",
        ),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** The substring between the first `{` after [marker] and its matching closing brace. */
    private fun matchedBody(code: String, marker: String): String? {
        val from = code.indexOf(marker)
        if (from < 0) return null
        var i = from + marker.length
        while (i < code.length && code[i] != '{') i += 1
        if (i >= code.length) return null
        var depth = 0
        val start = i
        for (j in i until code.length) {
            when (code[j]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return code.substring(start, j)
                }
            }
        }
        return null
    }

    private fun activityCode(): String = stripComments(AppSourceFiles.mainFile(activity))

    @Test
    fun `onCreate still records the wiring's switch listener`() {
        val broken = rules.values.filterNot { it.holds(activityCode()) }.map { it.name }
        assertTrue("app: the activity's onCreate breaks: $broken", broken.isEmpty())
    }

    @Test
    fun `the assignment is scoped to the onCreate body not the whole file`() {
        // The rule reads a BODY: the call must be inside the onCreate braces, not merely anywhere in the
        // file. The call is DELETED from the body and the same text is inserted into a sibling function -
        // so the whole file still contains the text while the onCreate body no longer does. A whole-file
        // `contains` would stay true here; the body-scoped rule must not.
        val body = matchedBody(activityCode(), "override fun onCreate")
        assertTrue("app: the activity has no onCreate body", body != null)
        assertTrue("app: onCreate must still record the wiring's switch listener", body!!.contains("switchListener = takeWiring.switchListener"))
        val without = edit(activityCode(), "        switchListener = takeWiring.switchListener\n", "")
        val moved = edit(
            without,
            "    private fun showFirstRun(",
            "    private fun holdSwitchListener() { switchListener = takeWiring.switchListener }\n    private fun showFirstRun(",
        )
        assertTrue(
            "app: the moved-out text must still be in the file, so only the body scope can fail",
            moved.contains("switchListener = takeWiring.switchListener"),
        )
        assertFalse(
            "app: the rule must read the onCreate body, not the whole file",
            rules.getValue("ONCREATE_RECORDS_THE_SWITCH_LISTENER").holds(moved),
        )
    }

    @Test
    fun `every firing sample makes the rule fail`() {
        for (s in fires) {
            val edited = edit(activityCode(), s.old, s.new)
            assertFalse(
                "app: ${s.rule} must fail once '${s.old.trim()}' => '${s.new.trim()}'",
                rules.getValue(s.rule).holds(edited),
            )
        }
    }

    @Test
    fun `every quiet edit keeps the rule true`() {
        for (q in quiet) {
            val edited = edit(activityCode(), q.old, q.new)
            assertTrue(
                "app: ${q.rule} must stay quiet on '${q.old.trim()}' => '${q.new.trim()}'",
                rules.getValue(q.rule).holds(edited),
            )
        }
    }

    @Test
    fun `firing and quiet sample text is a real substring of the file`() {
        for (s in fires) {
            assertTrue("app: '${s.old.trim()}' is not text of $activity", activityCode().contains(s.old))
        }
        for (q in quiet) {
            assertTrue("app: '${q.old.trim()}' is not text of $activity", activityCode().contains(q.old))
        }
    }
}

/** Strips // line comments and /* */ block comments from [source], leaving string literals untouched. */
private fun stripComments(source: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < source.length) {
        when {
            source.startsWith("//", i) -> {
                val end = source.indexOf('\n', i)
                i = if (end < 0) source.length else end
            }
            source.startsWith("/*", i) -> {
                val end = source.indexOf("*/", i)
                i = if (end < 0) source.length else end + 2
            }
            else -> out.append(source[i]).let { i++ }
        }
    }
    return out.toString()
}
