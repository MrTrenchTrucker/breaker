package dev.breaker.dictation.overlay

/**
 * The source-text checks of the Android adapters that have to follow code structure
 * rather than look for a word: where the window is added and what its guard catches,
 * where the permission answer comes from, what the frame change does, and where the
 * tile view takes its colours and its description from.
 *
 * Each is a pure function from source text to a list of violations, with comments
 * blanked and string literals kept. A construct that cannot be found is reported as
 * a violation, never skipped.
 */
internal object AdapterRules {

    private class Guard(val range: IntRange, val catches: List<Pair<String, String>>)

    private val tryOpen = Regex("\\btry\\s*\\{")
    private val catchHead = Regex("^\\s*catch\\s*\\(([^)]*)\\)\\s*\\{")
    private val refusedReturn = Regex("\\breturn\\s+AddOutcome\\.REFUSED\\b")

    /** Every try block of [code] with the catch clauses that directly follow it, as (parameter, body) pairs. */
    private fun guardsIn(code: String): List<Guard> = tryOpen.findAll(code).mapNotNull { match ->
        val open = match.range.last
        val close = SourceText.closeOf(code, open)
        if (close < 0) return@mapNotNull null
        val catches = ArrayList<Pair<String, String>>()
        var next = close + 1
        while (true) {
            val head = catchHead.find(code.substring(next)) ?: break
            val bodyOpen = next + head.range.last
            val bodyClose = SourceText.closeOf(code, bodyOpen)
            if (bodyClose < 0) break
            catches.add(head.groupValues[1] to code.substring(bodyOpen + 1, bodyClose))
            next = bodyClose + 1
        }
        Guard(open..close, catches)
    }.toList()

    private fun refusing(body: String): Boolean = refusedReturn.containsMatchIn(body) || body.trim() == "AddOutcome.REFUSED"

    private fun catchesType(parameter: String, type: String): Boolean = Regex("\\b$type\\b").containsMatchIn(parameter)

    private fun refuses(guard: Guard): Boolean = listOf("BadTokenException", "SecurityException").all { type ->
        guard.catches.any { (parameter, body) -> catchesType(parameter, type) && refusing(body) }
    }

    /** True when the last catch of [guard] is RuntimeException and refuses, after catches of BadTokenException and SecurityException. */
    private fun refusesAnyRuntimeFailure(guard: Guard): Boolean {
        val last = guard.catches.lastOrNull() ?: return false
        val before = guard.catches.dropLast(1)
        return catchesType(last.first, "RuntimeException") && refusing(last.second) &&
            listOf("BadTokenException", "SecurityException").all { type -> before.any { catchesType(it.first, type) } }
    }

    fun guardProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val calls = Regex("\\baddView\\s*\\(").findAll(code).map { it.range.first }.toList()
        if (calls.isEmpty()) return listOf("no addView call was found")
        val guards = guardsIn(code)
        return calls.mapNotNull { at ->
            val enclosing = guards.filter { at in it.range }
            when {
                enclosing.isEmpty() -> "addView at offset $at is not inside a try"
                enclosing.none { refuses(it) } -> "addView at offset $at is in a try that does not catch BadTokenException and SecurityException and return REFUSED"
                else -> null
            }
        }
    }

    private val canDrawHead = Regex("\\bfun\\s+canDrawOverlays\\s*\\(\\s*\\)\\s*(?::\\s*Boolean\\s*)?")

    /** The body of `canDrawOverlays`, block or expression, or null when there is no such function. */
    private fun canDrawBody(code: String): String? {
        val at = canDrawHead.find(code)?.range?.last?.plus(1) ?: return null
        if (at >= code.length) return null
        return when (code[at]) {
            '{' -> SourceText.closeOf(code, at).let { if (it < 0) null else code.substring(at, it + 1) }
            '=' -> expressionFrom(code, at + 1)
            else -> null
        }
    }

    private fun expressionFrom(code: String, start: Int): String {
        var depth = 0
        for (i in start until code.length) {
            if (code[i] == '(') depth++
            if (code[i] == ')') depth--
            if (code[i] == '\n' && depth <= 0) {
                val before = code.substring(start, i).trimEnd().lastOrNull()
                val after = code.substring(i + 1).trimStart().firstOrNull()
                val continued = before == null || before in "&|+-*?:=(," || (after != null && after in ".?&|+-*:")
                if (!continued) return code.substring(start, i)
            }
        }
        return code.substring(start)
    }

    fun permissionAnswerProblems(source: String): List<String> {
        val body = canDrawBody(SourceText.code(source)) ?: return listOf("no canDrawOverlays function with a body was found")
        return listOfNotNull(
            "canDrawOverlays does not call Settings.canDrawOverlays(".takeUnless { Regex("\\bSettings\\.canDrawOverlays\\s*\\(").containsMatchIn(body) },
            "canDrawOverlays contains a literal true".takeIf { Regex("\\btrue\\b").containsMatchIn(body) },
        )
    }
    /** The problems with the window add's catches beyond the two refusal types: the last catch must be RuntimeException and must refuse too. */
    fun runtimeCatchProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val calls = Regex("\\baddView\\s*\\(").findAll(code).map { it.range.first }.toList()
        if (calls.isEmpty()) return listOf("no addView call was found")
        val guards = guardsIn(code)
        return calls.mapNotNull { at ->
            val enclosing = guards.filter { at in it.range }
            when {
                enclosing.isEmpty() -> "addView at offset $at is not inside a try"
                enclosing.none { refusesAnyRuntimeFailure(it) } -> "addView at offset $at is in a try whose last catch is not RuntimeException returning REFUSED after the BadTokenException and SecurityException catches"
                else -> null
            }
        }
    }

    /** The block body of the first function called [name] in [code], braces included, or null when it has none. */
    internal fun blockBodyOf(code: String, name: String): String? {
        val head = Regex("\\bfun\\s+$name\\s*\\(").find(code) ?: return null
        var depth = 0
        var at = head.range.last
        while (at < code.length) {
            if (code[at] == '(') depth++
            if (code[at] == ')' && --depth == 0) break
            at++
        }
        val open = code.indexOf('{', at)
        if (open < 0 || code.substring(at, open).contains('=')) return null
        val close = SourceText.closeOf(code, open)
        return if (close < 0) null else code.substring(open, close + 1)
    }

    private val throwWord = Regex("\\bthrow\\b")

    /** The problems with `setFrame`: it must set all four params, call updateViewLayout, and swallow IllegalArgumentException from it. */
    fun setFrameProblems(source: String): List<String> {
        val body = blockBodyOf(SourceText.code(source), "setFrame") ?: return listOf("no setFrame function with a block body was found")
        val update = Regex("\\bupdateViewLayout\\s*\\(").find(body)
        val swallowed = update != null && guardsIn(body).any { guard ->
            update.range.first in guard.range &&
                guard.catches.any { (parameter, catchBody) -> catchesType(parameter, "IllegalArgumentException") && !throwWord.containsMatchIn(catchBody) }
        }
        return listOfNotNull(
            "setFrame does not call updateViewLayout(".takeIf { update == null },
            "setFrame calls updateViewLayout without swallowing IllegalArgumentException".takeIf { update != null && !swallowed },
        ) + listOf("x", "y", "width", "height")
            .filterNot { Regex("\\bparams\\.$it\\s*=(?!=)").containsMatchIn(body) }
            .map { "setFrame does not set params.$it" }
    }

    /** The problems with the window's `applyFace`: it must pass the face on to the view it holds. */
    fun applyFacePassOnProblems(source: String): List<String> {
        val body = blockBodyOf(SourceText.code(source), "applyFace") ?: return listOf("no applyFace function with a block body was found")
        return listOfNotNull(
            "applyFace does not call tileView.applyFace(face)".takeUnless { Regex("\\btileView\\s*\\??\\.\\s*applyFace\\s*\\(\\s*face\\s*\\)").containsMatchIn(body) },
        )
    }

    private val windowFlags = listOf("FLAG_NOT_FOCUSABLE", "FLAG_NOT_TOUCH_MODAL", "FLAG_LAYOUT_IN_SCREEN")

    /** The problems with the window flags: exactly the three of the plain tile, named once built, and never assigned again. */
    fun windowFlagSetProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val used = Regex("\\bFLAG_[A-Z0-9_]+\\b").findAll(code).map { it.value }.distinct().toList()
        return windowFlags.filterNot { it in used }.map { "$it is not used" } +
            used.filterNot { it in windowFlags }.map { "$it is used, and the flags are exactly $windowFlags" } +
            listOfNotNull("the flags are read or assigned after the window is built".takeIf { Regex("\\.flags\\b").containsMatchIn(code) })
    }

    private val viewColourSources = listOf(
        "the Color class" to Regex("\\bColor\\."),
        "TokenColor" to Regex("\\bTokenColor\\b"),
        "a palette" to Regex("(?i)palette"),
        "TruckingTokens" to Regex("\\bTruckingTokens\\b"),
        "TileColors" to Regex("\\b[tT]ileColors\\b"),
        "an argb value" to Regex("\\.argb\\b"),
        "a hex literal" to Regex("\\b0[xX][0-9A-Fa-f]"),
        "a colour resource" to Regex("\\bgetColor\\s*\\(|\\bColorStateList\\b|\\bR\\.color\\b"),
        "a shader, colour filter or tint list" to Regex("\\b(?:setShader|shader|setColorFilter|colorFilter|setBackgroundTintList|setColors)\\b"),
        "a local named color" to Regex("\\b(?:val|var)\\s+color\\b"),
        "a colour made from numbers" to Regex("\\b(?:setARGB|drawARGB|drawRGB)\\b"),
        "a ColorDrawable" to Regex("\\bColorDrawable\\b"),
        "a background resource" to Regex("\\bsetBackgroundResource\\b"),
    )
    private const val LOOK = "(?:face\\.)?look\\.\\w+"
    private val colourAssignment = Regex("\\.color\\s*=\\s*([^\\n]*)")
    private val colourAssignmentOk = Regex("color|$LOOK|if\\s*\\([^)\\n]*\\)\\s*$LOOK\\s+else\\s+$LOOK|when\\s*\\([^)\\n]*\\)\\s*\\{|ringDrawColor\\(color, pulseAlpha\\)")
    private val lookOnly = Regex(LOOK)
    private val colourFunctions = listOf("drawRing", "drawCancel", "drawSend")
    private val colourCall = Regex("\\b(?:${colourFunctions.joinToString("|")})\\s*\\(")
    private val colourSetter = Regex("\\b(set\\w*Colou?r\\w*|set\\w*Tint\\w*|draw(?:Colou?r|ARGB|RGB)|setARGB|setShadowLayer)\\s*\\(([^)\\n]*)\\)")
    private val bareColourAssignment = Regex("(?<![\\w.])color\\s*=(?!=)\\s*([^\\n]*)")
    private val colourParameter = Regex("\\bfun\\s+(\\w+)\\s*\\([^)]*\\bcolor\\s*:\\s*Int")
    private val strokeCall = Regex("\\bstrokeControl\\s*\\(([^)\\n]*)\\)")
    private val colourCallOk = Regex(",\\s*$LOOK\\s*\\)\\s*\\z")

    /** The problems with where the tile view takes its colours from: only the face's look, never a palette, a Color constant or a number. */
    fun viewColourProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val named = viewColourSources.filter { (_, pattern) -> pattern.containsMatchIn(code) }.map { "the view names ${it.first}" }
        val assignments = colourAssignment.findAll(code).toList()
        val branches = assignments.flatMap { match ->
            val value = match.groupValues[1].trim()
            if (!value.startsWith("when")) return@flatMap emptyList<String>()
            val open = match.groups[1]!!.range.first + match.groupValues[1].indexOf('{')
            val close = SourceText.closeOf(code, open)
            if (close < 0) return@flatMap listOf("a when block for a colour never closes")
            Regex("->\\s*([^\\n]+)").findAll(code.substring(open, close)).map { it.groupValues[1].trim() }
                .filterNot { lookOnly.matches(it) }.map { "a colour branch takes '$it', not a value of the face's look" }.toList()
        }
        val bare = bareColourAssignment.findAll(code).filterNot { Regex("\\b(?:val|var)\\s*\\z").containsMatchIn(code.substring(0, it.range.first)) }.toList()
        val badAssignments = (assignments + bare).map { it.groupValues[1].trim() }.filterNot { colourAssignmentOk.matches(it) }
            .map { "a colour is set from '$it', not from the face's look" }
        val calls = code.lines().filter { colourCall.containsMatchIn(it) && !it.contains("fun ") }
        val badCalls = calls.filterNot { colourCallOk.containsMatchIn(it.trim()) }.map { "a draw call passes a colour that is not a value of the face's look: ${it.trim()}" }
        val badSetters = colourSetter.findAll(code).map { it.groupValues[1] to it.groupValues[2].split(',').last().trim() }
            .filterNot { (_, value) -> lookOnly.matches(value) }.map { (name, value) -> "the colour setter $name is given '$value', not a value of the face's look" }.toList()
        val unchecked = colourParameter.findAll(code).map { it.groupValues[1] }.filterNot { it in colourFunctions || it == "strokeControl" }
            .map { "$it takes a colour but its calls are not checked, so a colour could reach the canvas through it" }.toList()
        val badStrokes = strokeCall.findAll(code).filterNot { code.substring(0, it.range.first).trimEnd().endsWith("fun") }
            .filter { it.groupValues[1].trim() != "color" }.map { "strokeControl is given '${it.groupValues[1].trim()}', not the colour parameter" }.toList()
        return listOfNotNull(
            "no colour assignment was found".takeIf { assignments.isEmpty() },
            "no draw call with a colour was found".takeIf { calls.isEmpty() },
        ) + named + badAssignments + branches + badCalls + badSetters + unchecked + badStrokes
    }

    private val timerWords = listOf(
        "Handler", "Looper", "Runnable", "Timer", "TimerTask", "post", "postDelayed", "postOnAnimation", "postInvalidateDelayed", "postInvalidateOnAnimation",
        "animate", "Animator", "ValueAnimator", "ObjectAnimator", "Choreographer", "Log", "println",
    )

    /** The problems with timers, animation and logging in the tile view: it uses none of them. */
    fun viewQuietProblems(source: String): List<String> {
        val code = SourceText.code(source)
        return timerWords.filter { Regex("\\b$it\\b").containsMatchIn(code) }.map { "$it is used" }
    }

    private val descriptionAssignment = Regex("\\bcontentDescription\\s*=\\s*([^\\n]*)")
    private val descriptionSetter = Regex("\\bsetContentDescription\\s*\\(([^)\\n]*)\\)")
    private val description = Regex("\\bcontentDescription\\s*=\\s*face\\.description[ \\t]*(?:\\n|\\z)|\\bsetContentDescription\\s*\\(\\s*face\\.description\\s*\\)")

    /** The problems with the content description: every setting of it takes the face's description, and `applyFace` sets it. */
    fun descriptionProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val values = (descriptionAssignment.findAll(code) + descriptionSetter.findAll(code)).map { it.groupValues[1].trim() }.toList()
        val body = blockBodyOf(code, "applyFace") ?: return listOf("no applyFace function with a block body was found")
        return listOfNotNull(
            "applyFace does not set the content description from face.description".takeUnless { description.containsMatchIn(body) },
        ) + values.filter { it != "face.description" }.map { "the content description is set from '$it', not from face.description" }
    }
}
