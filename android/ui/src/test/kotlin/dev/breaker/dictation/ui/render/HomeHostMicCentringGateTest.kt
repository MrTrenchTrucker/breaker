package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Gate for the mic slot in HomeHostView. The overlay mic measures to its own
 * width, so dropped into a vertical slot it would rest at the left edge; the
 * slot's layout gravity must be CENTER_HORIZONTAL so the mic is centred in it.
 * This rule holds on the real host and reports when the centring signal is
 * moved to a different function or written in anything but the code.
 */
internal class HomeHostMicCentringGateTest {
    private val host: String = mainSourceOf("render/HomeHostView.kt")

    /** [text] with comments and string literals removed, so a token compares exactly by case. */
    private fun flat(text: String): String = withoutCommentsAndStrings(text).replace(Regex("""\s+"""), "")

    /** Whether [host] holds the code [part], once its own whitespace is folded away. */
    private fun holds(host: String, part: String): Boolean = flat(host).contains(flat(part))

    /** Replace the first text with [new]; fail by name unless it occurs exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue(
            "ui: overlay: the text to change is not in the source exactly once: $old",
            Regex(Regex.escape(old)).findAll(text).count() == 1,
        )
        return text.replace(old, new)
    }

    @Test fun `the attachMicSlot body centres the mic with CENTER_HORIZONTAL`() {
        assertTrue("ui: overlay: the real host centres the slot with CENTER_HORIZONTAL", holds(host, "CENTER_HORIZONTAL"))
        assertFalse(
            "ui: overlay: replacing the gravity should make the centring signal vanish",
            holds(replaceOnce(host, "CENTER_HORIZONTAL", "VERTICAL"), "CENTER_HORIZONTAL"),
        )
    }

    @Test fun `the gravity line is what carries the centring`() {
        // The slot's gravity lives in a single `.apply`, so stripping it entirely loses the signal.
        val stripped = replaceOnce(host, ".apply { gravity = Gravity.CENTER_HORIZONTAL }", "")
        assertFalse("ui: overlay: dropping the gravity line keeps no centring", holds(stripped, "CENTER_HORIZONTAL"))
    }

    @Test fun `a trailing comment does not defeat the centring`() {
        // A comment after the gravity line is stripped before the rule reads it, so the centring still holds.
        val commented = replaceOnce(
            host,
            ".apply { gravity = Gravity.CENTER_HORIZONTAL }",
            ".apply { gravity = Gravity.CENTER_HORIZONTAL }\n        // centre it so the slot centres the mic it measures to its own width",
        )
        assertTrue("ui: overlay: a trailing comment must not hide the centring signal", holds(commented, "CENTER_HORIZONTAL"))
    }
}
