package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Gate that the diagnostic probe is absent from HomeHostView: no probe function, no call to it, no probe log tag and
 * no Log import. The probe is a diagnostic kit that is removed before release, so the release source must hold none of
 * it. Each rule first runs on a made-up source that obeys it, which must stay quiet, then on a mutant built from the
 * real source with one insert, which must be reported; only then is the real source asserted quiet. Comments are removed
 * and string literals are kept, because the rule is about the text of the source.
 */
internal class HomeHostProbeLinesGateTest {
    private val host: String = mainSourceOf("render/HomeHostView.kt")

    /** The text the source must not hold, each with the label a problem names it by. */
    private val banned: List<Pair<Regex, String>> = listOf(
        Regex("probeLogged") to "the probe function or one of its calls",
        Regex("BreakerMicProbe") to "the probe log tag",
        Regex("""android\.util\.Log\b""") to "the Log import",
        Regex("""\bLog\.""") to "a Log call",
    )

    /** One problem for each banned text that [source] holds once its comments are removed; none when it holds none. */
    private fun problems(source: String): List<String> {
        val text = withoutComments(source)
        return banned.mapNotNull { (rule, label) ->
            if (rule.containsMatchIn(text)) "HomeHostView still holds $label" else null
        }
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

    /** A made-up source that obeys the rule, for the quiet control. */
    private val good = """
        private fun draw(state: HomeState) {
            val screen = handler.show(state)
            show(screen)
        }
        override fun refresh() {
            draw(merged(drawn))
        }
    """

    /** A function header that is in the real source exactly once; the planted lines go after it. */
    private val anchor = "override fun refresh() {"

    /** A failure here means the probe function, or a call to it, is back in the release source of HomeHostView. */
    @Test fun `the probe function and its calls are absent`() {
        assertQuiet("the obeying source", problems(good))
        assertFires("a planted probe call", problems(replaceOnce(host, anchor, "$anchor\n        probeLogged(\"refresh\")")), "the probe function")
        assertFires("the helper declaration back", problems(replaceOnce(host, anchor, "private fun probeLogged(line: String) {}\n    $anchor")), "the probe function")
        assertEquals("ui: HomeHostView.kt must not hold the probe function or any call to it", emptyList<String>(), problems(host))
    }

    /** A failure here means the probe log tag, the Log import or a Log call is back in the release source of HomeHostView. */
    @Test fun `the probe log tag and the Log import are absent`() {
        assertQuiet("the obeying source", problems(good))
        assertFires("a planted Log call with the probe tag", problems(replaceOnce(host, anchor, "$anchor\n        Log.d(\"BreakerMicProbe\", \"refresh\")")), "the probe log tag")
        assertFires("a planted Log call with another tag", problems(replaceOnce(host, anchor, "$anchor\n        Log.d(\"Other\", \"refresh\")")), "a Log call")
        assertFires("a planted Log import", problems(replaceOnce(host, "import android.content.Context", "import android.content.Context\nimport android.util.Log")), "the Log import")
        assertEquals("ui: HomeHostView.kt must not hold the probe log tag, a Log import or a Log call", emptyList<String>(), problems(host))
    }
}
