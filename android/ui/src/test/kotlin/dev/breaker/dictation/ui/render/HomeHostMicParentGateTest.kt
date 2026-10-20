package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Gate for the mic slot in HomeHostView. Every redraw renders a new tree and puts the one
 * overlay-built mic view into it. The mic still sits in the tree of the last draw, and a view
 * with a parent cannot be added to another group: the add throws IllegalStateException. The
 * first draw happens in the constructor and the screen redraws on its first resume, so a mic
 * that is not taken out of its old parent first closes the app as it opens. The slot must
 * take the mic out of its parent before it adds it, in the same function and before the add.
 */
internal class HomeHostMicParentGateTest {
    private val host: String = mainSourceOf("render/HomeHostView.kt")

    /** [text] with comments and string literals removed, so a token compares exactly by case. */
    private fun flat(text: String): String = withoutCommentsAndStrings(text).replace(Regex("""\s+"""), "")

    /** The body of attachMicSlot, from its name to the next function after it. */
    private fun slotBody(source: String): String {
        val start = source.indexOf("private fun attachMicSlot")
        assertTrue("ui: overlay: attachMicSlot is not in the host source", start >= 0)
        val end = source.indexOf("private fun findTagged", start)
        assertTrue("ui: overlay: findTagged does not follow attachMicSlot", end > start)
        return flat(source.substring(start, end))
    }

    /** Replace the first text with [new]; fail by name unless it occurs exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue(
            "ui: overlay: the text to change is not in the source exactly once: $old",
            Regex(Regex.escape(old)).findAll(text).count() == 1,
        )
        return text.replace(old, new)
    }

    private val detach = flat("(mic.parent as? ViewGroup)?.removeView(mic)")
    private val add = flat("parent.addView(mic, index)")

    @Test fun `the slot takes the mic out of its old parent before it adds it`() {
        val body = slotBody(host)
        assertTrue("ui: overlay: attachMicSlot must detach the mic from its old parent", body.contains(detach))
        assertTrue("ui: overlay: attachMicSlot must add the mic to the slot", body.contains(add))
        assertTrue("ui: overlay: the detach must come before the add", body.indexOf(detach) < body.indexOf(add))
    }

    @Test fun `without the detach line the rule reports it`() {
        val stripped = replaceOnce(
            host,
            "(mic.parent as? ViewGroup)?.removeView(mic)",
            "",
        )
        assertFalse("ui: overlay: dropping the detach must be seen", slotBody(stripped).contains(detach))
    }

    @Test fun `a detach moved after the add is not accepted`() {
        val moved = replaceOnce(
            replaceOnce(host, "(mic.parent as? ViewGroup)?.removeView(mic)", ""),
            "parent.addView(mic, index)",
            "parent.addView(mic, index)\n        (mic.parent as? ViewGroup)?.removeView(mic)",
        )
        val body = slotBody(moved)
        assertFalse("ui: overlay: a detach after the add must not satisfy the order", body.indexOf(detach) < body.indexOf(add))
    }
}
