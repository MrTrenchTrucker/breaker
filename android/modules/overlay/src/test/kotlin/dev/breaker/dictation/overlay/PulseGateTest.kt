package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pulse's source shape, read as text because the view and the animator cannot run on a plain JVM: the
 * animator is named in the two animator files only, those files use none of the timer, handler or log words,
 * and the view's four hooks start and stop the pulse through the pure rule.
 *
 * Each rule runs first on short made-up samples (a bad one must be reported with the right words, a good one
 * must not) and then on the real files. A file that cannot be found is a violation, never a pass.
 */
class PulseGateTest {

    private val view = "TileView.kt"
    private val pulseFile = PulseSourceRules.PULSE_FILE
    private val breathingFile = PulseSourceRules.BREATHING_FILE

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

    // ---- The animator is named in the two animator files only ----

    /** A failure means an animator word appears in a main file other than the two animator files. */
    @Test
    fun `the animator words are banned in every main file except the two animator files`() {
        assertFires("a call to animate", PulseSourceRules.animatorProblems(mapOf(view to "fun f() { view.animate() }\n")), "animate")
        assertFires("a value animator", PulseSourceRules.animatorProblems(mapOf("TileStyle.kt" to "val a = ValueAnimator.ofFloat(0f, 1f)\n")), "TileStyle.kt uses the animator word ValueAnimator")
        assertFires("an animator type", PulseSourceRules.animatorProblems(mapOf("WindowManagerTileWindow.kt" to "val a: Animator? = null\n")), "WindowManagerTileWindow.kt uses the animator word Animator")
        assertFires("an object animator", PulseSourceRules.animatorProblems(mapOf("FloatingTile.kt" to "import android.animation.ObjectAnimator\n")), "FloatingTile.kt uses the animator word ObjectAnimator")
        assertQuiet("the animator in the pulse file", PulseSourceRules.animatorProblems(mapOf(pulseFile to "val a = ValueAnimator.ofFloat(0f, 1f)\n")))
        assertQuiet("the animator in the breathing file", PulseSourceRules.animatorProblems(mapOf(breathingFile to "val a = ValueAnimator.ofFloat(0f, 1f)\n")))
        assertQuiet(
            "the words only in comments, and longer or lower-case names",
            PulseSourceRules.animatorProblems(mapOf(view to "// animate the ring with a ValueAnimator\n/* Animator */\nval animated = 1\nval animator = 2\nval animations = 3\n")),
        )

        val texts = ModuleFiles.mainTexts()
        assertTrue("overlay: the scan did not read the pulse file: ${texts.keys}", texts.containsKey(pulseFile))
        assertEquals("overlay: main sources other than the animator files must name no animator", emptyList<String>(), PulseSourceRules.animatorProblems(texts))
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

    // ---- The two animator files cannot drift apart, and the breathing file keeps the armed file's shape ----

    /** A failure means the animator set drifted: a third file may name an animator, or a file was renamed out of the set. */
    @Test
    fun `the animator set holds exactly the two animator files, both present in the main sources`() {
        assertEquals(
            "overlay: the animator files must be exactly the armed and breathing pulse files",
            setOf(pulseFile, breathingFile),
            PulseSourceRules.ANIMATOR_FILES,
        )
        assertTrue("overlay: the animator files must hold the pulse file", pulseFile in PulseSourceRules.ANIMATOR_FILES)
        assertTrue("overlay: the animator files must hold the breathing file", breathingFile in PulseSourceRules.ANIMATOR_FILES)

        val texts = ModuleFiles.mainTexts()
        assertTrue("overlay: the scan did not read the breathing file: ${texts.keys}", texts.containsKey(breathingFile))
        assertTrue("overlay: the scan did not read the pulse file: ${texts.keys}", texts.containsKey(pulseFile))
        assertQuiet("both animator files naming an animator", PulseSourceRules.animatorProblems(texts))
        assertFires(
            "a third file naming an animator",
            PulseSourceRules.animatorProblems(texts + ("TileController.kt" to "val a = ValueAnimator.ofFloat(0f, 1f)\n")),
            "TileController.kt uses the animator word ValueAnimator",
        )
    }

    /** A failure means the breathing file's start, stop or banned words break the shape the armed file keeps. */
    @Test
    fun `the breathing file keeps the same start, stop and banned word shape as the armed file`() {
        val breathing = ModuleFiles.mainTexts().getValue(breathingFile)
        assertQuiet("the breathing start", PulseSourceRules.startGuardProblems(breathing, breathingFile))
        assertQuiet("the breathing stop", PulseSourceRules.stopResetProblems(breathing, breathingFile))
        assertQuiet("the breathing banned words", PulseSourceRules.animatorFileProblems(breathingFile, breathing))
        assertQuiet("the breathing start phase", PulseSourceRules.breathingStartProblems(breathing))

        // the start-phase pin, each check proven able to fire on the single-line edit it names
        assertFires("a breathing start with no seek", PulseSourceRules.breathingStartProblems(edit(breathing, "        next.setCurrentFraction(BREATHING_PHASE_START)\n", "")), "$breathingFile start does not seek the cycle to BREATHING_PHASE_START before it runs")
        assertFires("a breathing start narrowed to 0.5..1 instead of sought", PulseSourceRules.breathingStartProblems(edit(breathing, "ValueAnimator.ofFloat(0f, 1f)", "ValueAnimator.ofFloat(0.5f, 1f)")), "$breathingFile start does not keep the whole breath in one loop (ValueAnimator.ofFloat(0f, 1f))")
        assertFires("a breathing start that runs before it seeks", PulseSourceRules.breathingStartProblems(edit(breathing, "        next.setCurrentFraction(BREATHING_PHASE_START)\n        animator = next\n        next.start()", "        animator = next\n        next.start()\n        next.setCurrentFraction(BREATHING_PHASE_START)")), "$breathingFile start runs the animator before it seeks it")
        assertFires("a breathing start sought to a hard-coded 0.5f rather than the named phase", PulseSourceRules.breathingStartProblems(edit(breathing, "setCurrentFraction(BREATHING_PHASE_START)", "setCurrentFraction(0.5f)")), "$breathingFile start does not seek the cycle to BREATHING_PHASE_START before it runs")

        assertFires("a banned word in the breathing file", PulseSourceRules.animatorFileProblems(breathingFile, "fun f() { Handler }\n"), "$breathingFile uses Handler")
        assertQuiet("a banned word only in a comment", PulseSourceRules.animatorFileProblems(breathingFile, "// Handler\n/* Looper */\n"))
        assertFires("no start function", PulseSourceRules.startGuardProblems("val x = 1\n", breathingFile), "$breathingFile has no start function")
        assertFires("no stop function", PulseSourceRules.stopResetProblems("val x = 1\n", breathingFile), "$breathingFile has no stop function")
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

    // ---- The Home mic's whole motion arrangement: the two hooks re-take it, and only the level pulse takes the level ----

    /** The main file the Home mic's arrangement is read from, and the name its case uses. */
    private val screenMicFile = "ScreenMic.kt"

    /**
     * The arrangement, as made-up source: the two platform hooks each re-take the motion, the detach hook sets
     * `visible = false` before it re-takes, `refreshMotion` takes the motion and gives the level pulse its own
     * arm that stops the breathing and starts the smoother from silence, and `setModelLevel` smooths the level
     * into `motionAlpha` only while the motion is the level pulse.
     */
    private val motionSample = listOf(
        "internal class ScreenMicView(context: Context) : View(context) {",
        "private val breathing = BreathingPulse { motionAlpha = it }",
        "private var motionAlpha: Float = 1f",
        "private var levelSmooth: Float = 0f",
        "override fun onVisibilityAggregated(isVisible: Boolean) {",
        "super.onVisibilityAggregated(isVisible)",
        "visible = isVisible",
        "refreshMotion()",
        "}",
        "override fun onDetachedFromWindow() {",
        "visible = false",
        "refreshMotion()",
        "super.onDetachedFromWindow()",
        "}",
        "private fun refreshMotion() {",
        "val switchOn = switchOnFor(model.state)",
        "val ready = model.state == TileState.ARMED",
        "when (micMotion(model.state, switchOn, ready, visible, BreathingPulse.animationsOn())) {",
        "MicMotion.LEVEL_PULSE -> {",
        "breathing.stop()",
        "levelSmooth = 0f",
        "}",
        "MicMotion.STEADY, MicMotion.DIM_STEADY -> breathing.stop()",
        "MicMotion.BREATHE -> breathing.start()",
        "}",
        "}",
        "private fun motionIsLevelPulse(): Boolean =",
        "model.state == TileState.RECORDING && visible && BreathingPulse.animationsOn()",
        "fun setModelLevel(level: Float) {",
        "model.setLevel(level)",
        "if (motionIsLevelPulse()) {",
        "levelSmooth = levelSmoothStep(levelSmooth, level)",
        "motionAlpha = levelAlpha(levelSmooth)",
        "}",
        "invalidate()",
        "}",
        "}",
    ).joinToString("\n") + "\n"

    /** A failure means the two hooks, the level pulse arm or the level's path into the glow's alpha is not the arrangement this pins. */
    @Test
    fun `the Home mic takes its motion in two hooks and only the level pulse takes the level`() {
        assertQuiet("the made-up arrangement", PulseSourceRules.screenMicMotionProblems(motionSample))
        assertQuiet(
            "the arrangement with the words of the rule only in comments",
            PulseSourceRules.screenMicMotionProblems("// onVisibilityAggregated onDetachedFromWindow refreshMotion micMotion breathing.start() breathing.stop()\n" + motionSample),
        )

        assertFires("the visibility hook not overridden", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "override fun onVisibilityAggregated", "fun onVisibilityAggregated")), "does not override onVisibilityAggregated")
        assertFires("the detach hook not overridden", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "override fun onDetachedFromWindow", "fun onDetachedFromWindow")), "does not override onDetachedFromWindow")
        assertFires("the visibility hook never refreshes", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "visible = isVisible\nrefreshMotion()", "visible = isVisible")), "onVisibilityAggregated does not call refreshMotion()")
        assertFires("the detach hook never refreshes", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "refreshMotion()\nsuper.onDetachedFromWindow()", "super.onDetachedFromWindow()")), "onDetachedFromWindow does not call refreshMotion()")
        assertFires("the detach hook does not set visible false", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "visible = false\nrefreshMotion()", "refreshMotion()")), "onDetachedFromWindow does not set visible = false")
        assertFires("the detach hook sets visible false after the refresh", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "visible = false\nrefreshMotion()", "refreshMotion()\nvisible = false")), "onDetachedFromWindow sets visible = false after it calls refreshMotion()")
        assertFires("the refresh does not take the motion", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "when (micMotion(", "when (run(")), "refreshMotion does not take the motion with micMotion(")
        assertFires("the refresh re-derives switchOn inline", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "val switchOn = switchOnFor(model.state)", "val switchOn = model.state == TileState.IDLE")), "refreshMotion does not take switchOn from switchOnFor(model.state)")
        assertFires("the refresh never stops the breathing", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "MicMotion.STEADY, MicMotion.DIM_STEADY -> breathing.stop()\nMicMotion.BREATHE -> breathing.start()\n", "MicMotion.STEADY, MicMotion.DIM_STEADY -> Unit\nMicMotion.BREATHE -> Unit\n")), "refreshMotion does not start and stop the breathing")
        assertFires("the level pulse arm does not stop the breathing", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "breathing.stop()\nlevelSmooth = 0f", "levelSmooth = 0f")), "does not stop the breathing in the level pulse arm")
        assertFires("the level pulse arm does not reset the smoother", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "breathing.stop()\nlevelSmooth = 0f", "breathing.stop()")), "does not reset levelSmooth = 0f in the level pulse arm")
        assertFires("the level pulse arm is missing", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "MicMotion.LEVEL_PULSE -> {\nbreathing.stop()\nlevelSmooth = 0f\n}\n", "")), "refreshMotion has no LEVEL_PULSE arm")
        assertFires("the level path has no guard", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "if (motionIsLevelPulse()) {", "if (true) {")), "setModelLevel does not tell whether the motion is the level pulse")
        assertFires("the level is not smoothed", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "levelSmooth = levelSmoothStep(levelSmooth, level)", "levelSmooth = level")), "setModelLevel does not smooth the level with levelSmoothStep(")
        assertFires("the smoothed level is not given an alpha", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "motionAlpha = levelAlpha(levelSmooth)", "motionAlpha = 1f")), "setModelLevel does not give the smoothed level to levelAlpha(")
        assertFires("another field takes the alpha", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "motionAlpha = levelAlpha(levelSmooth)", "otherAlpha = levelAlpha(levelSmooth)")), "setModelLevel does not give the smoothed level to motionAlpha")
        assertFires("the level is not pushed to the model", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "model.setLevel(level)", "model.state")), "setModelLevel does not push the level with model.setLevel(")
        assertFires("the view is not redrawn", PulseSourceRules.screenMicMotionProblems(edit(motionSample, "invalidate()\n}\n}", "}\n}")), "setModelLevel does not redraw with invalidate()")

        val texts = ModuleFiles.mainTexts()
        assertTrue("overlay: the scan did not read $screenMicFile: ${texts.keys}", texts.containsKey(screenMicFile))
        assertEquals("overlay: $screenMicFile must hold the whole motion arrangement", emptyList<String>(), PulseSourceRules.screenMicMotionProblems(texts.getValue(screenMicFile)))
    }
}
