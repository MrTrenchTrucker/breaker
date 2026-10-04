package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Every distance in this module comes from a token.
 *
 * The design gives two sizes and no spacing scale: the floor a control must reach
 * to be tappable, and the corner radius the surfaces are drawn with. There is no
 * padding token, no margin token and no gap between rows, which is why the rows
 * simply touch. A number written into the renderer is therefore either one of
 * those two tokens, the platform's own constants, or a private decision the design
 * never made, and the last of those is what this rule is for.
 *
 * Zero and one are permitted because they are not distances: one is the hairline
 * the trim stripe is drawn with, the single literal the design discloses, and zero
 * is what several platform constants are spelled as. Every other number is caught,
 * and a constant whose name says it is a distance is caught whatever its value, so
 * renaming a private distance to something neutral does not hide it.
 */

/** The renderer package, the one place a size may be applied. */
private const val RENDER_DIRECTORY = "render"

/** The two sizes the design gives, which the renderer must read by name. */
private val TOKEN_NAMES = listOf("minTouchTargetDp", "cornerRadiusDp")

/**
 * A number in code.
 *
 * A digit preceded by a letter or a dot is part of a name or a version, not a
 * number; the lookbehind keeps `argb1` and `0x20` from being read as literals.
 */
private val NUMBER = Regex("""(?<![\w.$])(\d[\d_]*(?:\.\d+)?)[fFlLuU]*""")

/** A name that says the value it holds is a distance. */
private val DISTANCE_NAME = Regex("""(?i)(dp|px|pad|padding|margin|inset|gap|spacing|space)""")

/** A declaration of a value, with the name it binds. */
private val VALUE_DECLARATION = Regex("""\b(?:const\s+)?(?:val|var)\s+([A-Za-z_]\w*)""")

/** The numbers in the code of [text] that are neither zero nor one. */
private fun distancesIn(text: String): List<String> =
    NUMBER.findAll(withoutCommentsAndStrings(text))
        .map { it.groupValues[1] }
        .filter { it != "0" && it != "1" }
        .toList()

/** The names bound by a `val` or `var` in the code of [text] that name a distance. */
private fun distanceNamesIn(text: String): List<String> =
    VALUE_DECLARATION.findAll(withoutCommentsAndStrings(text))
        .map { it.groupValues[1] }
        .filter { DISTANCE_NAME.containsMatchIn(it) }
        .toList()

/** What [text] breaks, as one entry per line so a message points at the line. */
private fun breaksIn(text: String): List<String> =
    text.lines().mapIndexedNotNull { number, line ->
        val onThisLine = withoutCommentsAndStrings(line)
        val numbers = distancesIn(onThisLine)
        val names = distanceNamesIn(onThisLine)
        when {
            numbers.isNotEmpty() -> "${number + 1}: the number ${numbers.joinToString()}"
            names.isNotEmpty() -> "${number + 1}: the distance named ${names.joinToString()}"
            else -> null
        }
    }

/** The renderer's own sources, by path relative to the package. */
private val RENDER_SOURCES: List<Pair<String, String>> =
    MAIN_SOURCES.filter { (path, _) -> path.startsWith("$RENDER_DIRECTORY/") }

/**
 * No private distance in the renderer, and both token names read there.
 *
 * The file list is checked first, because a scan of nothing passes; and the two
 * token names are required, because a renderer that reached no token at all would
 * satisfy "no private distance" while painting every row its own size.
 */
class NoPrivateSpacingGateTest {
    @Test
    fun `the renderer holds no number but the hairline`() {
        val found = RENDER_SOURCES.flatMap { (path, text) ->
            distancesIn(text).map { number -> "$path: $number" }
        }
        assertEquals("distances written into the renderer: $found", emptyList<String>(), found)
    }

    @Test
    fun `the renderer names no value after a distance`() {
        val found = RENDER_SOURCES.flatMap { (path, text) ->
            distanceNamesIn(text).map { name -> "$path: $name" }
        }
        assertEquals("distances named in the renderer: $found", emptyList<String>(), found)
    }

    @Test
    fun `the rule covers every renderer file and nothing outside it`() {
        // A gate that skipped a file would pass on a breach placed in it, so the
        // set of files the rule reads is checked against the tree rather than
        // against a list of names that a change could shorten at the same time.
        assertTrue("the renderer was not found among the sources", RENDER_SOURCES.isNotEmpty())
        assertEquals(
            "the renderer sources include a file from elsewhere in the module",
            emptyList<String>(),
            RENDER_SOURCES.filter { (path, _) -> !path.startsWith("$RENDER_DIRECTORY/") }.map { it.first },
        )
        val expected = MAIN_SOURCES.count { (path, _) -> path.startsWith("$RENDER_DIRECTORY/") }
        assertEquals(
            "the gate read $expected renderer sources out of ${MAIN_SOURCES.size} in the module",
            expected,
            RENDER_SOURCES.size,
        )
        assertTrue(
            "a renderer source was read as empty",
            RENDER_SOURCES.all { (_, text) -> text.isNotEmpty() },
        )
    }

    @Test
    fun `the renderer really does take its sizes from the tokens`() {
        val code = RENDER_SOURCES.joinToString("\n") { it.second }
        for (token in TOKEN_NAMES) {
            assertTrue(
                "the renderer never reads $token, so nothing in it comes from the tokens",
                code.contains(token),
            )
        }
    }

    @Test
    fun `a private distance written as a number is caught`() {
        val control = "view.setPadding(16, 16, 16, 16)\n"
        assertEquals(listOf("1: the number 16, 16, 16, 16"), breaksIn(control))
        val height = "button.minHeight = 44\n"
        assertEquals(listOf("1: the number 44"), breaksIn(height))
        val tracking = "label.letterSpacing = 0.05f\n"
        assertEquals(listOf("1: the number 0.05"), breaksIn(tracking))
    }

    @Test
    fun `a private distance written as a constant is caught by its name`() {
        val control = "private const val rowDp = 8\n"
        assertEquals("1: the number 8", breaksIn(control).first())
        val named = "private const val PAD = 0\n"
        assertEquals(listOf("1: the distance named PAD"), breaksIn(named))
        val neutral = "private const val gutter = 8\n"
        assertEquals("1: the number 8", breaksIn(neutral).first())
    }

    @Test
    fun `the hairline and zero are the numbers the renderer may write`() {
        val control = """
            private fun stripeOf(theme: Theme): View =
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    scrollTo(0, 0)
                }
        """.trimIndent()
        assertEquals(emptyList<String>(), breaksIn(control))
    }

    @Test
    fun `a number inside a name or a colour value is not a distance`() {
        val control = """
            val shade1 = primary
            val pair2000 = shade1
            val metrics2 = TruckingTokens.metrics
        """.trimIndent()
        assertEquals(emptyList<String>(), distancesIn(control))
        assertEquals(emptyList<String>(), breaksIn(control))
    }

    @Test
    fun `a number quoted in a comment is prose and is left alone`() {
        // A file may say in a comment that it holds no 48 dp of its own; that is a
        // sentence about the rule, and reading it as a breach would make the gate
        // impossible to document.
        val control = """
            // rows touch: there is no 48 dp gap between them
            private const val TOUCH_TARGET = 48
        """.trimIndent()
        assertEquals(listOf("2: the number 48"), breaksIn(control))
        assertEquals(
            "the number in the comment was counted",
            emptyList<String>(),
            breaksIn("// rows touch: there is no 48 dp gap between them\n"),
        )
    }

    @Test
    fun `a token read as a value is not a distance, and a token named in code is not a breach`() {
        val control = """
            minHeight = touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), density)
            cornerRadius = theme.metrics.cornerRadiusDp * density
        """.trimIndent()
        assertEquals(emptyList<String>(), breaksIn(control))
    }

    @Test
    fun `a density multiplied by a token is still a token`() {
        val control = "val radius = theme.metrics.cornerRadiusDp * density\n"
        assertEquals(emptyList<String>(), distancesIn(control))
        assertEquals(emptyList<String>(), distanceNamesIn(control))
    }

    @Test
    fun `two breaches on two lines are both reported`() {
        val control = """
            private const val rowDp = 8
            private const val gutter = 12
        """.trimIndent()
        assertEquals(
            listOf("1: the number 8", "2: the number 12"),
            breaksIn(control),
        )
    }

    @Test
    fun `a distance declared but valued from a token is still named and caught`() {
        // The name rule exists so that renaming a private distance to something
        // neutral does not hide it: the value comes from a token, but the binding
        // says in its own name that it is a distance the renderer owns.
        val control = "private val rowGap = theme.metrics.cornerRadiusDp * density\n"
        assertEquals(emptyList<String>(), distancesIn(control))
        assertEquals(listOf("rowGap"), distanceNamesIn(control))
        assertEquals(listOf("1: the distance named rowGap"), breaksIn(control))
    }
}
