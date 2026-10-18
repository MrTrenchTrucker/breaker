package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pulse's source shape, read as text because the view and the animator cannot run on a plain JVM: the
 * animator is named in one file only, that file uses none of the timer, handler or log words, and the view's
 * four hooks start and stop the pulse through the pure rule.
 *
 * Each rule runs first on short made-up samples (a bad one must be reported with the right words, a good one
 * must not) and then on the real files. A file that cannot be found is a violation, never a pass.
 */
class PulseGateTest {

    private val view = "TileView.kt"
    private val pulseFile = PulseSourceRules.PULSE_FILE

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    /** The words that name the platform's clock and thread, split the same way as in the rules, so this file does not hold them whole. */
    private val threadWord = "Thr" + "ead"

    // ---- The animator is named in ArmedPulse.kt only ----

    /** A failure means an animator word appears in a main file other than the pulse file. */
    @Test
    fun `the animator words are banned in every main file except the pulse file`() {
        assertFires("a call to animate", PulseSourceRules.animatorProblems(mapOf(view to "fun f() { view.animate() }\n")), "animate")
        assertFires("a value animator", PulseSourceRules.animatorProblems(mapOf("TileStyle.kt" to "val a = ValueAnimator.ofFloat(0f, 1f)\n")), "TileStyle.kt uses the animator word ValueAnimator")
        assertFires("an animator type", PulseSourceRules.animatorProblems(mapOf("WindowManagerTileWindow.kt" to "val a: Animator? = null\n")), "WindowManagerTileWindow.kt uses the animator word Animator")
        assertFires("an object animator", PulseSourceRules.animatorProblems(mapOf("FloatingTile.kt" to "import android.animation.ObjectAnimator\n")), "FloatingTile.kt uses the animator word ObjectAnimator")
        assertQuiet("the animator in the pulse file", PulseSourceRules.animatorProblems(mapOf(pulseFile to "val a = ValueAnimator.ofFloat(0f, 1f)\n")))
        assertQuiet(
            "the words only in comments, and longer or lower-case names",
            PulseSourceRules.animatorProblems(mapOf(view to "// animate the ring with a ValueAnimator\n/* Animator */\nval animated = 1\nval animator = 2\nval animations = 3\n")),
        )

        val texts = ModuleFiles.mainTexts()
        assertTrue("overlay: the scan did not read the pulse file: ${texts.keys}", texts.containsKey(pulseFile))
        assertEquals("overlay: main sources other than $pulseFile must name no animator", emptyList<String>(), PulseSourceRules.animatorProblems(texts))
    }

    // ---- The pulse file holds no timer, handler, log or thread word ----

    /** A failure means the pulse file names a handler, timer, log or thread word, which the module must not use. */
    @Test
    fun `the pulse file holds none of the timer, handler, log and thread words`() {
        listOf(
            "Handler", "Looper", "Runnable", "Timer", "TimerTask", "post", "postDelayed", "postOnAnimation",
            "Choreographer", "Log", "println", "WakeLock", "wake lock", threadWord,
        ).forEach { word ->
            assertFires(word, PulseSourceRules.pulseFileProblems("fun f() { $word }\n"), "$pulseFile uses $word")
        }
        assertQuiet(
            "the words only in comments, and longer or lower-case names",
            PulseSourceRules.pulseFileProblems("// Handler Looper Runnable Timer post Log println WakeLock wake lock\n// $threadWord\n/* ValueAnimator */\nval postponed = 1\nval Logger = 2\nval handlers = 3\nval timerValue = 4\n"),
        )

        assertEquals(
            "overlay: $pulseFile must hold none of the banned words",
            emptyList<String>(),
            PulseSourceRules.pulseFileProblems(ModuleFiles.mainTexts().getValue(pulseFile)),
        )
    }

    // ---- The view's hooks start and stop the pulse through the pure rule ----

    private val hookSample = listOf(
        "internal class V(context: Context) : View(context) {",
        "override fun onAttachedToWindow() {",
        "super.onAttachedToWindow()",
        "attached = true",
        "refreshPulse()",
        "}",
        "override fun onDetachedFromWindow() {",
        "attached = false",
        "refreshPulse()",
        "super.onDetachedFromWindow()",
        "}",
        "override fun onWindowVisibilityChanged(visibility: Int) {",
        "super.onWindowVisibilityChanged(visibility)",
        "windowVisible = visibility == View.VISIBLE",
        "refreshPulse()",
        "}",
        "override fun onScreenStateChanged(screenState: Int) {",
        "super.onScreenStateChanged(screenState)",
        "screenOn = screenState != View.SCREEN_STATE_OFF",
        "refreshPulse()",
        "}",
        "private fun refreshPulse() {",
        "if (pulseShouldRun(wanted, attached, windowVisible, screenOn, ArmedPulse.animationsOn())) pulse.start() else pulse.stop()",
        "}",
        "}",
    ).joinToString("\n") + "\n"

    /** A failure means the view's pulse rule is given something other than the system's animator switch, so the pulse could run with animations off. */
    @Test
    fun `the view asks the system whether animations are on`() {
        assertQuiet("the real shape", PulseSourceRules.animationsArgumentProblems(hookSample))
        assertFires("the fifth argument replaced by true", PulseSourceRules.animationsArgumentProblems(edit(hookSample, "ArmedPulse.animationsOn()", "true")), "fifth argument of pulseShouldRun is 'true'")
        assertFires("the fifth argument replaced by false", PulseSourceRules.animationsArgumentProblems(edit(hookSample, "ArmedPulse.animationsOn()", "false")), "fifth argument of pulseShouldRun is 'false'")
        assertFires("no call", PulseSourceRules.animationsArgumentProblems("val x = 1\n"), "no pulseShouldRun call")
        assertEquals("overlay: $view must pass ArmedPulse.animationsOn() as the fifth argument", emptyList<String>(), PulseSourceRules.animationsArgumentProblems(ModuleFiles.mainTexts().getValue(view)))
    }

    /** A failure means a hook of the view is missing, does not reach the pure rule, or ignores the screen or the window state. */
    @Test
    fun `the view overrides its four pulse hooks and each reaches the pure rule`() {
        assertQuiet("the sample", PulseSourceRules.viewHookProblems(hookSample))
        assertQuiet("the sample with words in comments", PulseSourceRules.viewHookProblems("// pulseShouldRun( SCREEN_STATE_OFF\n" + hookSample))
        assertFires("a hook that is not overridden", PulseSourceRules.viewHookProblems(edit(hookSample, "override fun onScreenStateChanged", "fun onScreenStateChanged")), "TileView does not override onScreenStateChanged")
        assertFires("a hook named only in a comment", PulseSourceRules.viewHookProblems(edit(hookSample, "override fun onAttachedToWindow()", "// override fun onAttachedToWindow()\nfun onAttachedToWindow()")), "TileView does not override onAttachedToWindow")
        assertFires("the detach hook never refreshes", PulseSourceRules.viewHookProblems(edit(hookSample, "attached = false\nrefreshPulse()\n", "attached = false\n")), "onDetachedFromWindow does not call refreshPulse()")
        assertFires("the refresh does not run the rule", PulseSourceRules.viewHookProblems(edit(hookSample, "if (pulseShouldRun(", "if (run(")), "refreshPulse does not call pulseShouldRun(")
        assertFires("the screen-off state is not tested", PulseSourceRules.viewHookProblems(edit(hookSample, "View.SCREEN_STATE_OFF", "0")), "onScreenStateChanged does not test SCREEN_STATE_OFF")
        assertFires("the visible state is not tested", PulseSourceRules.viewHookProblems(edit(hookSample, "View.VISIBLE", "0")), "onWindowVisibilityChanged does not test View.VISIBLE")
        assertFires("the refresh never stops the pulse", PulseSourceRules.viewHookProblems(edit(hookSample, "pulse.stop()", "pulse.cancel()")), "refreshPulse does not start and stop the pulse")

        assertEquals("overlay: $view must override the four hooks and reach the pure pulse rule", emptyList<String>(), PulseSourceRules.viewHookProblems(ModuleFiles.mainTexts().getValue(view)))
    }

    // ---- The pulse starts, stops and is hidden in the order the ring needs ----

    /** A failure means `start` does not begin with the running guard, or creates the animator before it. */
    @Test
    fun `the pulse starts only after its running guard, which comes first`() {
        val real = ModuleFiles.mainTexts().getValue(pulseFile)
        assertQuiet("the real start", PulseSourceRules.startGuardProblems(real))
        assertFires(
            "the guard removed by a one-line edit",
            PulseSourceRules.startGuardProblems(edit(real, "        if (running) return\n        val next", "        val next")),
            "start does not begin with the running guard",
        )
        assertFires(
            "the guard placed after the animator is created",
            PulseSourceRules.startGuardProblems(
                edit(real, "        if (running) return\n        val next = ValueAnimator.ofFloat(PULSE_PHASE_START, PULSE_PHASE_END)\n", "        val next = ValueAnimator.ofFloat(PULSE_PHASE_START, PULSE_PHASE_END)\n        if (running) return\n"),
            ),
            "start creates the animator before the running guard",
        )
        assertFires("no start function", PulseSourceRules.startGuardProblems("val x = 1\n"), "has no start function")
    }

    /** A failure means `stop` does not cancel the animator, or sets the ring to full strength before it cancels. */
    @Test
    fun `stop sets the ring back to full strength after it cancels the animator`() {
        val real = ModuleFiles.mainTexts().getValue(pulseFile)
        assertQuiet("the real stop", PulseSourceRules.stopResetProblems(real))
        assertFires(
            "the full-strength call removed by a one-line edit",
            PulseSourceRules.stopResetProblems(edit(real, "        animator = null\n        onAlpha(1f)\n", "        animator = null\n")),
            "does not set the ring to full strength",
        )
        assertFires(
            "full strength set before the cancel",
            PulseSourceRules.stopResetProblems(edit(real, "        animator?.cancel()\n        animator = null\n        onAlpha(1f)\n", "        onAlpha(1f)\n        animator?.cancel()\n        animator = null\n")),
            "before it cancels the animator",
        )
        assertFires("no cancel", PulseSourceRules.stopResetProblems(edit(real, "animator?.cancel()", "animator?.end()")), "does not cancel the animator")
    }

    /** A failure means attach does not set attached true, or detach does not set it false before the refresh. */
    @Test
    fun `the detach and attach hooks of the view set attached before they refresh the pulse`() {
        val real = ModuleFiles.mainTexts().getValue(view)
        assertQuiet("the real view", PulseSourceRules.attachedFlagProblems(real))
        assertFires(
            "detach sets attached true by a one-line edit",
            PulseSourceRules.attachedFlagProblems(edit(real, "        attached = false\n        refreshPulse()\n", "        attached = true\n        refreshPulse()\n")),
            "onDetachedFromWindow does not set attached = false",
        )
        assertFires(
            "detach assigns attached after the refresh",
            PulseSourceRules.attachedFlagProblems(edit(real, "        attached = false\n        refreshPulse()\n", "        refreshPulse()\n        attached = false\n")),
            "onDetachedFromWindow sets attached = false after refreshPulse()",
        )
        assertFires(
            "attach leaves attached unset",
            PulseSourceRules.attachedFlagProblems(edit(real, "        attached = true\n        refreshPulse()\n", "        refreshPulse()\n")),
            "onAttachedToWindow does not set attached = true",
        )
    }

    /** A failure means hide does not stop the pulse, or removes the window before it stops the pulse. */
    @Test
    fun `hide stops the pulse before it removes the window`() {
        val real = ModuleFiles.mainTexts().getValue("TileController.kt")
        assertQuiet("the real hide", PulseSourceRules.hideOrderProblems(real))
        assertFires(
            "remove placed before the stop by a two-line swap",
            PulseSourceRules.hideOrderProblems(edit(real, "        window.setPulse(false)\n        window.remove()\n", "        window.remove()\n        window.setPulse(false)\n")),
            "removes the window before it stops the pulse",
        )
        assertFires(
            "the stop missing",
            PulseSourceRules.hideOrderProblems(edit(real, "        window.setPulse(false)\n", "")),
            "does not stop the pulse with window.setPulse(false)",
        )
    }

    /** A failure means drawRing does not draw the ring with the pulse-scaled colour, or the view sets the paint alpha, so the pulse would not show. */
    @Test
    fun `drawRing draws the ring with the pulse-scaled colour and sets no paint alpha`() {
        val drawn = "private fun drawRing(canvas: Canvas, cell: TileRect, color: Int) {\n" +
            "val stroke = TileMetrics.RING_DP * density\n" +
            "paint.color = ringDrawColor(color, pulseAlpha)\n" +
            "}\n"
        assertQuiet("the real shape", PulseSourceRules.ringDrawProblems(drawn))
        assertFires("the colour line missing", PulseSourceRules.ringDrawProblems(edit(drawn, "paint.color = ringDrawColor(color, pulseAlpha)\n", "")), "drawRing does not set paint.color")
        assertFires("the plain colour drawn", PulseSourceRules.ringDrawProblems(edit(drawn, "ringDrawColor(color, pulseAlpha)", "color")), "drawRing does not set paint.color")
        assertFires("the wrong pulse argument", PulseSourceRules.ringDrawProblems(edit(drawn, "ringDrawColor(color, pulseAlpha)", "ringDrawColor(color, 7)")), "drawRing does not set paint.color")
        assertFires("the paint alpha assigned", PulseSourceRules.ringDrawProblems(edit(drawn, "val stroke", "paint.alpha = pulseAlphaByte(pulseAlpha)\nval stroke")), "sets the paint alpha")
        assertFires("the paint alpha set by a call", PulseSourceRules.ringDrawProblems(edit(drawn, "val stroke", "paint.setAlpha(7)\nval stroke")), "sets the paint alpha")
        assertFires("no drawRing", PulseSourceRules.ringDrawProblems("val x = 1\n"), "no drawRing function")
        assertQuiet("the paint alpha only in a comment", PulseSourceRules.ringDrawProblems("// paint.alpha = 7\n" + drawn))

        assertEquals("overlay: $view must draw the ring with ringDrawColor and set no paint alpha", emptyList<String>(), PulseSourceRules.ringDrawProblems(ModuleFiles.mainTexts().getValue(view)))
    }

    /** A failure means the view's pulse callback does not store the animator's alpha into the field drawRing reads, or does not redraw the view. */
    @Test
    fun `the view's pulse callback stores the alpha into pulseAlpha and redraws the view`() {
        val callback = "private val pulse = ArmedPulse { alpha -> pulseAlpha = alpha; invalidate() }\n"
        assertQuiet("the sample", PulseSourceRules.pulseCallbackProblems(callback))
        assertQuiet("the redraw first, then the store", PulseSourceRules.pulseCallbackProblems(edit(callback, "pulseAlpha = alpha; invalidate()", "invalidate(); pulseAlpha = alpha")))
        assertFires("the store removed", PulseSourceRules.pulseCallbackProblems(edit(callback, "pulseAlpha = alpha; ", "")), "does not store alpha into pulseAlpha")
        assertFires("the redraw removed", PulseSourceRules.pulseCallbackProblems(edit(callback, "; invalidate()", "")), "does not call invalidate()")
        assertFires("the constant stored instead of the alpha", PulseSourceRules.pulseCallbackProblems(edit(callback, "pulseAlpha = alpha", "pulseAlpha = 1f")), "does not store alpha into pulseAlpha")
        assertFires("another field stored instead", PulseSourceRules.pulseCallbackProblems(edit(callback, "pulseAlpha = alpha", "otherAlpha = alpha")), "does not store alpha into pulseAlpha")
        assertFires("an empty body", PulseSourceRules.pulseCallbackProblems("private val pulse = ArmedPulse { alpha -> }\n"), "does not store alpha into pulseAlpha")
        assertFires("the parameter not named alpha", PulseSourceRules.pulseCallbackProblems(edit(callback, "alpha ->", "value ->")), "does not take alpha as its parameter")
        assertFires("the callback named only in a comment", PulseSourceRules.pulseCallbackProblems("// ArmedPulse { alpha -> pulseAlpha = alpha; invalidate() }\n"), "has no ArmedPulse")
        assertFires("no callback", PulseSourceRules.pulseCallbackProblems("val x = 1\n"), "has no ArmedPulse")

        assertEquals("overlay: $view must store the alpha into pulseAlpha and call invalidate() in its pulse callback", emptyList<String>(), PulseSourceRules.pulseCallbackProblems(ModuleFiles.mainTexts().getValue(view)))
    }
}
