package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source text checks for the probe wiring in ScreenMic.kt's touch handler and for the time field of the probe kit's
 * route lines. The line formats are pinned by ScreenMicProbeLinesTest; this gate pins WHERE the probe is called:
 * the down branch logs the touch, and the touch map line only while recording; the up branch logs the lift only
 * inside the index >= 0 guard (index -1 would throw on a real phone) and before interpreter.up; the cancel branch
 * logs the cancel; the tap routes with the event's own time; onDetachedFromWindow keeps its two lines. Each rule runs
 * first on a made-up body that obeys it, which must stay quiet, then on a mutant built from the real source with one
 * replace, which must be reported; only then is the real main source asserted quiet. Comments are blanked and string
 * literals are kept, as in the other source checks.
 */
class ScreenMicProbeWiringGateTest {

    private val viewFile = "ScreenMic.kt"
    private val kitFile = "ScreenMicProbeKit.kt"

    private val downCall = "ScreenMicProbeKit.downLog(event.eventTime, model.shape, model.state, x, y, height)"
    private val mapCall = "if (model.shape == TileShape.RECORDING) ScreenMicProbeKit.touchMapLog(event.eventTime, screenLocation(), x, y, height)"
    private val upCall = "ScreenMicProbeKit.upLog(event.eventTime, model.shape, model.state, event.getX(index), event.getY(index), height)"
    private val cancelCall = "ScreenMicProbeKit.cancelLog(event.eventTime, model.shape, model.state)"
    private val routeCall = "ScreenMicProbeKit.route(this, event.eventTime)"
    private val detachLines = "visible = false\n        refreshMotion()"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails unless [old] is in [text] exactly once, so a mutant can never be a no-op. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue("overlay: control: the text to change is not in the source exactly once: $old", text.split(old).size == 2)
        return text.replace(old, new)
    }

    /** The real ScreenMic.kt from the main sources, or a failure naming the file when it is not there. */
    private fun view(): String =
        ModuleFiles.mainTexts()[viewFile] ?: throw AssertionError("android_overlay: $viewFile is missing from the main sources")

    /** The real ScreenMicProbeKit.kt from the main sources, or a failure naming the file when it is not there. */
    private fun kit(): String =
        ModuleFiles.mainTexts()[kitFile] ?: throw AssertionError("android_overlay: $kitFile is missing from the main sources")

    /** The onTouchEvent body, comments blanked. */
    private fun touchBody(source: String): String? = AdapterRules.blockBodyOf(SourceText.code(source), "onTouchEvent")

    /** One branch of the touch handler's when: from its ACTION label to the next ACTION label or the end of the body. */
    private fun branch(body: String, action: String): String? {
        val start = body.indexOf("MotionEvent.$action ->")
        if (start < 0) return null
        val next = body.indexOf("MotionEvent.ACTION_", start + 1)
        return body.substring(start, if (next < 0) body.length else next)
    }

    private val noBody = listOf("no onTouchEvent function with a block body was found")

    /** The problems with the down branch: it logs the touch with the event time, and the touch map only while recording, once. */
    private fun downProblems(source: String): List<String> {
        val body = touchBody(source) ?: return noBody
        val down = branch(body, "ACTION_DOWN") ?: return listOf("the touch handler has no ACTION_DOWN branch")
        val log = Regex("""ScreenMicProbeKit\s*\.\s*downLog\(\s*event\.eventTime\s*,\s*model\.shape\s*,\s*model\.state\s*,""")
        val map = Regex("""if\s*\(\s*model\.shape\s*==\s*TileShape\.RECORDING\s*\)\s*ScreenMicProbeKit\s*\.\s*touchMapLog\(\s*event\.eventTime\s*,""")
        return listOfNotNull(
            "the down branch does not call downLog with the event time".takeUnless { log.containsMatchIn(down) },
            "the touch map line is not emitted only under the recording shape with the event time".takeUnless { map.containsMatchIn(down) },
            "the touch map line is called more than once in the down branch".takeUnless { down.split("touchMapLog(").size == 2 },
        )
    }

    /** The problems with the up branch: the lift is logged with the event time inside the index >= 0 guard and before interpreter.up. */
    private fun upProblems(source: String): List<String> {
        val body = touchBody(source) ?: return noBody
        val up = branch(body, "ACTION_UP") ?: return listOf("the touch handler has no ACTION_UP branch")
        val guard = Regex("""val\s+wasTap\s*=\s*if\s*\(\s*index\s*>=\s*0\s*\)\s*\{""").find(up)
            ?: return listOf("the up branch has no wasTap guard on index >= 0")
        val open = guard.range.last
        val close = SourceText.closeOf(up, open)
        if (close < 0) return listOf("the up guard block never closes")
        val inside = up.substring(open, close + 1)
        val before = up.substring(0, guard.range.first)
        val log = Regex("""ScreenMicProbeKit\s*\.\s*upLog\(\s*event\.eventTime\s*,\s*model\.shape\s*,\s*model\.state\s*,\s*event\.getX\(index\)\s*,\s*event\.getY\(index\)\s*,""").find(inside)
        return listOfNotNull(
            "the up probe line is not upLog with the event time inside the index >= 0 guard".takeIf { log == null },
            "the up branch reads a pointer position or logs before the index >= 0 guard".takeIf { before.contains("event.getX(") || before.contains("event.getY(") || before.contains("upLog(") },
            "the up probe line does not come before interpreter.up".takeIf { log != null && log.range.first > inside.indexOf("interpreter.up(") },
        )
    }

    /** The problems with the cancel branch: it logs the cancel with the event time. */
    private fun cancelProblems(source: String): List<String> {
        val body = touchBody(source) ?: return noBody
        val cancel = branch(body, "ACTION_CANCEL") ?: return listOf("the touch handler has no ACTION_CANCEL branch")
        val log = Regex("""ScreenMicProbeKit\s*\.\s*cancelLog\(\s*event\.eventTime\s*,\s*model\.shape\s*,\s*model\.state\s*\)""")
        return if (log.containsMatchIn(cancel)) emptyList() else listOf("the cancel branch does not call cancelLog with the event time")
    }

    /** The problems with the tap: it routes with the event time, only when the lift was a tap. */
    private fun routeProblems(source: String): List<String> {
        val body = touchBody(source) ?: return noBody
        val up = branch(body, "ACTION_UP") ?: return listOf("the touch handler has no ACTION_UP branch")
        val route = Regex("""if\s*\(\s*wasTap\s*\)\s*\{\s*ScreenMicProbeKit\s*\.\s*route\(\s*this\s*,\s*event\.eventTime\s*\)\s*\}""")
        return if (route.containsMatchIn(up)) emptyList() else listOf("the tap does not route with the event time under if (wasTap)")
    }

    /** The problems with onDetachedFromWindow: it hides the view, then refreshes the motion. */
    private fun detachProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "onDetachedFromWindow") ?: return listOf("no onDetachedFromWindow function with a block body was found")
        val hide = Regex("""\bvisible\s*=\s*false\b""").find(body)
        val refresh = body.indexOf("refreshMotion()")
        return listOfNotNull(
            "onDetachedFromWindow does not set visible = false".takeIf { hide == null },
            "onDetachedFromWindow does not call refreshMotion()".takeIf { refresh < 0 },
            "onDetachedFromWindow must set visible = false before refreshMotion()".takeIf { hide != null && refresh >= 0 && hide.range.first > refresh },
        )
    }

    /** The problems with the kit's route lines: at least one, and every one carries the time field after its tag. */
    private fun routeLineProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val all = Regex("line\\(\"0:").findAll(code).count()
        val timed = Regex("line\\(\"0:\\\$ts:").findAll(code).count()
        return listOfNotNull(
            "the kit has no route line".takeIf { all == 0 },
            "a route line of the kit does not carry the time field".takeIf { all != timed },
        )
    }

    // --- Bodies that obey the rules, for the quiet controls ---------------------------------------

    private val goodTouch = """override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val index = event.actionIndex
            val x = event.getX(index)
            val y = event.getY(index)
            $downCall
            $mapCall
        }
        MotionEvent.ACTION_UP -> {
            val index = event.findPointerIndex(activePointerId)
            val wasTap = if (index >= 0) {
                $upCall
                interpreter.up(event.getX(index), event.getY(index)) == TouchEvent.Tap
            } else false
            if (wasTap) {
                $routeCall
            }
        }
        MotionEvent.ACTION_CANCEL -> {
            interpreter.cancel()
            $cancelCall
        }
    }
    return true
}"""

    private val goodDetach = """override fun onDetachedFromWindow() {
    visible = false
    refreshMotion()
    super.onDetachedFromWindow()
}"""

    private val goodKit = """fun route(view: ScreenMicView, ts: Long) {
    when (val action = TileRouting.action(view.model.state, zone)) {
        TileAction.TAP -> { line("0:${'$'}ts:${'$'}{action.ordinal}") }
        TileAction.SEND -> { line("0:${'$'}ts:${'$'}{action.ordinal}") }
    }
}"""

    // --- Tests ------------------------------------------------------------------------------------

    /** A failure here means the down branch no longer logs the touch, or logs the touch map outside the recording shape. */
    @Test
    fun `the down branch logs the touch and the touch map only while recording`() {
        assertQuiet("the obeying touch body", downProblems(goodTouch))
        assertFires("a down log with the time 0", downProblems(replaceOnce(view(), downCall, downCall.replace("event.eventTime", "0L"))), "does not call downLog")
        assertFires("a deleted down log", downProblems(replaceOnce(view(), downCall, "")), "does not call downLog")
        assertFires("a touch map line with no recording guard", downProblems(replaceOnce(view(), mapCall, mapCall.removePrefix("if (model.shape == TileShape.RECORDING) "))), "touch map line is not emitted only")
        assertFires("a touch map line under the wrong shape test", downProblems(replaceOnce(view(), mapCall, mapCall.replace("==", "!="))), "touch map line is not emitted only")
        assertFires("a touch map line with the time 0", downProblems(replaceOnce(view(), mapCall, mapCall.replace("event.eventTime", "0L"))), "touch map line is not emitted only")
        assertFires("a second touch map line", downProblems(replaceOnce(view(), downCall, "$downCall\n                ScreenMicProbeKit.touchMapLog(event.eventTime, screenLocation(), x, y, height)")), "more than once")

        assertEquals("overlay: ScreenMic.kt down branch must log the touch and the touch map only while recording", emptyList<String>(), downProblems(view()))
    }

    /** A failure here means the up probe moved out of the index >= 0 guard (which throws on a real phone) or lost its time. */
    @Test
    fun `the up branch logs the lift inside the index guard and before interpreter up`() {
        assertQuiet("the obeying touch body", upProblems(goodTouch))
        assertFires("an up log moved above the guard", upProblems(replaceOnce(replaceOnce(view(), upCall, ""), "val wasTap = if (index >= 0) {", "$upCall\n                val wasTap = if (index >= 0) {")), "not upLog with the event time inside")
        assertFires("a deleted up log", upProblems(replaceOnce(view(), upCall, "")), "not upLog with the event time inside")
        assertFires("an up log with the time 0", upProblems(replaceOnce(view(), upCall, upCall.replace("event.eventTime", "0L"))), "not upLog with the event time inside")
        assertFires("an up log after interpreter up", upProblems(replaceOnce(replaceOnce(view(), upCall, ""), "} else false", "$upCall\n                } else false")), "does not come before interpreter.up")

        assertEquals("overlay: ScreenMic.kt up branch must log the lift inside the index >= 0 guard, before interpreter.up", emptyList<String>(), upProblems(view()))
    }

    /** A failure here means the cancel branch no longer logs the cancel with the event time. */
    @Test
    fun `the cancel branch logs the cancel`() {
        assertQuiet("the obeying touch body", cancelProblems(goodTouch))
        assertFires("a deleted cancel log", cancelProblems(replaceOnce(view(), cancelCall, "")), "does not call cancelLog")
        assertFires("a cancel log with the time 0", cancelProblems(replaceOnce(view(), cancelCall, cancelCall.replace("event.eventTime", "0L"))), "does not call cancelLog")

        assertEquals("overlay: ScreenMic.kt cancel branch must call cancelLog with the event time", emptyList<String>(), cancelProblems(view()))
    }

    /** A failure here means the tap no longer routes with the event time, or routes when the lift was not a tap. */
    @Test
    fun `the tap routes with the event time`() {
        assertQuiet("the obeying touch body", routeProblems(goodTouch))
        assertFires("a route with the time 0", routeProblems(replaceOnce(view(), routeCall, "ScreenMicProbeKit.route(this, 0L)")), "does not route with the event time")
        assertFires("a deleted route", routeProblems(replaceOnce(view(), routeCall, "")), "does not route with the event time")
        assertFires("a route not under wasTap", routeProblems(replaceOnce(view(), "if (wasTap) {", "if (true) {")), "does not route with the event time")

        assertEquals("overlay: ScreenMic.kt tap must route with the event time under if (wasTap)", emptyList<String>(), routeProblems(view()))
    }

    /** A failure here means onDetachedFromWindow lost one of its two lines or has them in the wrong order. */
    @Test
    fun `detaching hides the view and refreshes the motion`() {
        assertQuiet("the obeying detach body", detachProblems(goodDetach))
        assertFires("a detach without visible false", detachProblems(replaceOnce(view(), detachLines, "refreshMotion()")), "does not set visible = false")
        assertFires("a detach without the refresh", detachProblems(replaceOnce(view(), detachLines, "visible = false")), "does not call refreshMotion()")
        assertFires("a detach with the lines swapped", detachProblems(replaceOnce(view(), detachLines, "refreshMotion()\n        visible = false")), "before refreshMotion()")

        assertEquals("overlay: ScreenMic.kt onDetachedFromWindow must set visible = false, then call refreshMotion()", emptyList<String>(), detachProblems(view()))
    }

    /** A failure here means a route line of the kit lost its time field, so its fields shift by one for the log decoder. */
    @Test
    fun `every route line of the kit carries the time field`() {
        assertQuiet("the obeying route lines", routeLineProblems(goodKit))
        assertFires("a kit with no route line", routeLineProblems("fun route() {}"), "no route line")
        val old = "line(\"0:\$ts:\${action.ordinal}\")"
        assertTrue("overlay: control: the kit has no timed route line to mutate", kit().contains(old))
        assertFires("a route line without the time", routeLineProblems(kit().replaceFirst(old, "line(\"0:\${action.ordinal}\")")), "does not carry the time field")

        assertEquals("overlay: ScreenMicProbeKit.kt every route line must carry the time field", emptyList<String>(), routeLineProblems(kit()))
    }
}
