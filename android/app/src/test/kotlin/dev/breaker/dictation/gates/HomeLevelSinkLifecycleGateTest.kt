package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Home-mic level-sink lifecycle so no other gate can: the launcher activity's onDestroy calls
 * removeHomeLevelSink, TileHost keeps its composite sink removable via removeHomeLevelSink whose body calls
 * compositeLevelSink.remove, and CompositeLevelSink carries a CopyOnWriteArrayList that leaves no bare
 * ArrayList() once it is stripped. Each rule reads against real source (stripped of comments, literals kept),
 * fires on a single targeted edit that breaks it; onDestroy now also requires the `homeLevelSink = null` line
 * and the `super.onDestroy()` call, so a sample can only break the rule through one of those three. Quiet edits
 * keep every clause true. Each rule reads against real source (stripped of comments, literals kept), stays
 * quiet on a harmless spacing or comment change.
 */
internal class HomeLevelSinkLifecycleGateTest {

    private val settings = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"
    private val tileHost = "kotlin/dev/breaker/dictation/host/TileHost.kt"
    private val composite = "kotlin/dev/breaker/dictation/wiring/CompositeLevelSink.kt"

    /** A rule holds when its pattern still matches the comment-stripped source. */
    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)

    // Fires: a single targeted edit that must make this file's own rule fail.
    private class Fire(val rule: String, val old: String, val new: String)

    // Quiet: the same-file edit must keep this rule true.
    private class Quiet(val name: String, val file: String, val old: String, val new: String)

    private val byName: Map<String, Rule> = mapOf(
        "ONDESTROY_CALLS_REMOVE_HOME_LEVEL_SINK" to Rule(
            "ONDESTROY_CALLS_REMOVE_HOME_LEVEL_SINK", settings,
        ) { code ->
            val body = matchedBody(code, "override fun onDestroy")
            body != null && body.contains("removeHomeLevelSink(")
                && body.contains("homeLevelSink = null") && body.contains("super.onDestroy()")
        },
        "ACTIVITY_REMOVES_THE_HOME_SINK_ONLY_BY_THE_EXACT_TILE_HOST_NAME" to Rule(
            "ACTIVITY_REMOVES_THE_HOME_SINK_ONLY_BY_THE_EXACT_TILE_HOST_NAME", settings,
        ) { code ->
            Regex("""\btileHost\s*\.\s*remove\w*""").findAll(code).all { it.value.endsWith("removeHomeLevelSink") && code[it.range.last + 1] == '(' }
        },
        "REMOVE_HOME_LEVEL_SINK_BODY_CALLS_COMPOSITE_REMOVE" to Rule(
            "REMOVE_HOME_LEVEL_SINK_BODY_CALLS_COMPOSITE_REMOVE", tileHost,
        ) { code ->
             val body = matchedBody(code, "fun removeHomeLevelSink(")
             body != null && body.contains("compositeLevelSink.remove(")
         },
        "COMPOSITE_COPYONWRITE_WITHOUT_BARE_ARRAYLIST" to Rule(
            "COMPOSITE_COPYONWRITE_WITHOUT_BARE_ARRAYLIST", composite,
        ) { code ->
            val hasCOW = code.contains("CopyOnWriteArrayList")
            val noBareList = code.replace("CopyOnWriteArrayList", "").let { !it.contains("ArrayList(") }
            hasCOW && noBareList
        },
    )

    private val fires: List<Fire> = listOf(
        Fire("ONDESTROY_CALLS_REMOVE_HOME_LEVEL_SINK", "removeHomeLevelSink(it)", ""),
         Fire("ONDESTROY_CALLS_REMOVE_HOME_LEVEL_SINK", "homeLevelSink = null", ""),
         Fire("ONDESTROY_CALLS_REMOVE_HOME_LEVEL_SINK", "super.onDestroy()", ""),
        Fire("REMOVE_HOME_LEVEL_SINK_BODY_CALLS_COMPOSITE_REMOVE",
            "fun removeHomeLevelSink(sink: LevelSink)", "fun removeLevelsSink(sink: LevelSink)"),
        Fire("REMOVE_HOME_LEVEL_SINK_BODY_CALLS_COMPOSITE_REMOVE",
            "compositeLevelSink.remove(", "compositeLevelSink.add("),
        Fire("ACTIVITY_REMOVES_THE_HOME_SINK_ONLY_BY_THE_EXACT_TILE_HOST_NAME", "tileHost.removeHomeLevelSink(it)", "tileHost.removeAllHomeLevelSinks()"),
        Fire("ACTIVITY_REMOVES_THE_HOME_SINK_ONLY_BY_THE_EXACT_TILE_HOST_NAME", "tileHost.removeHomeLevelSink(it)", "tileHost.removeHomeLevelSinks(it)"),
        Fire("COMPOSITE_COPYONWRITE_WITHOUT_BARE_ARRAYLIST", "CopyOnWriteArrayList()", "ArrayList()"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("ONDESTROY_CALLS_REMOVE_HOME_LEVEL_SINK", settings,
            "override fun onDestroy()", "override fun onDestroy()   // note releases and nulls the sink"),
        Quiet("REMOVE_HOME_LEVEL_SINK_BODY_CALLS_COMPOSITE_REMOVE", tileHost,
            "fun removeHomeLevelSink(sink: LevelSink)", "fun removeHomeLevelSink(sink: LevelSink)   // note"),
        Quiet("COMPOSITE_COPYONWRITE_WITHOUT_BARE_ARRAYLIST", composite,
            "= CopyOnWriteArrayList()", "= CopyOnWriteArrayList()   // note fan-out is safe"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun fileOf(rule: String): String = when (rule) {
        "ONDESTROY_CALLS_REMOVE_HOME_LEVEL_SINK" -> settings
        "REMOVE_HOME_LEVEL_SINK_BODY_CALLS_COMPOSITE_REMOVE" -> tileHost
        "ACTIVITY_REMOVES_THE_HOME_SINK_ONLY_BY_THE_EXACT_TILE_HOST_NAME" -> settings
        else -> composite
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

    private fun code(file: String): String = stripComments(AppSourceFiles.mainFile(file))

    @Test
    fun `every rule holds on the real activity, host and composite source`() {
        val broken = byName.values.filterNot { it.holds(code(it.file)) }.map { it.name }
        assertTrue("the home-mic level-sink lifecycle breaks: $broken", broken.isEmpty())
    }

    @Test
    fun `every firing sample makes its rule fail`() {
        for (s in fires) {
            val edited = edit(code(fileOf(s.rule)), s.old, s.new)
            assertFalse("app: ${s.rule} must fail once '${s.old}' => '${s.new}'", byName.getValue(s.rule).holds(edited))
        }
    }

    @Test
    fun `every quiet edit keeps its rule true`() {
        for (q in quiet) {
            val edited = edit(code(q.file), q.old, q.new)
            assertTrue("app: rule ${q.name} must stay quiet on '${q.old}' => '${q.new}'", byName.getValue(q.name).holds(edited))
        }
    }

    @Test
    fun `firing sample text is a real substring of its file`() {
        for (s in fires) {
            val file = fileOf(s.rule)
            assertTrue("app: '${s.old}' is not text of ${file}", code(file).contains(s.old))
        }
        for (q in quiet) {
            assertTrue("app: '${q.old}' is not text of ${q.file}", code(q.file).contains(q.old))
        }
    }
}

/** Strips // and /* */ comments but keeps string literals, matching the source helper used by the old gate. */
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
