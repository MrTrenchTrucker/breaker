package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TokenColor
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outline colour of each tile state, in both themes, as a literal table. Each state's ring is checked
 * through [ringColor] and through the look the view draws with, so the two cannot drift apart. The view must
 * take its outline band, its glow and the busy sign from the look's ring, never from a fixed token.
 */
class TileOutlineColourTest {

    /** Asserts the ring of [state] is [light] in the light theme and [dark] in the dark theme, by both functions. */
    private fun assertRing(state: TileState, light: Int, dark: Int) {
        for ((mode, expected) in listOf(ThemeMode.LIGHT to light, ThemeMode.DARK to dark)) {
            val palette = TruckingTokens.palette(mode)
            assertEquals("overlay: $mode $state ringColor expected the literal ring colour", expected, ringColor(state, palette))
            assertEquals("overlay: $mode $state look ring expected the literal ring colour", expected, TileStyle.look(state, palette).ring)
        }
    }

    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    /** A failure means the idle outline is not the trim colour: black in light, white in dark. */
    @Test
    fun `the idle outline is the trim colour in both themes`() =
        assertRing(TileState.IDLE, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())

    /** A failure means the armed outline is not the primary colour in both themes. */
    @Test
    fun `the armed outline is the primary colour in both themes`() =
        assertRing(TileState.ARMED, 0xFF1E7A46.toInt(), 0xFF2E9E5B.toInt())

    /** A failure means the recording outline is not the primary colour in both themes. */
    @Test
    fun `the recording outline is the primary colour in both themes`() =
        assertRing(TileState.RECORDING, 0xFF1E7A46.toInt(), 0xFF2E9E5B.toInt())

    /** A failure means the sending outline is not the warning colour in both themes. */
    @Test
    fun `the sending outline is the warning colour in both themes`() =
        assertRing(TileState.SENDING, 0xFFA55713.toInt(), 0xFFF39C12.toInt())

    /** A failure means the local-sent outline is not the warning colour in both themes. */
    @Test
    fun `the local sent outline is the warning colour in both themes`() =
        assertRing(TileState.SENT_LOCAL, 0xFFA55713.toInt(), 0xFFF39C12.toInt())

    /** A failure means the failed outline is not the danger colour in both themes. */
    @Test
    fun `the failed outline is the danger colour in both themes`() =
        assertRing(TileState.FAILED, 0xFFC0392B.toInt(), 0xFFE74C3C.toInt())

    /** A failure means the busy outline is not the danger colour in both themes. */
    @Test
    fun `the busy outline is the danger colour in both themes`() =
        assertRing(TileState.MIC_BUSY, 0xFFC0392B.toInt(), 0xFFE74C3C.toInt())

    /** A failure means the sent outline is not the sent colour in both themes. */
    @Test
    fun `the sent outline is the sent colour in both themes`() =
        assertRing(TileState.SENT, 0xFF1E7A46.toInt(), 0xFF2E9E5B.toInt())

    /** A failure means the sent outline reads primary instead of the palette's sent field, which differs from primary here. */
    @Test
    fun `the sent outline follows the sent field and not the primary field`() {
        val palette = TruckingTokens.LIGHT.copy(sent = TokenColor(0xFF0A0B0C.toInt()))
        assertNotEquals("overlay: the test palette expected sent to differ from primary", palette.primary.argb, palette.sent.argb)
        assertEquals("overlay: the sent ring expected the sent field", palette.sent.argb, ringColor(TileState.SENT, palette))
        assertEquals("overlay: the sent look ring expected the sent field", palette.sent.argb, TileStyle.look(TileState.SENT, palette).ring)
    }

    /** The problems with where the outline comes from: the band and the glow in the view, and the busy sign in the art drawing, all take look.ring. */
    private fun ringSourceProblems(viewSource: String, artSource: String): List<String> {
        val view = SourceText.code(viewSource)
        val art = SourceText.code(artSource)
        return listOfNotNull(
            "TileView does not stroke the outline band with look.ring"
                .takeUnless { Regex("""\bdrawRing\s*\(\s*canvas\s*,\s*mic\s*,\s*look\.ring\s*\)""").containsMatchIn(view) },
            "TileView does not glow with look.ring"
                .takeUnless { Regex("""\bartDraw\.drawGlow\s*\(\s*canvas\s*,\s*mic\s*,\s*paint\s*,\s*look\.ring\s*,""").containsMatchIn(view) },
            "TileArtDraw does not draw the busy sign with the look field"
                .takeUnless { Regex("""\bdrawBusy\s*\(\s*canvas\s*,\s*paint\s*,\s*look\s*,\s*pulseAlpha\s*\)""").containsMatchIn(art) }
        )
    }

    private val viewSample = "override fun onDraw(canvas: Canvas) {\n" +
        "artDraw.drawGlow(canvas, mic, paint, look.ring, pulseAlpha, density)\n" +
        "drawRing(canvas, mic, look.ring)\n" +
        "}\n"

    private val artSample = "if (face.state == TileState.MIC_BUSY) drawBusy(canvas, paint, look, pulseAlpha)\n"

    /** A failure means the band, the glow or the busy sign takes a colour other than the look's ring. */
    @Test
    fun `the outline band the glow and the busy sign take the look ring`() {
        assertEquals("overlay: control: the sample must be quiet", emptyList<String>(), ringSourceProblems(viewSample, artSample))
        assertTrue(
            "overlay: control: a band in a fixed token must be reported",
            ringSourceProblems(edit(viewSample, "drawRing(canvas, mic, look.ring)", "drawRing(canvas, mic, palette.trim.argb)"), artSample)
                .any { it.contains("does not stroke the outline band") },
        )
        assertTrue(
            "overlay: control: a glow in the glyph colour must be reported",
            ringSourceProblems(edit(viewSample, "paint, look.ring, pulseAlpha", "paint, look.glyph, pulseAlpha"), artSample)
                .any { it.contains("does not glow with look.ring") },
        )
        assertTrue(
            "overlay: control: a busy sign in a fixed token must be reported",
            ringSourceProblems(viewSample, edit(artSample, "drawBusy(canvas, paint, look, pulseAlpha)", "drawBusy(canvas, paint, palette.danger.argb, pulseAlpha)"))
                .any { it.contains("does not draw the busy sign") },
        )
        val texts = ModuleFiles.mainTexts()
        assertEquals(
            "overlay: the outline band, the glow and the busy sign must take the look's ring",
            emptyList<String>(),
            ringSourceProblems(texts.getValue("TileView.kt"), texts.getValue("TileArtDraw.kt")),
        )
    }
}
