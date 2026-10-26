package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source rule pinning that `SettingsLauncherActivity.onDestroy` takes the
 * wiring's switch listener off again. The teardown pin (`wiring/HomeTakeWiringTeardownPinTest.kt`) drives
 * `HomeTakeWiring.unwatchSwitch` DIRECTLY, so it cannot catch a deletion of the activity's own
 * call - the call the real teardown makes. This gate pins the call.
 *
 * The rule is scoped to the `onDestroy` BODY (`matchedBody`), not to the whole file: `unwatchSwitch`
 * occurs in the whole activity only in that body (`wiring/HomeTakeWiring.kt:86` is the definition, and
 * the only `unwatchSwitch` in this file is `:192`), so deleting `:192` leaves an onDestroy
 * body with no `unwatchSwitch(` while the rest of the file is unchanged - and that is what makes the
 * rule go red. It also requires `super.onDestroy()`, so the body cannot be gutted down to the call.
 *
 * Read against real source with comments stripped but literals kept, per the house gate shape
 * (`HomeLevelSinkLifecycleGateTest`'s `matchedBody`, `HomeMicFaceWiringGateTest`'s
 * `stripComments(AppSourceFiles.mainFile(...))`).
 *
 * The one edit this rule must catch:
 *   `SettingsLauncherActivity.kt:192` deleted -
 *   `         switchListener?.let { takeWiring.unwatchSwitch(it) }\n` -> ``
 * The onDestroy body then holds only the homeLevelSink release, `homeLevelSink = null` and
 * `super.onDestroy()`; no `unwatchSwitch(` remains in it, so `ONDESTROY_UNWATCHES_THE_SWITCH` fails.
 */
internal class ActivityUnwatchSwitchGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** A rule holds when its pattern still matches the comment-stripped source. */
    private class Rule(val name: String, val holds: (String) -> Boolean)

    // Fires: a single targeted edit that must make this file's own rule fail.
    private class Fire(val rule: String, val old: String, val new: String)

    // Quiet: the same-file edit must keep this rule true.
    private class Quiet(val rule: String, val old: String, val new: String)

    private val rules: Map<String, Rule> = mapOf(
        "ONDESTROY_UNWATCHES_THE_SWITCH" to Rule("ONDESTROY_UNWATCHES_THE_SWITCH") { code ->
            val body = matchedBody(code, "override fun onDestroy")
            body != null && body.contains("unwatchSwitch(") && body.contains("super.onDestroy()")
        },
    )

    private val fires: List<Fire> = listOf(
        // The firing edit: the activity's own removal call is deleted.
        Fire("ONDESTROY_UNWATCHES_THE_SWITCH", "        switchListener?.let { takeWiring.unwatchSwitch(it) }\n", ""),
        // The body keeps the call but loses the activity's own super call.
        Fire("ONDESTROY_UNWATCHES_THE_SWITCH", "        super.onDestroy()\n", ""),
    )

    private val quiet: List<Quiet> = listOf(
        // Harmless spacing: the body still holds both required texts.
        Quiet("ONDESTROY_UNWATCHES_THE_SWITCH",
            "        switchListener?.let { takeWiring.unwatchSwitch(it) }",
            "        switchListener?.let { takeWiring.unwatchSwitch( it ) }"),
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
    fun `onDestroy still takes the switch listener off and still calls super`() {
        val broken = rules.values.filterNot { it.holds(activityCode()) }.map { it.name }
        assertTrue("app: the activity's onDestroy teardown breaks: $broken", broken.isEmpty())
    }

    @Test
    fun `the unwatchSwitch call is scoped to the onDestroy body not the whole file`() {
        // The rule reads a BODY: the call must be inside the onDestroy braces, not merely anywhere in
        // the file. The call is DELETED and the same text is re-inserted right after onDestroy's closing
        // brace - so the whole file still contains `unwatchSwitch(` exactly once while the onDestroy body
        // no longer does. A whole-file `contains` would stay true here; the body-scoped rule must not.
        val body = matchedBody(activityCode(), "override fun onDestroy")
        assertTrue("app: the activity has no onDestroy body", body != null)
        assertTrue("app: onDestroy must still call unwatchSwitch", body!!.contains("unwatchSwitch("))
        val onDestroy = "    override fun onDestroy() {\n        homeLevelSink?.let { (applicationContext as BreakerApp).tileHost.removeHomeLevelSink(it) }\n        homeLevelSink = null\n        switchListener?.let { takeWiring.unwatchSwitch(it) }\n        super.onDestroy()\n    }"
        assertTrue("app: the onDestroy text is not a real substring", activityCode().contains(onDestroy))
        val moved = edit(activityCode(), onDestroy,
            "    override fun onDestroy() {\n        homeLevelSink?.let { (applicationContext as BreakerApp).tileHost.removeHomeLevelSink(it) }\n        homeLevelSink = null\n        super.onDestroy()\n    }\n    private fun holdSwitchListener() = switchListener?.let { takeWiring.unwatchSwitch(it) }")
        assertTrue(
            "app: the moved-out text must still be in the file, so only the body scope can fail",
            moved.contains("unwatchSwitch("),
        )
        assertFalse(
            "app: the rule must read the onDestroy body, not the whole file",
            rules.getValue("ONDESTROY_UNWATCHES_THE_SWITCH").holds(moved),
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
