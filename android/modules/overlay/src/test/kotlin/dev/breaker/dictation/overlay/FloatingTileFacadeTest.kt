package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.TilePosition
import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public [FloatingTile], built with its internal constructor over a controller on fakes.
 *
 * Each test builds its own fakes. The screen is the one in [TestData].
 */
class FloatingTileFacadeTest {

    /** If this fails, the facade changes or invents a result instead of returning the controller's. */
    @Test
    fun `show returns what the controller returns`() {
        val shownWindow = FakeTileWindow()
        val shown = FloatingTile(TestData.controller(shownWindow, FakeSettingsStore(AppSettings())))
        val first = shown.show()
        val second = shown.show()

        val missingWindow = FakeTileWindow(permission = false)
        val missing = FloatingTile(TestData.controller(missingWindow, FakeSettingsStore(AppSettings())))
        val third = missing.show()

        val refusedWindow = FakeTileWindow(addOutcome = AddOutcome.REFUSED)
        val refused = FloatingTile(TestData.controller(refusedWindow, FakeSettingsStore(AppSettings())))
        val fourth = refused.show()

        assertSame("android_overlay: expected SHOWN from the first show", ShowResult.SHOWN, first)
        assertSame("android_overlay: expected ALREADY_SHOWN from the second show", ShowResult.ALREADY_SHOWN, second)
        assertSame("android_overlay: expected PERMISSION_MISSING without the permission", ShowResult.PERMISSION_MISSING, third)
        assertSame("android_overlay: expected FAILED from a refused window", ShowResult.FAILED, fourth)
        assertEquals("android_overlay: expected the shown tile to add one window", 1, shownWindow.adds.size)
        assertEquals("android_overlay: expected no add without the permission", 0, missingWindow.adds.size)
        assertEquals("android_overlay: expected one attempted add on the refused tile", 1, refusedWindow.adds.size)
    }

    /** If this fails, hide on the facade never reaches the controller. */
    @Test
    fun `hide reaches the controller`() {
        val window = FakeTileWindow()
        val tile = FloatingTile(TestData.controller(window, FakeSettingsStore(AppSettings())))
        tile.show()

        tile.hide()

        assertEquals("android_overlay: expected the window removed once", 1, window.removeCount)
        assertFalse("android_overlay: expected the tile to be hidden", tile.isShown)
    }

    /** If this fails, setTheme or onDisplayChanged on the facade does not reach the controller. */
    @Test
    fun `setTheme and onDisplayChanged reach the controller`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(TestData.settings(TilePosition(0.25f, 0.75f)))
        val tile = FloatingTile(TestData.controller(window, store, theme = ThemeMode.LIGHT))
        tile.show()

        tile.setTheme(ThemeMode.DARK)

        assertEquals("android_overlay: expected one palette applied", 1, window.appliedPalettes.size)
        assertSame("android_overlay: expected the dark palette applied", TruckingTokens.DARK, window.appliedPalettes[0])

        // Fresh screen: origin (0, 0), 1200 by 2200 pixels, tile 200 pixels square, so the movable
        // range is 1000 by 2000 and the saved fraction (0.25, 0.75) is x 250, y 1500.
        window.bounds = PixelBounds(0, 0, 1200, 2200)
        window.sizePx = 200
        tile.onDisplayChanged()

        assertEquals("android_overlay: expected one move after the display change", listOf(PixelPoint(250, 1500)), window.moves)
    }

    /** If this fails, the facade keeps its own shown flag instead of following the controller. */
    @Test
    fun `isShown follows the controller`() {
        val window = FakeTileWindow(addOutcome = AddOutcome.REFUSED)
        val controller = TestData.controller(window, FakeSettingsStore(AppSettings()))
        val tile = FloatingTile(controller)
        assertFalse("android_overlay: expected hidden before any show", tile.isShown)
        assertEquals("android_overlay: expected the facade to match the controller at the start", controller.isShown, tile.isShown)

        tile.show()
        assertFalse("android_overlay: expected hidden after a refused show", tile.isShown)
        assertEquals("android_overlay: expected the facade to match the controller after a refusal", controller.isShown, tile.isShown)

        window.addOutcome = AddOutcome.ADDED
        tile.show()
        assertTrue("android_overlay: expected shown after an accepted show", tile.isShown)
        assertEquals("android_overlay: expected the facade to match the controller when shown", controller.isShown, tile.isShown)

        tile.hide()
        assertFalse("android_overlay: expected hidden after hide", tile.isShown)
        assertEquals("android_overlay: expected the facade to match the controller after hide", controller.isShown, tile.isShown)
    }

    /** If this fails, setState on the facade does not reach the controller, or the facade keeps a state of its own. */
    @Test
    fun `setState reaches the controller and state follows it`() {
        val window = FakeTileWindow()
        val controller = TestData.controller(window, FakeSettingsStore(AppSettings()))
        val tile = FloatingTile(controller)
        assertSame("overlay: expected idle before any push", TileState.IDLE, tile.state)
        tile.show()

        tile.setState(TileState.ARMED)

        assertSame("overlay: expected the facade state to follow the push", TileState.ARMED, tile.state)
        assertSame("overlay: expected the controller to hold the pushed state", TileState.ARMED, controller.state)
        assertSame("overlay: expected the window to draw the pushed state", TileState.ARMED, window.appliedFaces.last().state)
    }

    /** If this fails, setLevel on the facade does not reach the controller. */
    @Test
    fun `setLevel reaches the controller`() {
        val window = FakeTileWindow()
        val tile = FloatingTile(TestData.controller(window, FakeSettingsStore(AppSettings())))
        tile.show()
        tile.setState(TileState.RECORDING)

        tile.setLevel(0.5f)

        assertEquals("overlay: expected half a level to light 6 segments", 6, window.appliedFaces.last().litSegments)
    }

    /** If this fails, showNotice or clearNotice on the facade does not reach the controller. */
    @Test
    fun `showNotice and clearNotice reach the controller`() {
        val window = FakeTileWindow()
        val tile = FloatingTile(TestData.controller(window, FakeSettingsStore(AppSettings())))
        tile.show()

        tile.showNotice("Turn dictation on first")
        assertEquals("overlay: expected the sentence in the face", "Turn dictation on first", window.appliedFaces.last().notice)
        assertEquals("overlay: expected the notice shape", TileShape.NOTICE, window.appliedFaces.last().shape)

        tile.clearNotice()
        assertEquals("overlay: expected the square shape after clearing", TileShape.COLLAPSED, window.appliedFaces.last().shape)
        assertNull("overlay: expected the sentence gone after clearing", window.appliedFaces.last().notice)
    }

    /** If this fails, setDescription on the facade does not reach the controller. */
    @Test
    fun `setDescription reaches the controller`() {
        val window = FakeTileWindow()
        val tile = FloatingTile(TestData.controller(window, FakeSettingsStore(AppSettings())))
        tile.show()

        tile.setDescription("Dictation is on")
        assertEquals("overlay: expected the description in the face", "Dictation is on", window.appliedFaces.last().description)

        tile.setDescription(null)
        assertNull("overlay: expected no description after null", window.appliedFaces.last().description)
    }

    // ---- the create() parameters, read from the source (create needs an Android context) ----

    /** The parameters of the function `create` in [source] as written, split at the commas outside brackets; null when there is none. */
    private fun createParameters(source: String): List<String>? {
        val code = SourceText.code(source)
        val head = Regex("\\bfun\\s+create\\s*\\(").find(code) ?: return null
        val parts = ArrayList<String>()
        var depth = 1
        var start = head.range.last + 1
        for (i in start until code.length) {
            when (code[i]) {
                '(', '<', '[' -> depth++
                ')', '>', ']' -> {
                    if (code[i] == '>' && code[i - 1] == '-') continue
                    depth--
                    if (depth == 0) {
                        parts.add(code.substring(start, i).trim())
                        return parts.filter { it.isNotEmpty() }
                    }
                }
                ',' -> if (depth == 1) {
                    parts.add(code.substring(start, i).trim())
                    start = i + 1
                }
            }
        }
        return null
    }

    private fun parameterNames(parameters: List<String>): List<String> = parameters.map { it.substringBefore(':').trim() }

    private fun defaultedNames(parameters: List<String>): List<String> =
        parameters.filter { it.contains("= null") }.map { it.substringBefore(':').trim() }

    /** If this fails, create() lost a parameter, or the old four-argument call no longer compiles because a new one has no default. */
    @Test
    fun `create keeps its four required parameters and defaults every other`() {
        val sample = "fun create(a: Int, b: () -> Unit, c: (() -> Unit)? = null,): FloatingTile = x()"
        assertEquals("overlay: control: the reader should find the names", listOf("a", "b", "c"), parameterNames(createParameters(sample)!!))
        assertEquals("overlay: control: the reader should find only the defaulted names", listOf("c"), defaultedNames(createParameters(sample)!!))
        assertNull("overlay: control: no create means no parameters", createParameters("fun other(a: Int) {}"))

        val real = createParameters(ModuleFiles.mainTexts().getValue("FloatingTile.kt"))!!
        assertEquals(
            "overlay: expected create to take these parameters in this order",
            listOf("context", "settings", "onTap", "theme", "onSaveFailed", "onBegin", "onCancel", "onSend"),
            parameterNames(real),
        )
        assertEquals(
            "overlay: expected every parameter after the fourth to default to null",
            listOf("onSaveFailed", "onBegin", "onCancel", "onSend"),
            defaultedNames(real),
        )
    }

    /** The arguments of the first call of [callee] after `fun create` in [source], split at the commas outside brackets; null when there is none. */
    private fun createCallArguments(source: String, callee: String): List<String>? {
        val code = SourceText.code(source)
        val create = Regex("\\bfun\\s+create\\s*\\(").find(code) ?: return null
        val call = Regex("\\b$callee\\s*\\(").find(code, create.range.last) ?: return null
        val parts = ArrayList<String>()
        var depth = 1
        var start = call.range.last + 1
        for (i in start until code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) {
                        parts.add(code.substring(start, i).trim())
                        return parts.filter { it.isNotEmpty() }
                    }
                }
                ',' -> if (depth == 1) {
                    parts.add(code.substring(start, i).trim())
                    start = i + 1
                }
            }
        }
        return null
    }

    private val controllerParameters = listOf("window", "settings", "onTap", "theme", "slopPx", "onSaveFailed", "onBegin", "onCancel", "onSend")

    /** The problems with how `create` hands the app's callbacks to the controller: each under its own name, whether the call is positional or named. */
    private fun wiringProblems(source: String): List<String> {
        val arguments = createCallArguments(source, "TileController") ?: return listOf("create does not build a TileController")
        val given = HashMap<String, String>()
        arguments.forEachIndexed { index, text ->
            val named = Regex("^(\\w+)\\s*=\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(text)
            if (named != null) { given[named.groupValues[1]] = named.groupValues[2].trim() }
            else if (index < controllerParameters.size) { given[controllerParameters[index]] = text }
        }
        return controllerParameters.filter { it.startsWith("on") }.mapNotNull { name ->
            when (val value = given[name]) {
                null -> "create does not pass $name to the controller"
                name -> null
                else -> "create passes '$value' as $name"
            }
        } + listOfNotNull("create passes more arguments than the controller takes".takeIf { arguments.size > controllerParameters.size })
    }

    private fun wiringSample(call: String) = "fun create(a: Int, onSend: (() -> Unit)? = null): FloatingTile {\n val w = X(ctx)\n val controller = $call\n return FloatingTile(controller)\n}\n"
    private val positional = "TileController(window, settings, onTap, theme, slopPx, onSaveFailed, onBegin, onCancel, onSend)"

    /** If this fails, a swap or a dropped argument inside create() hands the app's cancel to the begin of the tile, or loses a callback, with every other test green. */
    @Test
    fun `create hands every callback to the controller under its own name`() {
        fun fires(what: String, call: String, part: String) {
            val found = wiringProblems(wiringSample(call))
            assertTrue("overlay: control: $what must be reported with '$part', got $found", found.any { it.contains(part) })
        }
        fun quiet(what: String, call: String) =
            assertEquals("overlay: control: $what must not be reported", emptyList<String>(), wiringProblems(wiringSample(call)))

        fires("begin and cancel swapped", positional.replace("onBegin, onCancel", "onCancel, onBegin"), "passes 'onCancel' as onBegin")
        fires("cancel and send swapped", positional.replace("onCancel, onSend", "onSend, onCancel"), "passes 'onSend' as onCancel")
        fires("save-failed and begin swapped", positional.replace("onSaveFailed, onBegin", "onBegin, onSaveFailed"), "passes 'onBegin' as onSaveFailed")
        fires("the last callback dropped", positional.replace(", onSend)", ")"), "does not pass onSend")
        fires("the tap callback replaced", positional.replace("onTap, theme", "{ }, theme"), "passes '{ }' as onTap")
        fires("a named swap", "TileController(window, settings, onTap, theme, slopPx, onBegin = onCancel, onCancel = onBegin, onSend = onSend, onSaveFailed = onSaveFailed)", "passes 'onCancel' as onBegin")
        fires("a named call that leaves one out", "TileController(window, settings, onTap, theme, slopPx, onBegin = onBegin, onCancel = onCancel, onSaveFailed = onSaveFailed)", "does not pass onSend")
        fires("an extra argument", positional.replace(", onSend)", ", onSend, extra)"), "more arguments")
        assertTrue("overlay: control: no TileController call must be reported", wiringProblems("fun create(a: Int) { }\n").contains("create does not build a TileController"))
        quiet("the right positional order", positional)
        quiet("named arguments in another order", "TileController(window, settings, onTap, theme, slopPx, onSend = onSend, onBegin = onBegin, onCancel = onCancel, onSaveFailed = onSaveFailed)")
        quiet("the call over several lines with a trailing comma", "TileController(\n window,\n settings,\n onTap,\n theme,\n slopPx,\n onSaveFailed,\n onBegin,\n onCancel,\n onSend,\n)")

        assertEquals(
            "overlay: create must hand each callback to the TileController under its own name",
            emptyList<String>(),
            wiringProblems(ModuleFiles.mainTexts().getValue("FloatingTile.kt")),
        )
    }
}
