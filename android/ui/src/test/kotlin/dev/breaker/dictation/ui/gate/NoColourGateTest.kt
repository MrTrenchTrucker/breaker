package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * No colour is written down anywhere in this module.
 *
 * A colour belongs to the design tokens, and the way this module keeps that true
 * is that it has no way to hold one: a screen names a palette slot, the theme
 * turns the slot into a token value, and the renderer is the only place a value
 * becomes a number a view can use. A literal colour written into any other file
 * would be a second source of truth, and one no later change to the tokens would
 * reach.
 *
 * A colour can be written four ways, so all four are caught: the framework's own
 * colour type, a hex string, a hex number and a plain large number, which is what
 * a packed colour looks like once it has been through a compiler. The tests also
 * check the numbers that are not colours are left alone, because a rule that
 * caught every number would pass for the wrong reason on a file that happened to
 * hold a bitmask.
 */

/** A packed colour written as a hex string, with or without its alpha. */
private val HEX_STRING = Regex("""(?i)["']#(?:[0-9a-f]{8}|[0-9a-f]{6})["']""")

/** A packed colour written as a hex number, with or without its alpha. */
private val HEX_NUMBER = Regex("""\b0[xX](?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6})\b""")

/** A packed colour written as the decimal number it is worth. */
private val LARGE_DECIMAL = Regex("""(?<![\w.$])-?[1-9][0-9]{6,}(?![\w.$])""")

/** The framework's colour type, which paints and blends rather than naming a token. */
private val FRAMEWORK_COLOUR = Regex("""\bandroid\.graphics\.(?:Color|color)\b""")

/** The name that shape is reported under, so a message says what was found. */
private const val FRAMEWORK_COLOUR_SHAPE = "the framework colour type"

/**
 * The named shape a piece of [text] breaks, or null when it breaks none.
 *
 * Comments go before the shape is looked for and string literals stay: a colour
 * written as `"#1E7A46"` is a string, and a rule that stripped strings before
 * looking for one could never fire on the case it exists for.
 *
 * One shape is reported per call so a message names what was found; the caller
 * loops over the text and collects, so a file with two shapes still shows both.
 */
private fun colourIn(text: String): String? {
    val code = withoutComments(text)
    return when {
        FRAMEWORK_COLOUR.containsMatchIn(code) -> FRAMEWORK_COLOUR_SHAPE
        HEX_STRING.containsMatchIn(code) -> "a hex colour string"
        HEX_NUMBER.containsMatchIn(code) -> "a hex colour number"
        LARGE_DECIMAL.containsMatchIn(code) -> "a packed colour as a decimal number"
        else -> null
    }
}

/** Every line of [text] that carries a colour literal, each as `line: shape`. */
private fun colourLinesIn(text: String): List<String> =
    text.lines().mapIndexedNotNull { number, line ->
        colourIn(line)?.let { shape -> "${number + 1}: $shape" }
    }

/** The compiled form of the framework colour type, as a class file spells it. */
private const val FRAMEWORK_COLOUR_CLASS = "android/graphics/Color"

/**
 * No colour literal in the module's own text, and none in the classes it compiles
 * to.
 */
class NoColourGateTest {
    @Test
    fun `no source file names a colour`() {
        val found = MAIN_SOURCES.flatMap { (path, text) ->
            colourLinesIn(text).map { line -> "$path:$line" }
        }
        assertEquals("colour literals in the main tree: $found", emptyList<String>(), found)
    }

    @Test
    fun `no compiled class names the framework colour`() {
        val found = CLASS_ROOTS.flatMap { root ->
            classFilesOf(root)
                .filter { (_, bytes) -> bytes.contains(FRAMEWORK_COLOUR_CLASS) }
                .map { (path, _) -> path }
        }
        assertEquals("classes naming ${FRAMEWORK_COLOUR_CLASS.replace('/', '.')}", emptyList<String>(), found)
    }

    @Test
    fun `the renderer really does turn a token into a number, so the gate is looking`() {
        val renderer = mainSourceOf("render/ScreenRenderer.kt")
        assertTrue(
            "the renderer is expected to read a token value and does not",
            renderer.contains("theme.argbOf(") || renderer.contains("color(PaletteSlot."),
        )
        assertEquals(
            "the renderer holds a colour of its own",
            emptyList<String>(),
            colourLinesIn(renderer),
        )
    }

    @Test
    fun `the framework colour type is caught however it is written`() {
        val imported = "import android.graphics.Color\nval c = Color.RED\n"
        assertEquals("1: $FRAMEWORK_COLOUR_SHAPE", colourLinesIn(imported).first())
        val named = "val c = android.graphics.Color.rgb(30, 122, 70)\n"
        assertEquals("1: $FRAMEWORK_COLOUR_SHAPE", colourLinesIn(named).first())
    }

    @Test
    fun `a hex colour string is caught`() {
        val control = "val swatch = \"#1E7A46\"\n"
        assertEquals("1: a hex colour string", colourLinesIn(control).first())
        val withAlpha = "val swatch = \"#FF1E7A46\"\n"
        assertEquals("1: a hex colour string", colourLinesIn(withAlpha).first())
    }

    @Test
    fun `a hex colour number is caught`() {
        val control = "val swatch = 0xFF1E7A46.toInt()\n"
        assertEquals("1: a hex colour number", colourLinesIn(control).first())
    }

    @Test
    fun `a packed colour as a decimal number is caught`() {
        assertEquals("1: a packed colour as a decimal number", colourLinesIn("val c = -14451130\n").first())
        assertEquals("1: a packed colour as a decimal number", colourLinesIn("val c = 4278526309\n").first())
    }

    @Test
    fun `the bitmasks and small numbers the module needs are left alone`() {
        // A uiMode bitmask and a touch target size are numbers, not colours, and a
        // rule that caught them would be a rule nobody could satisfy.
        val control = """
            private const val NIGHT_MASK = 0x30
            private const val NIGHT_YES = 0x20
            private const val TOUCH_TARGET_DP = 48
            private const val CORNER_RADIUS_DP = 6
        """.trimIndent()
        assertEquals(emptyList<String>(), colourLinesIn(control))
    }

    @Test
    fun `an ordinary number inside an identifier is not a packed colour`() {
        val control = "val shade1 = primary\nval pair2000 = shade1\n"
        assertEquals(emptyList<String>(), colourLinesIn(control))
    }

    @Test
    fun `a hex colour written in a comment or a string is not counted twice`() {
        // A comment that explains a colour is prose and a string that names one is
        // content; neither is a value the module would paint with.
        val control = """
            // the tokens carry #1E7A46 and 0xFF1E7A46
            val note = "1E7A46"
        """.trimIndent()
        assertEquals(emptyList<String>(), colourLinesIn(control))
    }

    @Test
    fun `a colour shape is still caught in a file that also has numbers that are not colours`() {
        // The point of the contrast: the rule must be about colours, so a file that
        // holds a bitmask and a hex colour is caught for the colour alone.
        val control = """
            private const val NIGHT_MASK = 0x20
            val shade = 0xFF1E7A46.toInt()
        """.trimIndent()
        assertEquals(listOf("2: a hex colour number"), colourLinesIn(control))
    }

    @Test
    fun `every shape is reported at its own line`() {
        val control = """
            val a = 0x30
            val b = "#1E7A46"
            val c = android.graphics.Color.RED
        """.trimIndent()
        assertEquals(
            listOf("2: a hex colour string", "3: $FRAMEWORK_COLOUR_SHAPE"),
            colourLinesIn(control),
        )
    }

    @Test
    fun `a colour in a test fixture is caught, so the rule is not main-tree only`() {
        val control = "val NONDEFAULT_PRIMARY = 0xFF1E7A46.toInt()\n"
        assertEquals("1: a hex colour number", colourLinesIn(control).first())
    }
}
