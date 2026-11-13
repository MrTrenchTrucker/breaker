package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source text checks for the touch hold in ScreenMic.kt: the view builds one hold that asks its parent, with
 * requestDisallowInterceptTouchEvent, to leave the touch alone; the down branch asks before the interpreter
 * starts; the up branch and the cancel branch give the touch back. Without this, the Home page's scroll
 * container takes a long press that drifts a little and the mic loses its hold. The behaviour of the hold is
 * TouchHoldTest. Each rule runs first on a made-up body that obeys it, which must stay quiet, then on a mutant
 * built from the real source with one replace, which must be reported; only then is the real source asserted quiet.
 * Comments are blanked and string literals are kept, as in the other source checks.
 */
class ScreenMicHoldWiringGateTest {

    private val viewFile = "ScreenMic.kt"
    private val fieldText = "private val hold = TouchHold { disallow -> parent?.requestDisallowInterceptTouchEvent(disallow) }"
    private val downText = "hold.down()\n                interpreter.down(x, y)"
    private val upText = "MotionEvent.ACTION_UP -> {\n                hold.release()"
    private val cancelText = "MotionEvent.ACTION_CANCEL -> {\n                hold.release()"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails unless [old] is in [text] exactly once, so a mutant can never be a no-op. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue("overlay: control: the text to change is not in the source exactly once: $old", text.split(old).size == 2)
        return text.replace(old, new)
    }

    private fun view(): String =
        ModuleFiles.mainTexts()[viewFile] ?: throw AssertionError("android_overlay: $viewFile is missing from the main sources")

    private fun touchBody(source: String): String? = AdapterRules.blockBodyOf(SourceText.code(source), "onTouchEvent")

    /** One branch of the touch handler's when: from its ACTION label to the next ACTION label or the end of the body. */
    private fun branch(body: String, action: String): String? {
        val start = body.indexOf("MotionEvent.$action ->")
        if (start < 0) return null
        val next = body.indexOf("MotionEvent.ACTION_", start + 1)
        return body.substring(start, if (next < 0) body.length else next)
    }

    /** A call that stands alone on its line, so no condition can sit in front of it. */
    private fun plainCall(call: String): Regex = Regex("(?m)^\\s*" + Regex.escape(call) + "\\s*$")

    private fun flat(text: String): String = text.replace(Regex("""\s+"""), "")

    /** The problems with the hold field: exactly one hold, asking the parent through requestDisallowInterceptTouchEvent. */
    private fun fieldProblems(source: String): List<String> {
        val code = flat(SourceText.code(source))
        return listOfNotNull(
            "the view does not build its hold as TouchHold { disallow -> parent?.requestDisallowInterceptTouchEvent(disallow) }".takeUnless { code.contains(flat(fieldText)) },
            "the view builds more than one TouchHold".takeUnless { code.split("TouchHold{").size == 2 },
        )
    }

    /** The problems with the down branch: it asks once, before the interpreter starts, and does not give the touch back. */
    private fun downProblems(source: String): List<String> {
        val body = touchBody(source) ?: return listOf("no onTouchEvent function with a block body was found")
        val down = branch(body, "ACTION_DOWN") ?: return listOf("the touch handler has no ACTION_DOWN branch")
        val ask = down.indexOf("hold.down()")
        val start = down.indexOf("interpreter.down(")
        return listOfNotNull(
            "the down branch does not call hold.down()".takeIf { ask < 0 },
            "the down branch calls hold.down() more than once".takeIf { down.split("hold.down()").size > 2 },
            "the down branch must call hold.down() before interpreter.down(".takeIf { ask >= 0 && start >= 0 && ask > start },
            "the down branch must call hold.down() as a plain statement".takeIf { ask >= 0 && !plainCall("hold.down()").containsMatchIn(down) },
            "the down branch gives the touch back".takeIf { down.contains("hold.release()") },
        )
    }

    /** The problems with a branch that ends the gesture: it gives the touch back once and never asks to hold it. */
    private fun endProblems(source: String, action: String): List<String> {
        val body = touchBody(source) ?: return listOf("no onTouchEvent function with a block body was found")
        val end = branch(body, action) ?: return listOf("the touch handler has no $action branch")
        return listOfNotNull(
            "the $action branch does not call hold.release()".takeUnless { end.contains("hold.release()") },
            "the $action branch calls hold.release() more than once".takeIf { end.split("hold.release()").size > 2 },
            "the $action branch asks to hold the touch".takeIf { end.contains("hold.down()") },
            "the $action branch must call hold.release() as a plain statement".takeIf { end.contains("hold.release()") && !plainCall("hold.release()").containsMatchIn(end) },
        )
    }

    private val goodTouch = """override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            hold.down()
            interpreter.down(x, y)
        }
        MotionEvent.ACTION_UP -> {
            hold.release()
            val index = 0
        }
        MotionEvent.ACTION_CANCEL -> {
            hold.release()
            interpreter.cancel()
        }
    }
    return true
}
"""
    private val goodField = "class V { private val hold = TouchHold { disallow -> parent?.requestDisallowInterceptTouchEvent(disallow) } }\n"

    @Test
    fun `the view builds one hold that asks its parent not to intercept`() {
        assertQuiet("the obeying field", fieldProblems(goodField))
        assertFires("a hold that asks nothing", fieldProblems(replaceOnce(view(), "parent?.requestDisallowInterceptTouchEvent(disallow)", "Unit")), "does not build its hold")
        assertFires("the request on the wrong view", fieldProblems(replaceOnce(view(), "parent?.requestDisallowInterceptTouchEvent(disallow)", "parent?.parent?.requestDisallowInterceptTouchEvent(disallow)")), "does not build its hold")
        assertFires("a flag that is always true", fieldProblems(replaceOnce(view(), "requestDisallowInterceptTouchEvent(disallow)", "requestDisallowInterceptTouchEvent(true)")), "does not build its hold")
        assertEquals("overlay: ScreenMic.kt must build its hold as the card says", emptyList<String>(), fieldProblems(view()))
    }

    @Test
    fun `the down branch asks the parent to leave the touch before the interpreter starts`() {
        assertQuiet("the obeying body", downProblems(goodTouch))
        assertFires("a deleted request", downProblems(replaceOnce(view(), "hold.down()\n                interpreter.down(x, y)", "interpreter.down(x, y)")), "does not call hold.down()")
        assertFires("a request after the interpreter", downProblems(replaceOnce(view(), downText, "interpreter.down(x, y)\n                hold.down()")), "before interpreter.down(")
        assertFires("a request only for the check side", downProblems(replaceOnce(view(), "hold.down()\n                interpreter.down(x, y)", "if (x > width / 2f) hold.down()\n                interpreter.down(x, y)")), "as a plain statement")
        assertFires("a touch given back at once", downProblems(replaceOnce(view(), "hold.down()\n                interpreter.down(x, y)", "hold.down()\n                hold.release()\n                interpreter.down(x, y)")), "gives the touch back")
        assertEquals("overlay: the down branch of ScreenMic.kt", emptyList<String>(), downProblems(view()))
    }

    @Test
    fun `the up branch gives the touch back`() {
        assertQuiet("the obeying body", endProblems(goodTouch, "ACTION_UP"))
        assertFires("a deleted release", endProblems(replaceOnce(view(), upText, "MotionEvent.ACTION_UP -> {"), "ACTION_UP"), "does not call hold.release()")
        assertFires("a release only on the left side", endProblems(replaceOnce(view(), upText, "MotionEvent.ACTION_UP -> {\n                if (event.x < width / 2f) hold.release()"), "ACTION_UP"), "as a plain statement")
        assertFires("an up that asks again", endProblems(replaceOnce(view(), upText, "MotionEvent.ACTION_UP -> {\n                hold.down()"), "ACTION_UP"), "asks to hold the touch")
        assertEquals("overlay: the up branch of ScreenMic.kt", emptyList<String>(), endProblems(view(), "ACTION_UP"))
    }

    @Test
    fun `the cancel branch gives the touch back`() {
        assertQuiet("the obeying body", endProblems(goodTouch, "ACTION_CANCEL"))
        assertFires("a deleted release", endProblems(replaceOnce(view(), cancelText, "MotionEvent.ACTION_CANCEL -> {"), "ACTION_CANCEL"), "does not call hold.release()")
        assertEquals("overlay: the cancel branch of ScreenMic.kt", emptyList<String>(), endProblems(view(), "ACTION_CANCEL"))
    }
}
