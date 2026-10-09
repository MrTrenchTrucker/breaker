package dev.breaker.dictation.overlay

/**
 * Source-text rules for the armed pulse: the one file that may name an animator, the words that file may not
 * use, and the hooks of the tile view that start and stop the pulse. Each rule is a pure function from source
 * text to a list of problems, with comments blanked and string literals kept, as in [AdapterRules].
 */
internal object PulseSourceRules {

    /** The only main file that may name an animator, and the file the words below are banned from. */
    const val PULSE_FILE = "ArmedPulse.kt"

    private val animatorWords = listOf("Animator", "ValueAnimator", "ObjectAnimator", "animate")

    /** Split so this file does not hold the thread word whole, which the clock and thread scan of test sources reads. */
    private val threadWord = "Thr" + "ead"

    private val pulseFileWords = listOf(
        "Handler", "Looper", "Runnable", "Timer", "TimerTask", "post", "postDelayed", "postOnAnimation",
        "Choreographer", "Log", "println", "WakeLock", "wake lock", threadWord,
    )

    private val viewHooks = listOf("onAttachedToWindow", "onDetachedFromWindow", "onWindowVisibilityChanged", "onScreenStateChanged")

    /** The animator words used in any main file other than [PULSE_FILE], by file name, as whole words. */
    fun animatorProblems(files: Map<String, String>): List<String> =
        files.filterKeys { it != PULSE_FILE }.flatMap { (name, source) ->
            val code = SourceText.code(source)
            animatorWords.filter { Regex("\\b$it\\b").containsMatchIn(code) }.map { "$name uses the animator word $it" }
        }

    /** The banned words found in [PULSE_FILE] code, as whole words. */
    fun pulseFileProblems(source: String): List<String> {
        val code = SourceText.code(source)
        return pulseFileWords.filter { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(code) }.map { "$PULSE_FILE uses $it" }
    }

    /** The problems with the tile view's pulse hooks: each of the four is overridden, calls refreshPulse, and refreshPulse runs the pure rule. */
    fun viewHookProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val hooks = viewHooks.flatMap { hook ->
            val overridden = Regex("\\boverride\\s+fun\\s+$hook\\s*\\(").containsMatchIn(code)
            val body = AdapterRules.blockBodyOf(code, hook)
            listOfNotNull(
                "TileView does not override $hook".takeUnless { overridden },
                "$hook does not call refreshPulse()".takeIf { overridden && body?.contains("refreshPulse()") != true },
            )
        }
        val visibility = AdapterRules.blockBodyOf(code, "onWindowVisibilityChanged")
        val screen = AdapterRules.blockBodyOf(code, "onScreenStateChanged")
        val refresh = AdapterRules.blockBodyOf(code, "refreshPulse")
        return hooks + listOfNotNull(
            "onWindowVisibilityChanged does not test View.VISIBLE".takeIf { visibility != null && !visibility.contains("View.VISIBLE") },
            "onScreenStateChanged does not test SCREEN_STATE_OFF".takeIf { screen != null && !screen.contains("SCREEN_STATE_OFF") },
            "TileView has no refreshPulse function with a block body".takeIf { refresh == null },
            "refreshPulse does not call pulseShouldRun(".takeIf { refresh != null && !refresh.contains("pulseShouldRun(") },
            "refreshPulse does not start and stop the pulse".takeIf { refresh != null && !(refresh.contains("pulse.start()") && refresh.contains("pulse.stop()")) },
        )
    }

    /** The problems with the pulse rule's call in the view: exactly five arguments, and the fifth is the system's animator switch. */
    fun animationsArgumentProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val call = Regex("\\bpulseShouldRun\\s*\\(").findAll(code)
            .firstOrNull { !code.substring(0, it.range.first).trimEnd().endsWith("fun") }
            ?: return listOf("no pulseShouldRun call was found")
        val arguments = ArrayList<String>()
        var depth = 0
        var start = call.range.last + 1
        var at = call.range.last
        while (at < code.length && !(code[at] == ')' && depth == 1)) {
            if (code[at] == '(') depth++
            if (code[at] == ')') depth--
            if (code[at] == ',' && depth == 1) {
                arguments.add(code.substring(start, at))
                start = at + 1
            }
            at++
        }
        if (at >= code.length) return listOf("the pulseShouldRun call never closes")
        arguments.add(code.substring(start, at))
        val values = arguments.map { it.trim().replace(Regex("\\s+"), " ") }
        return listOfNotNull(
            "pulseShouldRun is given ${values.size} arguments, not five".takeIf { values.size != 5 },
            "the fifth argument of pulseShouldRun is '${values.getOrNull(4)}', not ArmedPulse.animationsOn()"
                .takeIf { values.size == 5 && values[4] != "ArmedPulse.animationsOn()" },
        )
    }

    /** The problems with `start`: its block begins with the running guard, and the guard comes before the animator is created. */
    fun startGuardProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "start")
            ?: return listOf("$PULSE_FILE has no start function with a block body")
        val guard = Regex("\\bif\\s*\\(\\s*running\\s*\\)\\s*return\\b").find(body)
        val create = Regex("\\bValueAnimator\\s*\\.\\s*ofFloat\\s*\\(").find(body)
        return listOfNotNull(
            "$PULSE_FILE start does not begin with the running guard 'if (running) return'"
                .takeIf { guard == null || body.substring(1, guard.range.first).isNotBlank() },
            "$PULSE_FILE start does not create the animator with ValueAnimator.ofFloat".takeIf { create == null },
            "$PULSE_FILE start creates the animator before the running guard"
                .takeIf { guard != null && create != null && create.range.first < guard.range.first },
        )
    }

    /** The problems with `stop`: it cancels the animator, and only after that sets the ring back to full strength. */
    fun stopResetProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "stop")
            ?: return listOf("$PULSE_FILE has no stop function with a block body")
        val cancel = Regex("\\bcancel\\s*\\(\\s*\\)").find(body)
        val full = Regex("\\bonAlpha\\s*\\(\\s*1f\\s*\\)").find(body)
        return listOfNotNull(
            "$PULSE_FILE stop does not cancel the animator".takeIf { cancel == null },
            "$PULSE_FILE stop does not set the ring to full strength with onAlpha(1f)".takeIf { full == null },
            "$PULSE_FILE stop sets the ring to full strength before it cancels the animator"
                .takeIf { cancel != null && full != null && full.range.first < cancel.range.first },
        )
    }

    /** The problems with the view's attach flag: attach sets it true and detach sets it false, each before the refresh. */
    fun attachedFlagProblems(source: String): List<String> {
        val code = SourceText.code(source)
        return listOf("onAttachedToWindow" to "true", "onDetachedFromWindow" to "false").flatMap { (hook, value) ->
            val body = AdapterRules.blockBodyOf(code, hook)
                ?: return@flatMap listOf("TileView has no $hook function with a block body")
            val set = Regex("(?<![\\w.])attached\\s*=\\s*$value\\b").find(body)
            val refresh = body.indexOf("refreshPulse()")
            listOfNotNull(
                "$hook does not set attached = $value".takeIf { set == null },
                "$hook sets attached = $value after refreshPulse()"
                    .takeIf { set != null && refresh >= 0 && set.range.first > refresh },
            )
        }
    }

    /** The problems with `hide` of the controller: it stops the pulse, and only then removes the window. */
    fun hideOrderProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "hide")
            ?: return listOf("TileController has no hide function with a block body")
        val stop = Regex("\\bwindow\\.setPulse\\s*\\(\\s*false\\s*\\)").find(body)
        val remove = Regex("\\bwindow\\.remove\\s*\\(\\s*\\)").find(body)
        return listOfNotNull(
            "TileController hide does not stop the pulse with window.setPulse(false)".takeIf { stop == null },
            "TileController hide does not remove the window with window.remove()".takeIf { remove == null },
            "TileController hide removes the window before it stops the pulse"
                .takeIf { stop != null && remove != null && remove.range.first < stop.range.first },
        )
    }

    /** The problems with `drawRing`: it sets the paint colour from the pulse-scaled colour, and nothing in the view sets the paint alpha. */
    fun ringDrawProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val body = AdapterRules.blockBodyOf(code, "drawRing") ?: return listOf("TileView has no drawRing function with a block body")
        return listOfNotNull(
            "drawRing does not set paint.color = ringDrawColor(color, pulseAlpha)"
                .takeUnless { Regex("\\bpaint\\.color\\s*=\\s*ringDrawColor\\(\\s*color\\s*,\\s*pulseAlpha\\s*\\)").containsMatchIn(body) },
            "TileView sets the paint alpha, with paint.alpha or paint.setAlpha, which the pulse must not do"
                .takeIf { Regex("\\bpaint\\s*\\.\\s*(?:alpha\\b|setAlpha\\s*\\()").containsMatchIn(code) },
        )
    }

    /**
     * The problems with the view's pulse callback, `ArmedPulse { alpha -> ... }`: the lambda takes the animator's alpha,
     * stores it into pulseAlpha, the field drawRing reads, and calls invalidate(). Both statements must be there, in
     * either order. The lambda is found by its braces (SourceText.closeOf), since blockBodyOf reads only `fun` bodies.
     */
    fun pulseCallbackProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val head = Regex("\\bArmedPulse\\s*\\{").find(code)
            ?: return listOf("TileView has no ArmedPulse { ... } callback")
        val open = head.range.last
        val close = SourceText.closeOf(code, open)
        if (close < 0) return listOf("the ArmedPulse callback never closes")
        val body = code.substring(open + 1, close)
        val arrow = Regex("^\\s*alpha\\s*->").find(body)
            ?: return listOf("the ArmedPulse callback does not take alpha as its parameter")
        val statements = body.substring(arrow.range.last + 1)
            .split(';', '\n').map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        return listOfNotNull(
            "the ArmedPulse callback does not store alpha into pulseAlpha".takeUnless { statements.contains("pulseAlpha = alpha") },
            "the ArmedPulse callback does not call invalidate()".takeUnless { statements.contains("invalidate()") },
        )
    }
}
