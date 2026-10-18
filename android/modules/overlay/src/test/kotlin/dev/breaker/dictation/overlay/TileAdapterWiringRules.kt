package dev.breaker.dictation.overlay

/**
 * Source-text rules for the places where the two Android adapters hand a value on: which window
 * parameter gets which number, what the window keeps after a good add, which face a new window starts
 * with, what a view redraws and answers to a touch. A wrong value at any of these still compiles and
 * the adapters never run on a plain JVM, so the text is the only place a swap can be seen.
 *
 * Each rule is a pure function from source text to a list of problems, with comments blanked and
 * string literals kept. A construct that cannot be found is reported as a problem, never skipped.
 */
internal object TileAdapterWiringRules {

    private val whitespace = Regex("""\s+""")

    private fun bodyOf(source: String, name: String): String? = AdapterRules.blockBodyOf(SourceText.code(source), name)

    private fun missing(name: String) = listOf("no $name function with a block body was found")

    private fun statements(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The top-level arguments of the call whose `(` is at [open] in [text], trimmed and with white space squeezed; null when it never closes. */
    private fun argumentsOf(text: String, open: Int): List<String>? {
        val parts = ArrayList<String>()
        var depth = 0
        var start = open + 1
        for (at in open until text.length) {
            val c = text[at]
            if (c == '(' || c == '[' || c == '{') depth++
            if (c == ')' || c == ']' || c == '}') {
                depth--
                if (depth == 0) {
                    parts.add(text.substring(start, at))
                    return parts.map { it.trim().replace(whitespace, " ") }.filter { it.isNotEmpty() }
                }
            }
            if (c == ',' && depth == 1) {
                parts.add(text.substring(start, at))
                start = at + 1
            }
        }
        return null
    }

    // ---- WindowManagerTileWindow ----

    private val frameSignature = Regex("""\bfun\s+setFrame\s*\(([^)]*)\)""")
    private val paramAssignment = Regex("""\bparams\.(x|y|width|height)\s*=(?!=)\s*([^\n]*)""")
    private val frameNames = listOf("x", "y", "width", "height")

    /** `setFrame` takes x, y, width, height in that order and copies each into the window parameter of the same name. */
    fun frameCopyProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val body = AdapterRules.blockBodyOf(code, "setFrame") ?: return missing("setFrame")
        val signature = frameSignature.find(code)?.groupValues?.get(1)?.trim()?.replace(whitespace, " ")
        val assigned = paramAssignment.findAll(body).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        val copies = frameNames.mapNotNull { name ->
            val values = assigned.filter { it.first == name }.map { it.second }
            when {
                values.isEmpty() -> "setFrame does not copy $name into params.$name"
                values != listOf(name) -> "setFrame copies '${values.joinToString("', '")}' into params.$name, not $name"
                else -> null
            }
        }
        return listOfNotNull(
            "setFrame does not take x: Int, y: Int, width: Int, height: Int in that order".takeIf { signature != "x: Int, y: Int, width: Int, height: Int" },
        ) + copies
    }

    private val moveToCall = Regex("""\bsetFrame\s*\(\s*x\s*,\s*y\s*,\s*params\.width\s*,\s*params\.height\s*\)""")

    /** `moveTo` moves the window with the new position and the width and height it already has, in that order. */
    fun moveToProblems(source: String): List<String> {
        val body = bodyOf(source, "moveTo") ?: return missing("moveTo")
        return listOfNotNull(
            "moveTo does not call setFrame(x, y, params.width, params.height)".takeUnless { moveToCall.containsMatchIn(body) },
        )
    }

    private val catchHead = Regex("""\bcatch\s*\([^)]*\)\s*\{""")

    /** After the guarded `addView` the window keeps the view and its parameters, and the last thing `add` does is answer ADDED. */
    fun addEndProblems(source: String): List<String> {
        val body = bodyOf(source, "add") ?: return missing("add")
        val last = catchHead.findAll(body).lastOrNull() ?: return listOf("add has no catch after the addView call")
        val close = SourceText.closeOf(body, last.range.last)
        if (close < 0) return listOf("the last catch of add never closes")
        val after = statements(body.substring(close + 1).trimEnd().removeSuffix("}"))
        return listOfNotNull(
            "add does not keep the view after the addView call (tileView = view)".takeUnless { "tileView = view" in after },
            "add does not keep the parameters after the addView call (layoutParams = params)".takeUnless { "layoutParams = params" in after },
            "add does not end with return AddOutcome.ADDED".takeUnless { after.lastOrNull() == "return AddOutcome.ADDED" },
        )
    }

    private val faceCall = Regex("""\bTileFace\s*\(""")
    private val namedArgument = Regex("""^(\w+)\s*=\s*(.*)""", RegexOption.DOT_MATCHES_ALL)
    private val firstFace = mapOf(
        "state" to "TileState.IDLE",
        "shape" to "TileShape.COLLAPSED",
        "litSegments" to "0",
        "look" to "TileStyle.look(TileState.IDLE, palette)",
        "notice" to "null",
        "description" to "null",
    )

    /** The face a new window starts with is the idle, collapsed tile with no meter, notice or description. */
    fun firstFaceProblems(source: String): List<String> {
        val body = bodyOf(source, "add") ?: return missing("add")
        val call = faceCall.find(body) ?: return listOf("add does not build a TileFace")
        val arguments = argumentsOf(body, call.range.last) ?: return listOf("the TileFace call in add never closes")
        val named = arguments.mapNotNull { argument ->
            namedArgument.find(argument)?.let { it.groupValues[1] to it.groupValues[2].trim().replace("( ", "(").replace(" )", ")") }
        }.toMap()
        return firstFace.mapNotNull { (name, want) ->
            val got = named[name]
            "the first face has $name = '${got ?: "(none)"}', not '$want'".takeIf { got != want }
        }
    }

    private val paletteRepaint = Regex("""\.copy\s*\(\s*look\s*=\s*TileStyle\.look\s*\(\s*face\.state\s*,\s*palette\s*\)\s*\)""")
    private val heldFace = Regex("""\bval\s+face\s*=\s*view\.face\b""")

    /** A theme change repaints the face the view holds with the look of that face's own state. */
    fun paletteProblems(source: String): List<String> {
        val body = bodyOf(source, "applyPalette") ?: return missing("applyPalette")
        return listOfNotNull(
            "applyPalette does not start from the face the view holds (val face = view.face)".takeUnless { heldFace.containsMatchIn(body) },
            "applyPalette does not copy the face with TileStyle.look(face.state, palette)".takeUnless { paletteRepaint.containsMatchIn(body) },
        )
    }

    private val tryOpen = Regex("""\btry\s*\{""")
    private val catchAfter = Regex("""^\s*catch\s*\(([^)]*)\)\s*\{""")
    private val throwWord = Regex("""\bthrow\b""")
    private val illegalArgument = Regex("""\bIllegalArgumentException\b""")

    /** `removeQuietly` calls removeView in a try whose catch swallows IllegalArgumentException, the view the system already removed. */
    fun removeProblems(source: String): List<String> {
        val body = bodyOf(source, "removeQuietly") ?: return missing("removeQuietly")
        val remove = Regex("""\bwindowManager\.removeView\s*\(""").find(body) ?: return listOf("removeQuietly does not call windowManager.removeView(")
        val close = tryOpen.findAll(body).map { it.range.last to SourceText.closeOf(body, it.range.last) }
            .firstOrNull { (open, end) -> open < remove.range.first && remove.range.first < end }?.second
            ?: return listOf("removeQuietly calls removeView outside a try")
        val head = catchAfter.find(body.substring(close + 1)) ?: return listOf("the try that holds removeView has no catch")
        val bodyOpen = close + 1 + head.range.last
        val bodyClose = SourceText.closeOf(body, bodyOpen)
        val swallowed = bodyClose >= 0 && !throwWord.containsMatchIn(body.substring(bodyOpen + 1, bodyClose))
        return listOfNotNull(
            "removeQuietly does not catch IllegalArgumentException".takeUnless { illegalArgument.containsMatchIn(head.groupValues[1]) },
            "removeQuietly does not swallow what its catch catches".takeUnless { swallowed },
        )
    }

    // ---- TileView ----

    private val storeFace = Regex("""\bthis\.face\s*=\s*face(?![\w.])""")
    private val redrawNow = Regex("""(?<![\w.])(?:this\.)?invalidate\s*\(\s*\)""")
    private val postedRedraw = Regex("""\bpostInvalidate\w*""")

    /** `applyFace` stores the face, then redraws at once; no redraw is posted to the message queue. */
    fun applyFaceStoreProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val body = AdapterRules.blockBodyOf(code, "applyFace") ?: return missing("applyFace")
        val store = storeFace.find(body)
        val draw = redrawNow.find(body)
        return listOfNotNull(
            "applyFace does not store the face (this.face = face)".takeIf { store == null },
            "applyFace does not redraw at once (invalidate())".takeIf { draw == null },
            "applyFace redraws before it stores the face".takeIf { store != null && draw != null && draw.range.first < store.range.first },
            "the view posts a redraw (postInvalidate) instead of drawing at once".takeIf { postedRedraw.containsMatchIn(code) },
        )
    }

    private val noticeText = Regex("""\bval\s+text\s*=\s*face\.notice\s*\?:\s*return\b""")
    private val descriptionWord = Regex("""\bdescription\b""")

    /** The notice area draws the app's notice and never the description. */
    fun noticeSourceProblems(source: String): List<String> {
        val body = bodyOf(source, "drawNotice") ?: return missing("drawNotice")
        return listOfNotNull(
            "drawNotice does not take its text from face.notice (val text = face.notice ?: return)".takeUnless { noticeText.containsMatchIn(body) },
            "drawNotice reads the description".takeIf { descriptionWord.containsMatchIn(body) },
        )
    }

    private val sinkCall = Regex("""\bsink\.onTouch(Down|Move|Up)\s*\(""")
    private val rawPosition = "event.getRawX(index), event.getRawY(index)"
    private val ownPointer = Regex("""\bval\s+index\s*=\s*event\.findPointerIndex\s*\(\s*activePointerId\s*\)""")
    private val answerFalse = Regex("""\breturn\s+false\b""")

    private fun branchOf(body: String, action: String): String? {
        val head = Regex("""\bMotionEvent\.$action\s*->\s*\{""").find(body) ?: return null
        val close = SourceText.closeOf(body, head.range.last)
        return if (close < 0) null else body.substring(head.range.last, close + 1)
    }

    /** The view hands the sink screen positions, follows the pointer that owns the gesture, and answers true to every touch event. */
    fun touchProblems(source: String): List<String> {
        val body = bodyOf(source, "onTouchEvent") ?: return missing("onTouchEvent")
        val calls = sinkCall.findAll(body).toList()
        val positions = calls.mapNotNull { call ->
            val kind = call.groupValues[1]
            val given = argumentsOf(body, call.range.last)?.joinToString(", ")
            "sink.onTouch$kind is given '${given ?: "(no end)"}', not the screen position ($rawPosition)".takeIf { given != rawPosition }
        }
        val absent = listOf("Down", "Move", "Up").filter { kind -> calls.none { it.groupValues[1] == kind } }
            .map { "the view never calls sink.onTouch$it" }
        val pointers = listOf("ACTION_MOVE", "ACTION_UP").mapNotNull { action ->
            val branch = branchOf(body, action)
            when {
                branch == null -> "onTouchEvent has no MotionEvent.$action branch"
                !ownPointer.containsMatchIn(branch) -> "the $action branch does not read the pointer that owns the gesture (val index = event.findPointerIndex(activePointerId))"
                else -> null
            }
        }
        val lastLine = statements(body.trimEnd().removeSuffix("}")).lastOrNull()
        return positions + absent + pointers + listOfNotNull(
            "onTouchEvent does not end with return true".takeIf { lastLine != "return true" },
            "onTouchEvent answers false".takeIf { answerFalse.containsMatchIn(body) },
        )
    }
}
