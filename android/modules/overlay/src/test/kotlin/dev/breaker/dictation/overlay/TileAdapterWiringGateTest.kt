package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window adapter and the tile view never run on a plain JVM, and a value handed to the wrong
 * place in them still compiles. These tests read their source text and pin the hand-overs: which
 * window parameter gets which number, what the window keeps after a good add, which face a new
 * window starts with, what the view redraws and what it answers to a touch.
 *
 * Every test runs its rule first on short made-up samples (a wrong one must be reported with the
 * right words, a correct one must not) and only then on the real file. A file or construct that
 * cannot be found is a violation, never a pass. Comments are blanked and string literals are kept.
 */
class TileAdapterWiringGateTest {

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

    // ---- setFrame copies each number into its own parameter; moveTo keeps the size ----

    private val frameSets = "params.x = x\n params.y = y\n params.width = width\n params.height = height\n"

    private fun frameSample(sets: String = frameSets, signature: String = "x: Int, y: Int, width: Int, height: Int") =
        "override fun setFrame($signature) {\n val view = tileView ?: return\n $sets updateViewLayout(view, params)\n}\n"

    private fun moveSample(call: String = "setFrame(x, y, params.width, params.height)") =
        "override fun moveTo(x: Int, y: Int) {\n val params = layoutParams ?: return\n $call\n}\n"

    /** A failure here means the window is placed at the wrong spot or given the wrong size, for the wide window or for every drag. */
    @Test
    fun `setFrame copies each number into its own window parameter and moveTo passes the width and height in order`() {
        assertFires("no function", TileAdapterWiringRules.frameCopyProblems("val x = 1\n"), "no setFrame function")
        assertFires("x taken from y", TileAdapterWiringRules.frameCopyProblems(frameSample(edit(frameSets, "params.x = x", "params.x = y"))), "copies 'y' into params.x, not x")
        assertFires("y taken from x", TileAdapterWiringRules.frameCopyProblems(frameSample(edit(frameSets, "params.y = y", "params.y = x"))), "copies 'x' into params.y, not y")
        assertFires("width taken from height", TileAdapterWiringRules.frameCopyProblems(frameSample(edit(frameSets, "params.width = width", "params.width = height"))), "copies 'height' into params.width")
        assertFires("height taken from width", TileAdapterWiringRules.frameCopyProblems(frameSample(edit(frameSets, "params.height = height", "params.height = width"))), "copies 'width' into params.height")
        assertFires("a number set twice", TileAdapterWiringRules.frameCopyProblems(frameSample(frameSets + " params.x = 0\n")), "copies 'x', '0' into params.x")
        assertFires("width never set", TileAdapterWiringRules.frameCopyProblems(frameSample(edit(frameSets, "params.width = width\n", ""))), "does not copy width into params.width")
        assertFires("a set only in a comment", TileAdapterWiringRules.frameCopyProblems(frameSample(edit(frameSets, "params.y = y", "// params.y = y"))), "does not copy y into params.y")
        assertFires("parameters in another order", TileAdapterWiringRules.frameCopyProblems(frameSample(signature = "y: Int, x: Int, width: Int, height: Int")), "in that order")
        assertQuiet("a complete setFrame", TileAdapterWiringRules.frameCopyProblems(frameSample()))
        assertQuiet("a complete setFrame with a comment after a set", TileAdapterWiringRules.frameCopyProblems(frameSample(edit(frameSets, "params.x = x", "params.x = x // left"))))

        assertFires("no function", TileAdapterWiringRules.moveToProblems("val x = 1\n"), "no moveTo function")
        assertFires("an expression body", TileAdapterWiringRules.moveToProblems("override fun moveTo(x: Int, y: Int) = setFrame(x, y, 1, 1)\n"), "no moveTo function")
        assertFires("width and height swapped", TileAdapterWiringRules.moveToProblems(moveSample("setFrame(x, y, params.height, params.width)")), "does not call setFrame(x, y, params.width, params.height)")
        assertFires("x and y swapped", TileAdapterWiringRules.moveToProblems(moveSample("setFrame(y, x, params.width, params.height)")), "does not call setFrame")
        assertFires("a fixed size", TileAdapterWiringRules.moveToProblems(moveSample("setFrame(x, y, 1, 1)")), "does not call setFrame")
        assertFires("the call only in a comment", TileAdapterWiringRules.moveToProblems(moveSample("// setFrame(x, y, params.width, params.height)")), "does not call setFrame")
        assertQuiet("the call", TileAdapterWiringRules.moveToProblems(moveSample()))
        assertQuiet("the call on several lines", TileAdapterWiringRules.moveToProblems(moveSample("setFrame(\n x,\n y,\n params.width,\n params.height\n )")))

        assertEquals("overlay: $window must copy each number into its own parameter", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, TileAdapterWiringRules::frameCopyProblems))
        assertEquals("overlay: $window must keep the size when it only moves", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, TileAdapterWiringRules::moveToProblems))
    }

    // ---- add keeps what it added and answers ADDED ----

    private val guarded = "try { wm.addView(v, p) } catch (e: SecurityException) { return AddOutcome.REFUSED } catch (e: RuntimeException) { return AddOutcome.REFUSED }\n"
    private val keptAll = " tileView = view\n layoutParams = params\n return AddOutcome.ADDED\n"

    private fun addSample(tail: String = keptAll, guard: String = guarded) =
        "override fun add(x: Int, y: Int, palette: TruckingPalette): AddOutcome {\n val view = TileView(c, s, f)\n $guard$tail}\n"

    /** A failure here means a window that is on screen is reported as refused, or the window forgets the view it must move and remove later. */
    @Test
    fun `add keeps the view and its parameters and answers ADDED last`() {
        assertFires("no function", TileAdapterWiringRules.addEndProblems("val x = 1\n"), "no add function")
        assertFires("no catch", TileAdapterWiringRules.addEndProblems(addSample(guard = "wm.addView(v, p)\n")), "no catch after the addView call")
        assertFires("the view not kept", TileAdapterWiringRules.addEndProblems(addSample(edit(keptAll, " tileView = view\n", ""))), "does not keep the view")
        assertFires("the view dropped", TileAdapterWiringRules.addEndProblems(addSample(edit(keptAll, "tileView = view", "tileView = null"))), "does not keep the view")
        assertFires("the parameters not kept", TileAdapterWiringRules.addEndProblems(addSample(edit(keptAll, " layoutParams = params\n", ""))), "does not keep the parameters")
        assertFires("the keeping only in a comment", TileAdapterWiringRules.addEndProblems(addSample(edit(keptAll, " tileView = view", " // tileView = view"))), "does not keep the view")
        assertFires("a refusal after a good add", TileAdapterWiringRules.addEndProblems(addSample(edit(keptAll, "AddOutcome.ADDED", "AddOutcome.REFUSED"))), "does not end with return AddOutcome.ADDED")
        assertFires("ADDED before the last statement", TileAdapterWiringRules.addEndProblems(addSample(" tileView = view\n return AddOutcome.ADDED\n layoutParams = params\n")), "does not end with return AddOutcome.ADDED")
        assertFires("no answer at all", TileAdapterWiringRules.addEndProblems(addSample(edit(keptAll, " return AddOutcome.ADDED\n", ""))), "does not end with return AddOutcome.ADDED")
        assertQuiet("the three statements", TileAdapterWiringRules.addEndProblems(addSample()))
        assertQuiet("the three statements with a comment and another statement", TileAdapterWiringRules.addEndProblems(addSample(" // keep it\n tileView = view\n layoutParams = params\n log2()\n return AddOutcome.ADDED\n")))

        assertEquals("overlay: $window must keep the view and answer ADDED last", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, TileAdapterWiringRules::addEndProblems))
    }

    // ---- The first face is the idle collapsed tile ----

    private fun faceSample(
        state: String = "state = TileState.IDLE",
        shape: String = "shape = TileShape.COLLAPSED",
        lit: String = "litSegments = 0",
        look: String = "look = TileStyle.look(TileState.IDLE, palette)",
        notice: String = "notice = null",
        description: String = "description = null",
    ) = "override fun add(x: Int, y: Int, palette: TruckingPalette): AddOutcome {\n val face = TileFace(\n $state,\n $shape,\n $lit,\n segments = LedMeter.SEGMENTS,\n $look,\n $notice,\n $description,\n )\n return AddOutcome.ADDED\n}\n"

    /** A failure here means a new window starts in the colours or the shape of another state, until the controller draws over it. */
    @Test
    fun `a new window starts with the idle collapsed face`() {
        assertFires("no function", TileAdapterWiringRules.firstFaceProblems("val x = 1\n"), "no add function")
        assertFires("no face", TileAdapterWiringRules.firstFaceProblems("override fun add(x: Int): AddOutcome {\n return AddOutcome.ADDED\n}\n"), "does not build a TileFace")
        assertFires("the failed state", TileAdapterWiringRules.firstFaceProblems(faceSample(state = "state = TileState.FAILED")), "state = 'TileState.FAILED', not 'TileState.IDLE'")
        assertFires("the notice shape", TileAdapterWiringRules.firstFaceProblems(faceSample(shape = "shape = TileShape.NOTICE")), "shape = 'TileShape.NOTICE', not 'TileShape.COLLAPSED'")
        assertFires("the recording shape", TileAdapterWiringRules.firstFaceProblems(faceSample(shape = "shape = TileShape.RECORDING")), "shape = 'TileShape.RECORDING'")
        assertFires("lit segments", TileAdapterWiringRules.firstFaceProblems(faceSample(lit = "litSegments = 3")), "litSegments = '3', not '0'")
        assertFires("the failed look", TileAdapterWiringRules.firstFaceProblems(faceSample(look = "look = TileStyle.look(TileState.FAILED, palette)")), "look = 'TileStyle.look(TileState.FAILED, palette)'")
        assertFires("another palette", TileAdapterWiringRules.firstFaceProblems(faceSample(look = "look = TileStyle.look(TileState.IDLE, other)")), "look = 'TileStyle.look(TileState.IDLE, other)'")
        assertFires("a notice", TileAdapterWiringRules.firstFaceProblems(faceSample(notice = "notice = \"text\"")), "notice = '\"text\"', not 'null'")
        assertFires("a description", TileAdapterWiringRules.firstFaceProblems(faceSample(description = "description = text")), "description = 'text', not 'null'")
        assertFires("the state left out", TileAdapterWiringRules.firstFaceProblems(faceSample(state = "TileState.IDLE")), "state = '(none)'")
        assertQuiet("the idle face", TileAdapterWiringRules.firstFaceProblems(faceSample()))
        assertQuiet("the idle face with spaces and breaks inside the look", TileAdapterWiringRules.firstFaceProblems(faceSample(look = "look = TileStyle.look(\n TileState.IDLE,\n palette\n )")))

        assertEquals("overlay: $window must start every window with the idle collapsed face", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, TileAdapterWiringRules::firstFaceProblems))
    }

    // ---- A theme change keeps the state of the face ----

    private fun paletteSample(
        held: String = "val face = view.face",
        repaint: String = "view.applyFace(face.copy(look = TileStyle.look(face.state, palette)))",
    ) = "override fun applyPalette(palette: TruckingPalette) {\n val view = tileView ?: return\n $held\n $repaint\n}\n"

    /** A failure here means a theme change repaints a recording or failed tile in the idle colours. */
    @Test
    fun `a theme change repaints the face with the look of its own state`() {
        assertFires("no function", TileAdapterWiringRules.paletteProblems("val x = 1\n"), "no applyPalette function")
        assertFires("the idle state", TileAdapterWiringRules.paletteProblems(paletteSample(repaint = "view.applyFace(face.copy(look = TileStyle.look(TileState.IDLE, palette)))")), "does not copy the face with TileStyle.look(face.state, palette)")
        assertFires("another palette", TileAdapterWiringRules.paletteProblems(paletteSample(repaint = "view.applyFace(face.copy(look = TileStyle.look(face.state, old)))")), "does not copy the face")
        assertFires("a fresh face", TileAdapterWiringRules.paletteProblems(paletteSample(repaint = "view.applyFace(fresh(TileStyle.look(face.state, palette)))")), "does not copy the face")
        assertFires("the face from somewhere else", TileAdapterWiringRules.paletteProblems(paletteSample(held = "val face = other.face")), "does not start from the face the view holds")
        assertFires("the repaint only in a comment", TileAdapterWiringRules.paletteProblems(paletteSample(repaint = "// view.applyFace(face.copy(look = TileStyle.look(face.state, palette)))")), "does not copy the face")
        assertQuiet("the repaint", TileAdapterWiringRules.paletteProblems(paletteSample()))
        assertQuiet("the repaint on several lines", TileAdapterWiringRules.paletteProblems(paletteSample(repaint = "view.applyFace(\n face.copy(look = TileStyle.look(face.state, palette))\n )")))

        assertEquals("overlay: $window must repaint with the look of the face's own state", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, TileAdapterWiringRules::paletteProblems))
    }

    // ---- remove swallows a view the system already removed ----

    private fun removeSample(
        call: String = "try {\n windowManager.removeView(view)\n } catch (e: IllegalArgumentException) {\n // gone\n }\n",
    ) = "private fun removeQuietly() {\n val view = tileView ?: return\n $call tileView = null\n}\n"

    /** A failure here means removing a window the system already took away crashes the app. */
    @Test
    fun `remove swallows only a view the system already removed`() {
        assertFires("no function", TileAdapterWiringRules.removeProblems("val x = 1\n"), "no removeQuietly function")
        assertFires("no removeView", TileAdapterWiringRules.removeProblems(removeSample("view.invalidate()\n")), "does not call windowManager.removeView(")
        assertFires("another exception", TileAdapterWiringRules.removeProblems(removeSample("try {\n windowManager.removeView(view)\n } catch (e: IllegalStateException) {\n }\n")), "does not catch IllegalArgumentException")
        assertFires("the exception thrown again", TileAdapterWiringRules.removeProblems(removeSample("try {\n windowManager.removeView(view)\n } catch (e: IllegalArgumentException) {\n throw e\n }\n")), "does not swallow")
        assertFires("no try", TileAdapterWiringRules.removeProblems(removeSample("windowManager.removeView(view)\n")), "outside a try")
        assertFires("the call after the try", TileAdapterWiringRules.removeProblems(removeSample("try {\n x()\n } catch (e: IllegalArgumentException) {\n }\n windowManager.removeView(view)\n")), "outside a try")
        assertFires("no catch", TileAdapterWiringRules.removeProblems(removeSample("try {\n windowManager.removeView(view)\n } finally {\n }\n")), "has no catch")
        assertQuiet("the guarded call", TileAdapterWiringRules.removeProblems(removeSample()))
        assertQuiet("a full exception name and more work in the try", TileAdapterWiringRules.removeProblems(removeSample("try {\n done()\n windowManager.removeView(view)\n } catch (e: java.lang.IllegalArgumentException) {\n }\n")))

        assertEquals("overlay: $window must swallow a view the system already removed", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, TileAdapterWiringRules::removeProblems))
    }

    // ---- The view redraws at once with the face it was given ----

    private fun applyFaceSample(store: String = "this.face = face", draw: String = "invalidate()") =
        "fun applyFace(face: TileFace) {\n $store\n contentDescription = face.description\n $draw\n}\n"

    /** A failure here means the tile keeps showing the old face, or shows the new one only when the system next gets to it. */
    @Test
    fun `applyFace stores the face and redraws at once`() {
        assertFires("no function", TileAdapterWiringRules.applyFaceStoreProblems("val x = 1\n"), "no applyFace function")
        assertFires("the face not stored", TileAdapterWiringRules.applyFaceStoreProblems(applyFaceSample(store = "")), "does not store the face")
        assertFires("another value stored", TileAdapterWiringRules.applyFaceStoreProblems(applyFaceSample(store = "this.face = face.copy()")), "does not store the face")
        assertFires("the store only in a comment", TileAdapterWiringRules.applyFaceStoreProblems(applyFaceSample(store = "// this.face = face")), "does not store the face")
        assertFires("no redraw", TileAdapterWiringRules.applyFaceStoreProblems(applyFaceSample(draw = "")), "does not redraw at once")
        assertFires("a posted redraw", TileAdapterWiringRules.applyFaceStoreProblems(applyFaceSample(draw = "postInvalidate()")), "does not redraw at once")
        assertFires("a posted redraw is named", TileAdapterWiringRules.applyFaceStoreProblems(applyFaceSample(draw = "postInvalidate()")), "posts a redraw")
        assertFires("a redraw of another view", TileAdapterWiringRules.applyFaceStoreProblems(applyFaceSample(draw = "other.invalidate()")), "does not redraw at once")
        assertFires("the redraw before the store", TileAdapterWiringRules.applyFaceStoreProblems(applyFaceSample(store = "invalidate()", draw = "this.face = face")), "redraws before it stores the face")
        assertQuiet("a store and a redraw", TileAdapterWiringRules.applyFaceStoreProblems(applyFaceSample()))
        assertQuiet("a redraw through this, and a posted redraw only in a comment", TileAdapterWiringRules.applyFaceStoreProblems("// postInvalidate()\n" + applyFaceSample(draw = "this.invalidate()")))

        assertEquals("overlay: $view must store the face and redraw at once", emptyList<String>(), inFile(ModuleFiles.mainTexts(), view, TileAdapterWiringRules::applyFaceStoreProblems))
    }

    // ---- The notice area draws the notice ----

    private fun noticeSample(text: String = "val text = face.notice ?: return", more: String = "val layout = obtain(text)") =
        "private fun drawNotice(canvas: Canvas, s: Int, face: TileFace) {\n $text\n $more\n}\n"

    /** A failure here means the strip shows the sentence meant for a screen reader instead of the app's notice. */
    @Test
    fun `the notice area draws the notice and not the description`() {
        assertFires("no function", TileAdapterWiringRules.noticeSourceProblems("val x = 1\n"), "no drawNotice function")
        assertFires("the description drawn", TileAdapterWiringRules.noticeSourceProblems(noticeSample(text = "val text = face.description ?: return")), "does not take its text from face.notice")
        assertFires("the description drawn is named", TileAdapterWiringRules.noticeSourceProblems(noticeSample(text = "val text = face.description ?: return")), "reads the description")
        assertFires("a fixed text", TileAdapterWiringRules.noticeSourceProblems(noticeSample(text = "val text = \"Wait\"")), "does not take its text from face.notice")
        assertFires("a notice that may be missing", TileAdapterWiringRules.noticeSourceProblems(noticeSample(text = "val text = face.notice ?: \"\"")), "does not take its text from face.notice")
        assertFires("the description read later", TileAdapterWiringRules.noticeSourceProblems(noticeSample(more = "val other = face.description")), "reads the description")
        assertQuiet("the notice", TileAdapterWiringRules.noticeSourceProblems(noticeSample()))
        assertQuiet("the word in a comment", TileAdapterWiringRules.noticeSourceProblems(noticeSample(more = "// not the description\n val layout = obtain(text)")))

        assertEquals("overlay: $view must draw the notice", emptyList<String>(), inFile(ModuleFiles.mainTexts(), view, TileAdapterWiringRules::noticeSourceProblems))
    }

    // ---- The view hands the sink screen positions of the owning pointer and answers true ----

    private val rawPosition = "event.getRawX(index), event.getRawY(index)"

    private fun touchSample(
        down: String = rawPosition,
        moveIndex: String = "event.findPointerIndex(activePointerId)",
        move: String = rawPosition,
        upIndex: String = "event.findPointerIndex(activePointerId)",
        up: String = rawPosition,
        end: String = "return true",
    ) = "override fun onTouchEvent(event: MotionEvent): Boolean {\n when (event.actionMasked) {\n" +
        " MotionEvent.ACTION_DOWN -> {\n val index = event.actionIndex\n activePointerId = event.getPointerId(index)\n sink.onTouchDown($down)\n }\n" +
        " MotionEvent.ACTION_MOVE -> {\n val index = $moveIndex\n if (index >= 0) {\n sink.onTouchMove($move)\n }\n }\n" +
        " MotionEvent.ACTION_POINTER_UP -> {\n val index = event.actionIndex\n sink.onTouchUp($rawPosition)\n }\n" +
        " MotionEvent.ACTION_UP -> {\n val index = $upIndex\n if (index >= 0) {\n sink.onTouchUp($up)\n }\n }\n" +
        " MotionEvent.ACTION_CANCEL -> {\n sink.onTouchCancel()\n }\n }\n $end\n}\n"

    /** A failure here means the gesture is read in view coordinates, or a second finger can drive it, or the system stops delivering the rest of a gesture. */
    @Test
    fun `the view hands over screen positions of the owning pointer and answers true`() {
        val viewPosition = "event.getX(index), event.getY(index)"
        assertFires("no function", TileAdapterWiringRules.touchProblems("val x = 1\n"), "no onTouchEvent function")
        assertFires("view coordinates on down", TileAdapterWiringRules.touchProblems(touchSample(down = viewPosition)), "sink.onTouchDown is given 'event.getX(index), event.getY(index)'")
        assertFires("view coordinates on move", TileAdapterWiringRules.touchProblems(touchSample(move = viewPosition)), "sink.onTouchMove is given")
        assertFires("view coordinates on up", TileAdapterWiringRules.touchProblems(touchSample(up = viewPosition)), "sink.onTouchUp is given")
        assertFires("x and y swapped on down", TileAdapterWiringRules.touchProblems(touchSample(down = "event.getRawY(index), event.getRawX(index)")), "sink.onTouchDown is given")
        assertFires("the first pointer on down", TileAdapterWiringRules.touchProblems(touchSample(down = "event.getRawX(0), event.getRawY(0)")), "sink.onTouchDown is given")
        assertFires("no move call", TileAdapterWiringRules.touchProblems(edit(touchSample(), "sink.onTouchMove($rawPosition)", "idle()")), "never calls sink.onTouchMove")
        assertFires("the first pointer on move", TileAdapterWiringRules.touchProblems(touchSample(moveIndex = "0")), "the ACTION_MOVE branch does not read the pointer that owns the gesture")
        assertFires("the first pointer on up", TileAdapterWiringRules.touchProblems(touchSample(upIndex = "0")), "the ACTION_UP branch does not read the pointer that owns the gesture")
        assertFires("another pointer id on move", TileAdapterWiringRules.touchProblems(touchSample(moveIndex = "event.findPointerIndex(otherId)")), "the ACTION_MOVE branch")
        assertFires("no move branch", TileAdapterWiringRules.touchProblems(edit(touchSample(), "MotionEvent.ACTION_MOVE", "MotionEvent.ACTION_HOVER_MOVE")), "no MotionEvent.ACTION_MOVE branch")
        assertFires("a false answer", TileAdapterWiringRules.touchProblems(touchSample(end = "return false")), "does not end with return true")
        assertFires("a false answer is named", TileAdapterWiringRules.touchProblems(touchSample(end = "return false")), "answers false")
        assertFires("no answer", TileAdapterWiringRules.touchProblems(touchSample(end = "")), "does not end with return true")
        assertFires("an early false answer", TileAdapterWiringRules.touchProblems(touchSample(end = "if (event.pointerCount > 3) return false\n return true")), "answers false")
        assertQuiet("raw positions, the owning pointer and true", TileAdapterWiringRules.touchProblems(touchSample()))
        assertQuiet("view coordinates only in a comment", TileAdapterWiringRules.touchProblems(touchSample(end = "// $viewPosition\n return true")))

        assertEquals("overlay: $view must hand over screen positions of the owning pointer and answer true", emptyList<String>(), inFile(ModuleFiles.mainTexts(), view, TileAdapterWiringRules::touchProblems))
    }
}
