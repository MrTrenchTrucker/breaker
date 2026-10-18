package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text checks for BrandArtViews.kt: the brand art views
 * exist, are under the 300-line cap, reference the brand vectors, use
 * HomeGeometry for sizes and theme for colours, and hold no colour or
 * distance of their own.
 */
class BrandArtViewsGateTest {

    private val source: String = mainSourceOf("render/BrandArtViews.kt")

    @Test
    fun `BrandArtViews is under the 300-line cap`() {
        val lines = source.lines().size
        assertTrue("BrandArtViews.kt has $lines lines, over the 300 cap", lines < 300)
    }

    @Test
    fun `BrandArtViews references the brand vector drawables`() {
        assertTrue("the dark logo is not referenced", source.contains("breaker_logo_dark"))
        assertTrue("the light logo is not referenced", source.contains("breaker_logo_light"))
        assertTrue("the mic tile is not referenced", source.contains("breaker_mic_tile"))
    }

    @Test
    fun `BrandArtViews takes its sizes from HomeGeometry`() {
        assertTrue("LOGO_H_DP is not read", source.contains("HomeGeometry.LOGO_H_DP"))
        assertTrue("bigMic is not read", source.contains("HomeGeometry.bigMic"))
        assertTrue("stripes is not read", source.contains("HomeGeometry.stripes"))
        assertTrue("statusCard is not read", source.contains("HomeGeometry.statusCard"))
    }

    @Test
    fun `BrandArtViews takes its colours from the theme`() {
        assertTrue("PaletteSlot is not read in the code", paletteReadIn(source))
        assertTrue("color is not read", source.contains("color(PaletteSlot."))
        assertTrue("the glow is not read from the primary slot", source.contains(GLOW_READ))
    }

    @Test
    fun `the palette rule is not satisfied by the import alone`() {
        val importOnly = "import dev.breaker.dictation.ui.theme.PaletteSlot\nclass Only\n"
        assertFalse("an import is not a use", paletteReadIn(importOnly))
        val commentOnly = "// PaletteSlot.PRIMARY is named in a comment only\nclass Only\n"
        assertFalse("a comment is not a use", paletteReadIn(commentOnly))
        val recoloured = editOnce(source, GLOW_READ, "theme.color(PaletteSlot.ACCENT).argb")
        assertFalse("the glow read moved to another slot is caught", withoutComments(recoloured).contains(GLOW_READ))
    }

    @Test
    fun `BrandArtViews holds no colour of its own`() {
        val found = colourLinesIn(source)
        assertEquals("colour literals in BrandArtViews: $found", emptyList<String>(), found)
    }

    @Test
    fun `BrandArtViews holds no distance of its own`() {
        val found = distancesIn(source)
        assertEquals("distances in BrandArtViews: $found", emptyList<String>(), found)
        val names = distanceNamesIn(source)
        assertEquals("distances named in BrandArtViews: $names", emptyList<String>(), names)
    }

    @Test
    fun `BrandArtViews is wired into HomeHostView`() {
        val host = mainSourceOf("render/HomeHostView.kt")
        assertTrue("applyBrandArt is not called", host.contains("applyBrandArt"))
    }

    @Test
    fun `HomeHostView adds no outer padding of its own`() {
        val host = mainSourceOf("render/HomeHostView.kt")
        assertEquals("HomeHostView sets padding in its override, its forward to the page and the panel's top inset", 3, setPaddingCallsIn(host))
        assertTrue("the forward is to the page", withoutComments(host).contains(PAGE_FORWARD))
        assertTrue("the panel's top inset is the one panel call", withoutComments(host).contains(PANEL_INSET))
    }

    @Test
    fun `the no-outer-padding rule fires on a gutter added back to HomeHostView`() {
        val host = mainSourceOf("render/HomeHostView.kt")
        // M4b: the gutter applied per-screen again, a fourth call
        val withGutter = editOnce(
            host,
            "        draw(merged(null))\n",
            "        setPadding(theme.metrics.gutterDp, theme.metrics.gutterDp, theme.metrics.gutterDp, theme.metrics.gutterDp)\n        draw(merged(null))\n",
        )
        assertFalse("M4b must fire: a fourth padding call", outerPaddingHolds(withGutter))
        // M4c: the panel's inset changed to an outer padding of its own
        val withPadding = editOnce(host, PANEL_INSET, "panel.setPadding(16, panelTop, 16, 0)")
        assertFalse("M4c must fire: the panel call changed", outerPaddingHolds(withPadding))
    }

    /** Whether [text] keeps the padding rule: exactly three calls, and the forward and the panel inset among them. */
    private fun outerPaddingHolds(text: String): Boolean {
        val code = withoutComments(text)
        return setPaddingCallsIn(text) == 3 && code.contains(PAGE_FORWARD) && code.contains(PANEL_INSET)
    }

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun editOnce(text: String, old: String, new: String): String {
        assertEquals("the text to change is not in the source exactly once: $old", 1, Regex(Regex.escape(old)).findAll(text).count())
        return text.replace(old, new)
    }

    /** Whether [text] reads a palette slot in its code, not only in an import or a comment. */
    private fun paletteReadIn(text: String): Boolean = withoutComments(text).contains("PaletteSlot.")

    /** The calls to setPadding in [text], with its comments taken out. */
    private fun setPaddingCallsIn(text: String): Int = Regex("""setPadding\(""").findAll(withoutComments(text)).count()

    private fun colourLinesIn(text: String): List<String> =
        text.lines().mapIndexedNotNull { number, line ->
            colourIn(line)?.let { shape -> "${number + 1}: $shape" }
        }

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

    private fun distancesIn(text: String): List<String> =
        NUMBER.findAll(withoutCommentsAndStrings(text))
            .map { it.groupValues[1] }
            .filter { it != "0" && it != "1" }
            .toList()

    private fun distanceNamesIn(text: String): List<String> =
        VALUE_DECLARATION.findAll(withoutCommentsAndStrings(text))
            .map { it.groupValues[1] }
            .filter { DISTANCE_NAME.containsMatchIn(it) }
            .toList()

    private companion object {
        const val PAGE_FORWARD = "page?.setPadding(left, top, right, bottom)"
        const val PANEL_INSET = "panel.setPadding(0, panelTop, 0, 0)"
        const val GLOW_READ = "theme.color(PaletteSlot.PRIMARY).argb"
        val HEX_STRING = Regex("""(?i)["']#(?:[0-9a-f]{8}|[0-9a-f]{6})["']""")
        val HEX_NUMBER = Regex("""\b0[xX](?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6})\b""")
        val LARGE_DECIMAL = Regex("""(?<![\w.$])-?[1-9][0-9]{6,}(?![\w.$])""")
        val FRAMEWORK_COLOUR = Regex("""\bandroid\.graphics\.(?:Color|color)\b""")
        const val FRAMEWORK_COLOUR_SHAPE = "the framework colour type"
        val NUMBER = Regex("""(?<![\w.$])(\d[\d_]*(?:\.\d+)?)[fFlLuU]*""")
        val DISTANCE_NAME = Regex("""(?i)(dp|px|pad|padding|margin|inset|gap|spacing|space)""")
        val VALUE_DECLARATION = Regex("""\b(?:const\s+)?(?:val|var)\s+([A-Za-z_]\w*)""")
    }
}
