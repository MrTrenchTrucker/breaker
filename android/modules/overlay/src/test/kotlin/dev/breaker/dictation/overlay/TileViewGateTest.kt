package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile view and the window adapter cannot run on a plain JVM, so the rules for what they
 * draw and how they change the window are checked on their source text, like the rules in
 * [AdapterGateTest].
 *
 * Every test runs its rule first on short made-up samples (a wrong one must be reported with the
 * right words, a correct one must not) and only then on the real file. A file or construct that
 * cannot be found is a violation, never a pass. Comments are blanked and string literals are kept.
 */
class TileViewGateTest {

    private val window = "WindowManagerTileWindow.kt"
    private val view = "TileView.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** The violations of [check] on one file of [files], or one violation when the file is not there. */
    private fun inFile(files: Map<String, String>, name: String, check: (String) -> List<String>): List<String> =
        files[name]?.let(check) ?: listOf("$name is missing from the main sources")

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    // ---- The window changes its frame through updateViewLayout ----

    private val allSets = "params.x = x\n params.y = y\n params.width = width\n params.height = height\n"

    private fun frameSample(
        sets: String = allSets,
        body: String = "try { windowManager.updateViewLayout(view, params) } catch (e: IllegalArgumentException) { }",
    ) = "override fun setFrame(x: Int, y: Int, width: Int, height: Int) {\n val view = tileView ?: return\n $sets $body\n}\n"

    /** A failure here means the wide window cannot be placed or sized, or a window the system already removed crashes the move. */
    @Test
    fun `setFrame moves and resizes the window and swallows a view the system already removed`() {
        assertFires("no function", AdapterRules.setFrameProblems("val x = 1\n"), "no setFrame function")
        assertFires("a function only in a comment", AdapterRules.setFrameProblems("// fun setFrame(x: Int) { }\n"), "no setFrame function")
        assertFires("an expression body", AdapterRules.setFrameProblems("override fun setFrame(a: Int, b: Int, c: Int, d: Int) = move(a)\n"), "block body")
        listOf("x", "y", "width", "height").forEach { name ->
            assertFires("params.$name never set", AdapterRules.setFrameProblems(frameSample(sets = edit(allSets, "params.$name = $name\n", ""))), "does not set params.$name")
        }
        assertFires("no update call", AdapterRules.setFrameProblems(frameSample(body = "view.invalidate()")), "does not call updateViewLayout")
        assertFires("the call in another function", AdapterRules.setFrameProblems(
            "fun other() { try { wm.updateViewLayout(v, p) } catch (e: IllegalArgumentException) { } }\n" + frameSample(body = "view.invalidate()"),
        ), "does not call updateViewLayout")
        assertFires("no try", AdapterRules.setFrameProblems(frameSample(body = "windowManager.updateViewLayout(view, params)")), "without swallowing")
        assertFires("another exception caught", AdapterRules.setFrameProblems(frameSample(body = "try { windowManager.updateViewLayout(view, params) } catch (e: IllegalStateException) { }")), "without swallowing")
        assertFires("the exception thrown again", AdapterRules.setFrameProblems(frameSample(body = "try { windowManager.updateViewLayout(view, params) } catch (e: IllegalArgumentException) { throw e }")), "without swallowing")
        assertFires("the call after the try", AdapterRules.setFrameProblems(frameSample(body = "try { x() } catch (e: IllegalArgumentException) { }\n windowManager.updateViewLayout(view, params)")), "without swallowing")
        assertQuiet("a complete setFrame", AdapterRules.setFrameProblems(frameSample()))
        assertQuiet("a complete setFrame with a comment in the catch and a longer function name before it", AdapterRules.setFrameProblems(
            "fun setFrameLater(a: Int) = a\n" + frameSample(body = "try { windowManager.updateViewLayout(view, params) } catch (e: IllegalArgumentException) {\n // gone\n }"),
        ))

        assertEquals("overlay: $window must set the frame and swallow the removed view", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, AdapterRules::setFrameProblems))
    }

    private fun faceSample(body: String = "tileView?.applyFace(face)") = "override fun applyFace(face: TileFace) {\n $body\n}\n"

    /** A failure here means a face pushed to the window never reaches the view that draws it. */
    @Test
    fun `the window passes a face to the view it holds`() {
        assertFires("no function", AdapterRules.applyFacePassOnProblems("val x = 1\n"), "no applyFace function")
        assertFires("an empty function", AdapterRules.applyFacePassOnProblems(faceSample(body = "")), "does not call tileView.applyFace(face)")
        assertFires("a redraw without the face", AdapterRules.applyFacePassOnProblems(faceSample(body = "tileView?.invalidate()")), "does not call")
        assertFires("another face passed on", AdapterRules.applyFacePassOnProblems(faceSample(body = "tileView?.applyFace(other)")), "does not call")
        assertFires("the call only in a comment", AdapterRules.applyFacePassOnProblems(faceSample(body = "// tileView?.applyFace(face)")), "does not call")
        assertQuiet("a safe call", AdapterRules.applyFacePassOnProblems(faceSample()))
        assertQuiet("a plain call", AdapterRules.applyFacePassOnProblems(faceSample(body = "tileView.applyFace(face)")))

        assertEquals("overlay: $window must pass the face to the view", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, AdapterRules::applyFacePassOnProblems))
    }

    // ---- The window flags stay the three of the plain tile ----

    private val threeFlags = "val f = FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN\n"

    /** A failure here means the wide window can take focus or touches that belong to the app underneath, or the flags were changed after the window was built. */
    @Test
    fun `the window flags are exactly the three of the plain tile and are never changed`() {
        listOf("FLAG_NOT_FOCUSABLE", "FLAG_NOT_TOUCH_MODAL", "FLAG_LAYOUT_IN_SCREEN").forEach { flag ->
            val without = edit(threeFlags, flag, "FLAG_ALT").replace("FLAG_ALT or ", "").replace(" or FLAG_ALT", "")
            assertFires("$flag missing", AdapterRules.windowFlagSetProblems(without), "$flag is not used")
        }
        assertFires("a fourth flag", AdapterRules.windowFlagSetProblems(threeFlags + "val g = FLAG_NOT_TOUCHABLE\n"), "FLAG_NOT_TOUCHABLE is used")
        assertFires("a near-miss name for one", AdapterRules.windowFlagSetProblems(edit(threeFlags, "FLAG_NOT_FOCUSABLE", "FLAG_NOT_FOCUSABLE_X")), "FLAG_NOT_FOCUSABLE is not used")
        assertFires("flags set after the build", AdapterRules.windowFlagSetProblems(threeFlags + "params.flags = params.flags or 8\n"), "after the window is built")
        assertFires("flags named only in a comment", AdapterRules.windowFlagSetProblems("// $threeFlags"), "FLAG_NOT_FOCUSABLE is not used")
        assertQuiet("the three flags", AdapterRules.windowFlagSetProblems(threeFlags))
        assertQuiet("the three flags twice in another order, and a fourth only in a comment", AdapterRules.windowFlagSetProblems(
            "val a = FLAG_LAYOUT_IN_SCREEN or FLAG_NOT_TOUCH_MODAL or FLAG_NOT_FOCUSABLE\n" + threeFlags + "// FLAG_NOT_TOUCHABLE\n",
        ))

        assertEquals("overlay: $window must use exactly the three flags of the plain tile", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, AdapterRules::windowFlagSetProblems))
    }

    // ---- Any failure of the add is a refusal ----

    private val bad = "catch (e: WindowManager.BadTokenException) { return AddOutcome.REFUSED }\n"
    private val security = "catch (e: SecurityException) { return AddOutcome.REFUSED }\n"
    private val runtime = "catch (e: RuntimeException) { return AddOutcome.REFUSED }\n"

    private fun addSample(catches: String, call: String = "wm.addView(v, p)") =
        "fun add(): AddOutcome {\n try { $call } $catches return AddOutcome.ADDED\n}\n"

    /** A failure here means an unexpected runtime failure of the add can crash the app instead of being reported as a refusal. */
    @Test
    fun `any runtime failure of the add is a refusal returned last`() {
        assertFires("no addView", AdapterRules.runtimeCatchProblems("val x = 1\n// wm.addView(v, p)\n"), "no addView call")
        assertFires("an unguarded call", AdapterRules.runtimeCatchProblems("fun add() { wm.addView(v, p) }\n"), "is not inside a try")
        assertFires("the two specific catches only", AdapterRules.runtimeCatchProblems(addSample(bad + security)), "last catch is not RuntimeException")
        assertFires("another exception last", AdapterRules.runtimeCatchProblems(addSample(bad + security + "catch (e: IllegalStateException) { return AddOutcome.REFUSED }\n")), "last catch is not RuntimeException")
        assertFires("a runtime catch that answers ADDED", AdapterRules.runtimeCatchProblems(addSample(bad + security + "catch (e: RuntimeException) { return AddOutcome.ADDED }\n")), "last catch is not RuntimeException")
        assertFires("a runtime catch that swallows", AdapterRules.runtimeCatchProblems(addSample(bad + security + "catch (e: RuntimeException) { }\n")), "last catch is not RuntimeException")
        assertFires("the runtime catch first", AdapterRules.runtimeCatchProblems(addSample(runtime + bad + security)), "last catch is not RuntimeException")
        assertFires("no bad-token catch", AdapterRules.runtimeCatchProblems(addSample(security + runtime)), "last catch is not RuntimeException")
        assertFires("no security catch", AdapterRules.runtimeCatchProblems(addSample(bad + runtime)), "last catch is not RuntimeException")
        assertFires("a call inside the catch body", AdapterRules.runtimeCatchProblems(
            "fun a() {\n try { x() } catch (e: RuntimeException) { wm.addView(v, p); return AddOutcome.REFUSED }\n}\n",
        ), "is not inside a try")
        assertQuiet("the three catches in order", AdapterRules.runtimeCatchProblems(addSample(bad + security + runtime)))
        assertQuiet("the three catches with a comment and a full type name", AdapterRules.runtimeCatchProblems(
            addSample(bad + security + "catch (e: java.lang.RuntimeException) {\n // anything else\n return AddOutcome.REFUSED\n }\n"),
        ))
        assertQuiet("the specific catches swapped and the call nested", AdapterRules.runtimeCatchProblems(addSample(security + bad + runtime, call = "if (ok) { wm.addView(v, p) }")))

        assertEquals("overlay: $window must end the add's catches with RuntimeException returning REFUSED", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, AdapterRules::runtimeCatchProblems))
    }

    // ---- The view takes every colour from the face's look ----

    private val colourSample = listOf(
        "internal class V {",
        "fun onDraw() {",
        "paint.color = look.background",
        "paint.color = when (rect.role) {",
        "GlyphRole.BODY -> look.glyph",
        "GlyphRole.OUTLINE -> look.glyphOutline",
        "}",
        "paint.color = if (i < face.litSegments) face.look.litSegment else face.look.unlitSegment",
        "textPaint.color = face.look.control",
        "drawRing(canvas, mic, look.ring)",
        "drawCancel(canvas, TileLayout.cancelCell(s), look.control)",
        "}",
        "private fun drawRing(canvas: Canvas, cell: TileRect, color: Int) {",
        "paint.color = color",
        "}",
        "}",
    ).joinToString("\n") + "\n"

    /** A failure here means the view paints with a colour that is not in the face it was given, so the theme and the state stop deciding the colours. */
    @Test
    fun `the tile view takes every colour from the face's look`() {
        assertFires("a Color constant", AdapterRules.viewColourProblems(edit(colourSample, "= look.background", "= Color.RED")), "names the Color class")
        assertFires("a Color constant as the assigned value", AdapterRules.viewColourProblems(edit(colourSample, "= look.background", "= android.graphics.Color.BLACK")), "a colour is set from 'android.graphics.Color.BLACK'")
        assertFires("a hex literal", AdapterRules.viewColourProblems(edit(colourSample, "= look.background", "= 0xFF1E7A46.toInt()")), "a hex literal")
        assertFires("a number", AdapterRules.viewColourProblems(edit(colourSample, "= look.background", "= 7")), "a colour is set from '7'")
        assertFires("a palette value", AdapterRules.viewColourProblems(edit(colourSample, "= look.background", "= palette.surface.argb")), "a palette")
        assertFires("an argb value", AdapterRules.viewColourProblems(edit(colourSample, "= look.background", "= tokenColor.argb")), "an argb value")
        assertFires("the old colour holder", AdapterRules.viewColourProblems(edit(colourSample, "internal class V {", "internal class V(colors: TileColors) {")), "TileColors")
        assertFires("a colour resource", AdapterRules.viewColourProblems(edit(colourSample, "= look.background", "= resources.getColor(1)")), "a colour resource")
        assertFires("a glyph branch with a number", AdapterRules.viewColourProblems(edit(colourSample, "-> look.glyph\n", "-> 5\n")), "a colour branch takes '5'")
        assertFires("a glyph branch with a constant", AdapterRules.viewColourProblems(edit(colourSample, "-> look.glyphOutline", "-> Color.BLUE")), "a colour branch takes 'Color.BLUE'")
        assertFires("a meter colour of zero", AdapterRules.viewColourProblems(edit(colourSample, "else face.look.unlitSegment", "else 0")), "a colour is set from 'if (i < face.litSegments)")
        assertFires("a text colour from outside the look", AdapterRules.viewColourProblems(edit(colourSample, "face.look.control", "textColour")), "a colour is set from 'textColour'")
        assertFires("a ring drawn in a number", AdapterRules.viewColourProblems(edit(colourSample, "drawRing(canvas, mic, look.ring)", "drawRing(canvas, mic, 123)")), "a draw call passes a colour")
        assertFires("a cross drawn in a variable", AdapterRules.viewColourProblems(edit(colourSample, "look.control)", "tint)")), "a draw call passes a colour")
        assertFires("no colour assignment", AdapterRules.viewColourProblems("class V\n"), "no colour assignment")
        assertFires("no draw call with a colour", AdapterRules.viewColourProblems(edit(edit(colourSample, "drawRing(canvas, mic, look.ring)\n", ""), "drawCancel(canvas, TileLayout.cancelCell(s), look.control)\n", "")), "no draw call with a colour")
        assertQuiet("the look everywhere, a colour parameter, and the old words in comments", AdapterRules.viewColourProblems("// Color.RED palette 0xFF1E7A46 TileColors\n/* look.glyph.argb */\n" + colourSample))

        assertEquals("overlay: $view must take every colour from the face's look", emptyList<String>(), inFile(ModuleFiles.mainTexts(), view, AdapterRules::viewColourProblems))
    }

    private fun withLine(after: String, line: String) = edit(colourSample, after, "$after\n$line")

    /** A failure here means a colour can reach the canvas through a setter, a shader or a helper that the colour rule does not follow. */
    @Test
    fun `the tile view sets no colour through a setter, a shader or an unchecked helper`() {
        val first = "paint.color = look.background"
        assertFires("setColor with a number", AdapterRules.viewColourProblems(edit(colourSample, "textPaint.color = face.look.control", "textPaint.setColor(7)")), "setColor is given '7'")
        assertFires("setBackgroundColor with a number", AdapterRules.viewColourProblems(edit(colourSample, first, "setBackgroundColor(0)")), "setBackgroundColor is given '0'")
        assertFires("drawColor with a variable", AdapterRules.viewColourProblems(edit(colourSample, first, "canvas.drawColor(tint)")), "drawColor is given 'tint'")
        assertFires("setTint with a variable", AdapterRules.viewColourProblems(edit(colourSample, first, "drawable.setTint(accent)")), "setTint is given 'accent'")
        assertFires("setTextColor with a number", AdapterRules.viewColourProblems(withLine(first, "textPaint.setTextColor(3)")), "setTextColor is given '3'")
        assertFires("a shadow with a number as its colour", AdapterRules.viewColourProblems(withLine(first, "paint.setShadowLayer(2f, 0f, 0f, 0)")), "setShadowLayer is given '0'")
        assertFires("a shadow with a variable as its colour", AdapterRules.viewColourProblems(withLine(first, "paint.setShadowLayer(radius, dx, dy, shade)")), "setShadowLayer is given 'shade'")
        assertFires("a shader", AdapterRules.viewColourProblems(withLine(first, "paint.shader = gradient")), "a shader, colour filter or tint list")
        assertFires("a colour filter", AdapterRules.viewColourProblems(withLine(first, "paint.setColorFilter(filter)")), "a shader, colour filter or tint list")
        assertFires("a helper with a colour parameter that no rule follows", AdapterRules.viewColourProblems(
            edit(colourSample, "private fun drawRing(", "private fun drawFoo(canvas: Canvas, cell: TileRect, color: Int) {\n}\nprivate fun drawRing("),
        ), "drawFoo takes a colour")
        assertFires("a local named color", AdapterRules.viewColourProblems(edit(colourSample, "paint.color = color\n", "val color = 7\npaint.color = color\n")), "a local named color")
        assertFires("strokeControl given a number", AdapterRules.viewColourProblems(withLine(first, "strokeControl(7)")), "strokeControl is given '7'")
        assertQuiet("setters given values of the look", AdapterRules.viewColourProblems(
            withLine(first, "paint.setColor(look.glyph)\ncanvas.drawColor(face.look.background)\npaint.setShadowLayer(1f, 0f, 0f, face.look.control)"),
        ))
        assertQuiet("strokeControl given the colour parameter, and its declaration", AdapterRules.viewColourProblems(
            withLine(first, "strokeControl(color)\nprivate fun strokeControl(color: Int) {\n}"),
        ))
        assertQuiet("setters, a shader and a local colour only in comments", AdapterRules.viewColourProblems(
            "// paint.setColor(7) canvas.drawColor(tint) paint.shader = g val color = 1\n" + colourSample,
        ))
    }

    // ---- The view has no timer, animation or log ----

    private val quietWords = listOf(
        "Handler", "Looper", "Runnable", "Timer", "TimerTask", "post", "postDelayed", "postOnAnimation", "postInvalidateDelayed", "postInvalidateOnAnimation",
        "animate", "Animator", "ValueAnimator", "ObjectAnimator", "Choreographer", "Log", "println",
    )

    /** A failure here means the view starts something that runs by itself (a timer, an animation, a log line), which the module must never do. */
    @Test
    fun `the tile view starts no timer or animation and writes no log`() {
        quietWords.forEach { word ->
            assertFires(word, AdapterRules.viewQuietProblems("fun f() { $word }\n"), "$word is used")
        }
        assertFires("a log call", AdapterRules.viewQuietProblems("import android.util.Log\nfun f() { Log.d(\"x\", \"y\") }\n"), "Log is used")
        assertFires("an animation start", AdapterRules.viewQuietProblems("fun f() { view.animate().alpha(1f) }\n"), "animate is used")
        assertFires("a delayed redraw", AdapterRules.viewQuietProblems("fun f() { postInvalidateDelayed(16) }\n"), "postInvalidateDelayed is used")
        assertQuiet("every word in comments", AdapterRules.viewQuietProblems("// ${quietWords.joinToString(" ")}\n/* ${quietWords.joinToString(" ")} */\nval x = 1\n"))
        assertQuiet("longer names that only start like a word", AdapterRules.viewQuietProblems("val a = Logger\nval b = postponed\nval c = animated\nval d = Handlers\nval e = isPosted\n"))

        assertEquals("overlay: $view must start no timer or animation and write no log", emptyList<String>(), inFile(ModuleFiles.mainTexts(), view, AdapterRules::viewQuietProblems))
    }

    // ---- The view's description is the face's description ----

    private fun descriptionSample(inFace: String = "contentDescription = face.description", inInit: String = "contentDescription = face.description") =
        "init {\n $inInit\n}\nfun applyFace(face: TileFace) {\n this.face = face\n $inFace\n invalidate()\n}\n"

    /** A failure here means a screen reader reads out something other than the sentence the app gave for the tile. */
    @Test
    fun `the tile view sets its content description from the face`() {
        assertFires("a fixed text", AdapterRules.descriptionProblems(descriptionSample(inFace = "contentDescription = \"Microphone\"")), "set from '\"Microphone\"'")
        assertFires("a fixed text and no face description in applyFace", AdapterRules.descriptionProblems(descriptionSample(inFace = "contentDescription = \"Microphone\"")), "applyFace does not set")
        assertFires("a null", AdapterRules.descriptionProblems(descriptionSample(inFace = "contentDescription = null")), "set from 'null'")
        assertFires("the notice instead", AdapterRules.descriptionProblems(descriptionSample(inFace = "contentDescription = face.notice")), "set from 'face.notice'")
        assertFires("a fallback text", AdapterRules.descriptionProblems(descriptionSample(inFace = "contentDescription = face.description ?: \"\"")), "set from 'face.description ?:")
        assertFires("a setter with another value", AdapterRules.descriptionProblems(descriptionSample(inFace = "setContentDescription(text)")), "set from 'text'")
        assertFires("applyFace never sets it", AdapterRules.descriptionProblems(descriptionSample(inFace = "")), "applyFace does not set")
        assertFires("a wrong value in init", AdapterRules.descriptionProblems(descriptionSample(inInit = "contentDescription = \"Mic\"")), "set from '\"Mic\"'")
        assertFires("no applyFace", AdapterRules.descriptionProblems("init { contentDescription = face.description }\n"), "no applyFace function")
        assertFires("the assignment only in a comment", AdapterRules.descriptionProblems(descriptionSample(inFace = "// contentDescription = face.description")), "applyFace does not set")
        assertQuiet("the property assignment", AdapterRules.descriptionProblems(descriptionSample()))
        assertQuiet("the setter call", AdapterRules.descriptionProblems(descriptionSample(inFace = "setContentDescription(face.description)", inInit = "setContentDescription(face.description)")))

        assertEquals("overlay: $view must set the content description from face.description", emptyList<String>(), inFile(ModuleFiles.mainTexts(), view, AdapterRules::descriptionProblems))
    }
}
