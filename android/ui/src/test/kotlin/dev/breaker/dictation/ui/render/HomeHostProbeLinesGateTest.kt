package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Gate for the diagnostic probe lines in HomeHostView: the draw line, the window-focus line with its two words in
 * the right branches, the re-parent line of the mic slot, and the gate that keeps every probe line silent unless the
 * big mic is recording. Each rule runs first on a made-up source that obeys it, which must stay quiet, then on a
 * mutant built from the real source with one replace, which must be reported; only then is the real source asserted
 * quiet. Comments are removed and string literals are kept, because the rule is about the text of the lines.
 */
internal class HomeHostProbeLinesGateTest {
    private val host: String = mainSourceOf("render/HomeHostView.kt")

    private val drawLine = "probeLogged(\"draw \${SystemClock.uptimeMillis()}\")"
    private val focusLine = "probeLogged(\"windowFocus \${SystemClock.uptimeMillis()} \${if (hasWindowFocus) \"focused\" else \"unfocused\"}\")"
    private val reparentLine = "probeLogged(\"attachMicSlot re-parent \${SystemClock.uptimeMillis()} recording=true\")"
    private val recordingGate = "if (!recording) return"
    private val logCall = "Log.d(\"BreakerMicProbe\", line)"

    /** [text] with comments removed and every run of white space dropped, so a line compares however it is wrapped. */
    private fun flat(text: String): String = withoutComments(text).replace(Regex("""\s+"""), "")

    /** The flat body of the function whose header is [header], braces included; fails by name when it is not there. */
    private fun bodyOf(source: String, header: String): String {
        val text = flat(source)
        val start = text.indexOf(flat(header))
        assertTrue("ui: probe: the function is not in the host source: $header", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (at in open until text.length) {
            if (text[at] == '{') depth++
            if (text[at] == '}' && --depth == 0) return text.substring(open, at + 1)
        }
        throw AssertionError("ui: probe: the function never closes: $header")
    }

    /** [text] with [old] replaced by [new]; fails unless [old] is in [text] exactly once, so a mutant can never be a no-op. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue("ui: probe: the text to change is not in the source exactly once: $old", text.split(old).size == 2)
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("ui: probe: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("ui: probe: control: $what must not be reported", emptyList<String>(), problems)

    /** The problems with a function that must hold one exact probe line. */
    private fun holds(source: String, header: String, line: String, what: String): List<String> =
        if (bodyOf(source, header).contains(flat(line))) emptyList() else listOf("$what does not hold its probe line")

    private val drawHeader = "private fun draw(state: HomeState) {"
    private val focusHeader = "override fun onWindowFocusChanged(hasWindowFocus: Boolean) {"
    private val slotHeader = "private fun attachMicSlot(root: View) {"
    private val probeHeader = "private fun probeLogged(line: String) {"

    private fun drawProblems(source: String) = holds(source, drawHeader, drawLine, "draw")

    private fun focusProblems(source: String) = holds(source, focusHeader, focusLine, "onWindowFocusChanged")

    private fun reparentProblems(source: String) = holds(source, slotHeader, reparentLine, "attachMicSlot")

    /** The problems with the probe gate: the recording test comes first, then the log call. */
    private fun gateProblems(source: String): List<String> {
        val body = bodyOf(source, probeHeader)
        val gate = body.indexOf(flat(recordingGate))
        val call = body.indexOf(flat(logCall))
        return listOfNotNull(
            "probeLogged does not return unless the big mic is recording".takeIf { gate < 0 },
            "probeLogged does not log through Log.d with the probe tag".takeIf { call < 0 },
            "probeLogged must test recording before it logs".takeIf { gate >= 0 && call >= 0 && gate > call },
        )
    }

    // --- Sources that obey the rules, for the quiet controls --------------------------------------

    private val good = """
        private fun draw(state: HomeState) {
            $drawLine
        }
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            $focusLine
        }
        private fun attachMicSlot(root: View) {
            $reparentLine
        }
        private fun probeLogged(line: String) {
            $recordingGate
            $logCall
        }
    """

    // --- Tests ------------------------------------------------------------------------------------

    /** A failure here means a redraw is no longer logged, so the probe cannot show how often Home redraws during a take. */
    @Test fun `draw logs its time`() {
        assertQuiet("the obeying source", drawProblems(good))
        assertFires("a deleted draw line", drawProblems(replaceOnce(host, drawLine, "")), "draw does not hold")
        assertFires("a draw line without its time", drawProblems(replaceOnce(host, drawLine, "probeLogged(\"draw\")")), "draw does not hold")
        assertEquals("ui: HomeHostView.kt draw must log its time", emptyList<String>(), drawProblems(host))
    }

    /** A failure here means the window-focus line lost its time or has its two words in the wrong branches. */
    @Test fun `window focus logs its time and the right word`() {
        assertQuiet("the obeying source", focusProblems(good))
        val swapped = "if (hasWindowFocus) \"unfocused\" else \"focused\""
        assertFires("the two words swapped", focusProblems(replaceOnce(host, "if (hasWindowFocus) \"focused\" else \"unfocused\"", swapped)), "onWindowFocusChanged does not hold")
        assertFires("a deleted focus line", focusProblems(replaceOnce(host, focusLine, "")), "onWindowFocusChanged does not hold")
        assertFires("a focus line without its time", focusProblems(replaceOnce(host, "windowFocus \${SystemClock.uptimeMillis()} ", "windowFocus ")), "onWindowFocusChanged does not hold")
        assertEquals("ui: HomeHostView.kt onWindowFocusChanged must log its time and the right word", emptyList<String>(), focusProblems(host))
    }

    /** A failure here means the re-parent of the mic slot is no longer logged as a recording event. */
    @Test fun `the mic slot logs its re-parent as recording`() {
        assertQuiet("the obeying source", reparentProblems(good))
        assertFires("the recording word changed", reparentProblems(replaceOnce(host, "re-parent \${SystemClock.uptimeMillis()} recording=true", "re-parent \${SystemClock.uptimeMillis()} recording=false")), "attachMicSlot does not hold")
        assertFires("a deleted re-parent line", reparentProblems(replaceOnce(host, reparentLine, "")), "attachMicSlot does not hold")
        assertEquals("ui: HomeHostView.kt attachMicSlot must log its re-parent as recording", emptyList<String>(), reparentProblems(host))
    }

    /** A failure here means a probe line is logged while the big mic is not recording, which would put probe noise into every normal session. */
    @Test fun `probe lines are silent unless the big mic is recording`() {
        assertQuiet("the obeying source", gateProblems(good))
        assertFires("a deleted recording gate", gateProblems(replaceOnce(host, recordingGate, "")), "does not return unless")
        assertFires("a gate after the log call", gateProblems(replaceOnce(replaceOnce(host, recordingGate, ""), logCall, "$logCall\n        $recordingGate")), "must test recording before")
        assertFires("a changed tag", gateProblems(replaceOnce(host, logCall, "Log.d(\"Other\", line)")), "does not log through Log.d")
        assertEquals("ui: HomeHostView.kt probeLogged must test recording before it logs", emptyList<String>(), gateProblems(host))
    }
}
