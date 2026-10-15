package dev.breaker.dictation.overlay

/**
 * Source-text rules for the drawing of the tile: no call paints the whole window, the art and every call that
 * passes a colour take their colours from a face colour or the look, the buttons and the notice text are drawn
 * with a halo pass, the grille slots are lit and coloured as the face says, the faces of the mic take their look
 * field, and each picture is drawn into the microphone cell.
 *
 * Each rule is a pure function from source text to a list of problems. Comments are blanked and string literals
 * are kept, as in [SourceText.code]: a rule word in a comment is not a use of it, and a rule word in a string is.
 * A construct that cannot be found is a problem, never a pass.
 */
internal object ViewDrawingRules {

    private val wholeWindowPaint = Regex(
        """\b(drawRect|drawRoundRect|drawColor|drawPaint|drawARGB|drawRGB|drawOval|drawArc|drawBitmap|drawPicture)\s*\(""",
    )

    /** The calls in [source] that paint a box as large as the window, by name. */
    fun fillProblems(source: String): List<String> =
        wholeWindowPaint.findAll(SourceText.code(source)).map { "the window is painted by ${it.groupValues[1]}" }.toList()

    /** Words that bring a colour, a shader or an animation into the art from outside the face. */
    private val artWords = listOf(
        "a hex literal" to Regex("""\b0[xX][0-9A-Fa-f]"""),
        "an argb value" to Regex("""\.argb\b"""),
        "a Color constant" to Regex("""\bColor\."""),
        "a local named color" to Regex("""\b(?:val|var)\s+color\b"""),
        "a shader or colour filter" to Regex("""\b(?:setShader|shader|setColorFilter|colorFilter|setBackgroundTintList|setColors|setTint|setTintList)\b"""),
        "a colour made from numbers" to Regex("""\b(?:setARGB|setColor|drawARGB|drawRGB)\b"""),
        "a paint alpha" to Regex("""\bsetAlpha\s*\(|\.alpha\b"""),
        "an animator" to Regex("""\b(?:Animator|ValueAnimator|ObjectAnimator|animate)\b"""),
    )

    /** The values a paint colour may be set from in the art: the bare colour parameter, a look field, or a colour function of the art. */
    private val artAssignment = Regex(
        """color|(?:face\.)?look\.\w+|ringDrawColor\(color, pulseAlpha\)|glowDrawColor\(color, pulseAlpha, glowAlpha\(layer\)\)|faceColor\([^()]*\)|slotColor\([^()]*\)""",
    )

    /** The values a colour argument may take at a call: a look field or one of the colour functions. */
    private val callColour = Regex(
        """(?:face\.)?look\.\w+|faceColor\([^()]*\)|slotColor\([^()]*\)|ringDrawColor\(color, pulseAlpha\)|glowDrawColor\(color, pulseAlpha, glowAlpha\(layer\)\)""",
    )

    /** Each helper that takes a colour, with the position of that colour among its arguments (the first is 0). */
    private val colourArgumentAt = mapOf("drawGlow" to 3, "fillShape" to 3, "drawBusy" to 2)

    /**
     * The colour sources of the art in [source]: no word of [artWords], every paint colour from [artAssignment], and
     * every colour argument of a helper checked at its call (see [helperCallProblems]).
     */
    fun artColourProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val assignments = Regex("""\.color\s*=\s*([^\n]*)""").findAll(code).map { it.groupValues[1].trim() }.toList()
        return listOfNotNull("the art has no colour assignment".takeIf { assignments.isEmpty() }) +
            artWords.filter { it.second.containsMatchIn(code) }.map { "the art names ${it.first}" } +
            assignments.filterNot { artAssignment.matches(it) }.map { "a colour is set from '$it', not from a face colour" } +
            colourParameterProblems(code) + callProblems(code)
    }

    /** Every function of [source] that takes a colour must be one whose calls [helperCallProblems] checks. */
    fun colourParameterProblems(source: String): List<String> {
        val code = SourceText.code(source)
        return Regex("""\bfun\s+(\w+)\s*\([^)]*\bcolor\s*:\s*Int""").findAll(code).map { it.groupValues[1] }
            .filterNot { colourArgumentAt.containsKey(it) }
            .map { "$it takes a colour but no rule checks its calls" }
            .toList()
    }

    /** Every call of drawGlow, fillShape or drawBusy in [source] must pass a look field or a colour function in its colour position. */
    fun helperCallProblems(source: String): List<String> = callProblems(SourceText.code(source))

    private fun callProblems(code: String): List<String> = colourArgumentAt.entries.flatMap { entry ->
        callsOf(code, entry.key).mapNotNull { call ->
            val argument = argsOf(call).getOrNull(entry.value)
            "a call of ${entry.key} does not pass a face colour as argument ${entry.value}: ${oneLine(call)}"
                .takeUnless { argument != null && callColour.matches(argument) }
        }
    }

    private val haloOn = Regex("""2\s*\*\s*ArtMetrics\.HALO_DP\s*\*\s*density""")
    private val haloSet = Regex("""\bhaloExtraPx\s*=\s*([^\n]+)""")

    /**
     * The buttons of onDraw in [source]: each of drawCancel and drawSend is called twice, first with look.background
     * as the halo and then with look.control, each into its own cell; the halo is set above zero before the first
     * pair and set back to 0f between the two pairs.
     */
    fun buttonProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "onDraw")
            ?: return listOf("TileView has no onDraw function with a block body")
        val cancel = callsAt(body, "drawCancel")
        val send = callsAt(body, "drawSend")
        val halo = haloSet.findAll(body).map { it.range.first to it.groupValues[1].trim() }.toList()
        val problems = ArrayList<String>()
        listOf("drawCancel" to cancel, "drawSend" to send).forEach { (name, calls) ->
            val cell = if (name == "drawCancel") "TileLayout.cancelCell(s)" else "TileLayout.sendCell(s)"
            if (calls.size != 2) {
                problems.add("$name is not called exactly twice in onDraw")
            } else {
                if (argsOf(calls[0].second).getOrNull(2) != "look.background") problems.add("the first call of $name is not drawn with look.background")
                if (argsOf(calls[1].second).getOrNull(2) != "look.control") problems.add("the last call of $name is not drawn with look.control")
            }
            calls.filter { argsOf(it.second).getOrNull(1) != cell }.forEach { problems.add("$name is not drawn into $cell") }
        }
        if (cancel.size == 2 && send.size == 2) {
            val firstPair = minOf(cancel[0].first, send[0].first)
            val firstPairEnd = maxOf(cancel[0].first, send[0].first)
            val secondPair = minOf(cancel[1].first, send[1].first)
            val before = halo.lastOrNull { it.first < firstPair }
            if (before == null || !haloOn.matches(before.second)) problems.add("the halo is not set above zero before the first pair of buttons")
            if (halo.none { it.first > firstPairEnd && it.first < secondPair && it.second == "0f" }) {
                problems.add("the halo is not set back to 0f between the two pairs of buttons")
            }
        }
        return problems
    }

    private val noticeHalo = Regex(
        """textPaint\.style\s*=\s*Paint\.Style\.STROKE\s+textPaint\.strokeWidth\s*=\s*[^\n]+\s+textPaint\.color\s*=\s*face\.look\.background\s+layout\.draw\(canvas\)\s+textPaint\.style\s*=\s*Paint\.Style\.FILL\s+textPaint\.color\s*=\s*face\.look\.control\s+layout\.draw\(canvas\)""",
    )
    private val noticeObtain = Regex("""StaticLayout\.Builder\.obtain\(\s*text\s*,\s*0\s*,\s*text\.length\s*,\s*textPaint\s*,\s*textWidth\s*\)""")

    /** The notice of drawNotice in [source]: a STROKE pass in look.background, then a FILL pass in look.control, on one layout. */
    fun noticeHaloProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "drawNotice")
            ?: return listOf("TileView has no drawNotice function with a block body")
        return listOfNotNull(
            "drawNotice does not draw the text as a STROKE halo in look.background and then as a FILL in look.control".takeUnless { noticeHalo.containsMatchIn(body) },
            "drawNotice does not build its layout with the five-argument obtain".takeUnless { noticeObtain.containsMatchIn(body) },
        )
    }

    private val slotLitDecl = Regex("""\bval\s+(\w+)\s*=\s*slotLit\(\s*face\.state\s*,\s*(\w+)\s*,\s*face\.litSegments\s*\)""")
    private val slotLoop = Regex("""for\s*\(\s*\(\s*(\w+)\s*,\s*\w+\s*\)\s+in\s+TileArt\.slots\.withIndex\(\)\s*\)""")

    /**
     * The grille slots of drawArt in [source]: the loop walks TileArt.slots with an index, each slot is lit by slotLit
     * with that index and the face's lit count, and its body and its strip are coloured by slotColor with that lit flag.
     */
    fun slotProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "drawArt")
            ?: return listOf("TileArtDraw has no drawArt function with a block body")
        val loop = slotLoop.find(body)?.groupValues?.get(1)
        val lit = slotLitDecl.find(body)
        val litName = lit?.groupValues?.get(1)
        return listOfNotNull(
            "drawArt does not walk TileArt.slots with an index".takeIf { loop == null },
            "drawArt does not light each slot with slotLit(face.state, index, face.litSegments)".takeUnless { lit != null && lit.groupValues[2] == loop },
            "drawArt does not colour the slot body with slotColor(false, lit, look)".takeUnless {
                litName != null && Regex("""fillShape\(\s*canvas\s*,\s*paint\s*,\s*slot\.body\s*,\s*slotColor\(\s*false\s*,\s*$litName\s*,\s*look\s*\)\s*\)""").containsMatchIn(body)
            },
            "drawArt does not colour the slot strip with slotColor(true, lit, look)".takeUnless {
                litName != null && Regex("""fillShape\(\s*canvas\s*,\s*paint\s*,\s*slot\.top\s*,\s*slotColor\(\s*true\s*,\s*$litName\s*,\s*look\s*\)\s*\)""").containsMatchIn(body)
            },
        )
    }

    /**
     * One arm of faceColor: the colour it takes, [base] (bodyColor(look) or a look field) and, when the arm shades
     * that base, the ArtFactors name it shades by ([factor]); null when the arm gives the base unshaded.
     */
    private data class FaceSpec(val base: String, val factor: String?) {
        fun describe(): String = if (factor == null) base else "shade($base, ArtFactors.$factor)"
    }

    /**
     * The colour each part of the mic takes in faceColor. The body shapes and the well take bodyColor(look); the
     * shaded body faces take it shaded by their own factor name; the frame, the ledge face and the ledge ends take
     * the glyph outline, shaded by their own factor name; the slots and the letters take a look field, unshaded
     * except the slot strip, which is shaded by SLOT_TOP.
     */
    private val faceSpec = mapOf(
        "TRIGGER_BODY" to FaceSpec("bodyColor(look)", null), "HEAD_BODY" to FaceSpec("bodyColor(look)", null),
        "CORD_BODY" to FaceSpec("bodyColor(look)", null), "WELL" to FaceSpec("bodyColor(look)", null),
        "TRIGGER_FACE" to FaceSpec("bodyColor(look)", "TRIGGER_FACE"), "TRIGGER_STRIPE" to FaceSpec("bodyColor(look)", "TRIGGER_STRIPE"),
        "LOWER_LEFT" to FaceSpec("bodyColor(look)", "LOWER_LEFT"), "LOWER_RIGHT" to FaceSpec("bodyColor(look)", "LOWER_RIGHT"),
        "LEDGE_EDGE" to FaceSpec("bodyColor(look)", "LEDGE_EDGE"),
        "FRAME_TOP" to FaceSpec("look.glyphOutline", "FRAME_TOP"), "FRAME_LEFT" to FaceSpec("look.glyphOutline", "FRAME_LEFT"),
        "FRAME_BOTTOM" to FaceSpec("look.glyphOutline", "FRAME_BOTTOM"), "FRAME_RIGHT" to FaceSpec("look.glyphOutline", "FRAME_RIGHT"),
        "LEDGE_FACE" to FaceSpec("look.glyphOutline", "LEDGE_FACE"),
        "LEDGE_END_LEFT" to FaceSpec("look.glyphOutline", "LEDGE_END"), "LEDGE_END_RIGHT" to FaceSpec("look.glyphOutline", "LEDGE_END"),
        "SLOT" to FaceSpec("look.litSegment", null), "SLOT_TOP" to FaceSpec("look.litSegment", "SLOT_TOP"),
        "LETTERS" to FaceSpec("look.glyph", null),
    )
    private val faceArm = Regex("""(?m)^\s*((?:ArtPart\.\w+,?\s*)+)->\s*(\S.*?)\s*$""")
    private val faceExpression = Regex("""^(?:shade\((look\.\w+|bodyColor\(look\)), ArtFactors\.(\w+)\)|(look\.\w+|bodyColor\(look\)))$""")
    private val partName = Regex("""ArtPart\.(\w+)""")

    /**
     * The arms of faceColor in [source]: each part of the mic is drawn from the colour its arm names, with the same base
     * and, for a shaded face, the shade factor of its own name. An arm the rule cannot read gives no spec, so it is reported.
     */
    fun faceRoleProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val arms = faceArm.findAll(code).toList().flatMap { arm ->
            val match = faceExpression.find(arm.groupValues[2])
            val spec = match?.let {
                if (it.groupValues[1].isNotEmpty()) FaceSpec(it.groupValues[1], it.groupValues[2]) else FaceSpec(it.groupValues[3], null)
            }
            partName.findAll(arm.groupValues[1]).map { it.groupValues[1] to spec }.toList()
        }
        return faceSpec.entries.flatMap { entry ->
            val got = arms.filter { it.first == entry.key }.map { it.second }
            listOfNotNull("the face ${entry.key} is not drawn from ${entry.value.describe()}".takeUnless { got == listOf(entry.value) })
        }
    }

    /** Each picture of onDraw in [source] is drawn into the microphone cell, which is cut once as TileLayout.micCell(face.shape, s). */
    fun artCellProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "onDraw")
            ?: return listOf("TileView has no onDraw function with a block body")
        val mic = Regex("""\bval\s+(\w+)\s*=\s*TileLayout\.micCell\(\s*face\.shape\s*,\s*s\s*\)""").find(body)?.groupValues?.get(1)
        return listOfNotNull("onDraw does not cut the microphone cell with TileLayout.micCell(face.shape, s)".takeIf { mic == null }) +
            listOf("drawGlow", "drawArt").flatMap { name ->
                val calls = callsOf(body, name)
                if (calls.size != 1) {
                    listOf("$name is not called exactly once in onDraw")
                } else {
                    listOfNotNull("$name is not drawn into the microphone cell".takeUnless { mic != null && argsOf(calls[0]).getOrNull(1) == mic })
                }
            }
    }

    /** The calls of [name] in [code], each from its name to its closing bracket; the declarations of [name] are left out. */
    fun callsOf(code: String, name: String): List<String> = callsAt(code, name).map { it.second }

    /** The calls of [name] in [code] with their start offsets, the declarations of [name] left out. */
    private fun callsAt(code: String, name: String): List<Pair<Int, String>> =
        Regex("""\b$name\s*\(""").findAll(code)
            .filterNot { code.substring(0, it.range.first).trimEnd().endsWith("fun") }
            .map { it.range.first to callText(code, it.range) }
            .toList()

    /** The text of one call, from its name to the bracket that closes the one it opens. */
    private fun callText(code: String, range: IntRange): String {
        var depth = 0
        var end = range.last
        while (end < code.length) {
            if (code[end] == '(') depth++
            if (code[end] == ')') {
                depth--
                if (depth == 0) break
            }
            end++
        }
        return code.substring(range.first, minOf(end + 1, code.length))
    }

    /** The arguments of one call text, split at the commas of its own brackets, each trimmed with its spaces made single. */
    fun argsOf(call: String): List<String> {
        val open = call.indexOf('(')
        if (open < 0) return emptyList()
        val args = ArrayList<String>()
        var depth = 0
        var start = open + 1
        var at = open
        while (at < call.length) {
            val c = call[at]
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth == 0) {
                    args.add(call.substring(start, at))
                    break
                }
            }
            if (c == ',' && depth == 1) {
                args.add(call.substring(start, at))
                start = at + 1
            }
            at++
        }
        return args.map { oneLine(it.trim()) }
    }

    private fun oneLine(text: String): String = text.replace(Regex("""\s+"""), " ")
}
