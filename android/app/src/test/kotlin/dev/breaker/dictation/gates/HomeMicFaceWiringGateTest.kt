package dev.breaker.dictation.gates

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three Home-mic-face wiring facts no call-level test can, because a test can build the
 * wiring but never observe the values its helpers carry across a call: the re-push of the mic state
 * passes the switch, the shared busy flag and the state to homeMicFace in that order (never a hard
 * constant for the switch or the flag — a hard `true`/`false` for either breaks rule a, so B4 and B5
 * both die); the screen mic is set before the derived face is pushed once at paint, so a rebuilt mic
 * never shows a stale one (deleting that first paint breaks rule b); and the tile host's mic busy port
 * is the real forwarder over the coordinator's controller, not a no-op (swapping in a no-op breaks
 * rule c). Each rule is read against source with comments removed but literals kept, holds on the real
 * source, fires on a single targeted edit that breaks it, and stays quiet on a harmless spacing change.
 * String-literal content must survive stripping, so this gate strips comments only — see [stripComments].
 */
internal class HomeMicFaceWiringGateTest {

    private val homeTake: String = "kotlin/dev/breaker/dictation/wiring/HomeTakeWiring.kt"
    private val tileHost: String = "kotlin/dev/breaker/dictation/host/TileHost.kt"

    /** A rule holds when its pattern still matches the comment-stripped source. */
    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)

    /** Per firing sample: [rule] that must fail after editing [old] into [new] in the rule's file. */
    private class Sample(val rule: String, val old: String, val new: String)

    /** A harmless edit ([old] to [new]) applied to the file its rule names. */
    private class Quiet(val rule: String, val file: String, val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        // (a) rePushState derives the face from the switch, the shared busy flag and the state, in order.
        Rule("REPUSH_STATE_PASSES_SWITCH_AND_BUSY_FACE", homeTake) { code ->
            Regex("""homeMicFace\s*\(\s*switch\.isOn\(\)\s*,\s*busy\.micBusy\(\)\s*,\s*sending\s*,\s*state\s*\)""").containsMatchIn(code)
        },
        // (b) createScreenMic sets the screen mic, then repaints once from the port's own session state.
        Rule("CREATE_SCREEN_MIC_SETS_THE_MIC_THEN_REPAINTS", homeTake) { code ->
            Regex("""screenMic\s*=\s*mic[\s\S]*?rePushState\s*\(\s*port\.sessionState\s*\)""").containsMatchIn(code)
        },
        // (c) the tile host's mic busy port is the real forwarder over the coordinator's controller.
        Rule("MIC_BUSY_PORT_IS_THE_FORWARDER_OVER_THE_COORDINATOR", tileHost) { code ->
            Regex("""micBusy\s*:\s*MicBusyPort\s+by\s+lazy\s*\{[^}]*MicBusyPortForwarder\s*\(\s*coordinator\.yield\s*\)""").containsMatchIn(code)
        },
        // (d) onState sets the sticky sending flag to whether the state is SENDING.
        Rule("ONSTATE_SETS_THE_SENDING_FLAG", homeTake) { code ->
            Regex("""onState\s*=\s*\{\s*state\s*->[\s\S]*?sending\s*=\s*state\s*==\s*DictationState\.SENDING""").containsMatchIn(code)
        },
    )

    private val firing: List<Sample> = listOf(
        // B4: the switch arg is a hard constant instead of switch.isOn() (rule a's first clause breaks).
        Sample("REPUSH_STATE_PASSES_SWITCH_AND_BUSY_FACE",
            "homeMicFace(switch.isOn(), busy.micBusy(), sending, state)", "homeMicFace(switch.isOn(), busy.micBusy(), false, state)"),
        // B6: the first-paint re-push line is deleted (rule b's rePushState(port.sessionState) is gone).
        Sample("CREATE_SCREEN_MIC_SETS_THE_MIC_THEN_REPAINTS",
            "        rePushState(port.sessionState)\n", ""),
        // B19: the lazy body builds a no-op port instead of the real forwarder (rule c's forwarder is gone).
        Sample("MIC_BUSY_PORT_IS_THE_FORWARDER_OVER_THE_COORDINATOR",
            "MicBusyPortForwarder(coordinator.yield)", "NoopMicBusyPort"),
        // onState no longer sets the sending flag (rule d's flag assignment is gone).
        Sample("ONSTATE_SETS_THE_SENDING_FLAG",
            "sending = state == DictationState.SENDING", "sending = false"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("REPUSH_STATE_PASSES_SWITCH_AND_BUSY_FACE", homeTake,
            "homeMicFace(switch.isOn(), busy.micBusy(), sending, state)",
            "homeMicFace( switch.isOn() , busy.micBusy() , sending , state )"),
        Quiet("CREATE_SCREEN_MIC_SETS_THE_MIC_THEN_REPAINTS", homeTake,
            "screenMic = mic", "screenMic   =   mic"),
        Quiet("MIC_BUSY_PORT_IS_THE_FORWARDER_OVER_THE_COORDINATOR", tileHost,
            "MicBusyPortForwarder(coordinator.yield)",
            "MicBusyPortForwarder( coordinator.yield )"),
        Quiet("ONSTATE_SETS_THE_SENDING_FLAG", homeTake,
            "sending = state == DictationState.SENDING",
            "sending   =   state   ==   DictationState.SENDING"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun homeTakeCode(): String = stripComments(AppSourceFiles.mainFile(homeTake))

    private fun tileHostCode(): String = stripComments(AppSourceFiles.mainFile(tileHost))

    @Test
    fun `every rule holds on the real home take wiring and tile host`() {
        val broken = rules.filterNot { it.holds(homeTakeCode() + "\n\n" + tileHostCode()) }.map { it.name }
        assertTrue("app: the home mic face wiring breaks: $broken", broken.isEmpty())
    }

    @Test
    fun `every firing sample makes its rule fail`() {
        val byName = rules.associateBy { it.name }
        val stillTrue = mutableListOf<String>()
        for (s in firing) {
            val rule = byName.getValue(s.rule)
            val fileText = edit(if (rule.file == homeTake) homeTakeCode() else tileHostCode(), s.old, s.new)
            // The edited file keeps its own slot: home take first, tile host second.
            val combined = if (rule.file == homeTake) fileText + "\n\n" + tileHostCode() else homeTakeCode() + "\n\n" + fileText
            if (rule.holds(combined)) stillTrue += "${s.rule}: '${s.old}' => '${s.new}'"
        }
        assertTrue("app: the home mic face wiring keeps these rules true: $stillTrue", stillTrue.isEmpty())
    }

    @Test
    fun `every quiet edit keeps every rule true`() {
        for (q in quiet) {
            val edited = edit(if (q.file == homeTake) homeTakeCode() else tileHostCode(), q.old, q.new)
            val combined = if (q.file == homeTake) edited + "\n\n" + tileHostCode() else homeTakeCode() + "\n\n" + edited
            for (rule in rules) {
                assertTrue("app: rule ${rule.name} must stay quiet on '${q.old}' => '${q.new}'", rule.holds(combined))
            }
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
