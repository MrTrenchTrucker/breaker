package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TokenColor
import dev.breaker.shared.tokens.TruckingPalette
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure-logic parts of the MIC_BUSY face that can be tested on the JVM without Android:
 * the ring colour, the shape, the tap routing, the pulse parameterization and the slash glyph.
 *
 * MIC_BUSY is the state where the microphone is not available. The tile shows a collapsed square
 * with a danger-coloured ring and a slash across the microphone head. A tap on the microphone is
 * passed to the app like a tap in IDLE.
 */
class MicBusyFaceTest {

    private val modes = listOf(ThemeMode.LIGHT, ThemeMode.DARK)

    /** A palette whose eleven colours all differ, so a look that takes the wrong field cannot hide behind equal values. */
    private val distinct = TruckingPalette(
        bg = TokenColor(0xFF000001.toInt()),
        surface = TokenColor(0xFF000002.toInt()),
        text = TokenColor(0xFF000003.toInt()),
        textMuted = TokenColor(0xFF000004.toInt()),
        primary = TokenColor(0xFF000005.toInt()),
        primaryHover = TokenColor(0xFF000006.toInt()),
        accent = TokenColor(0xFF000007.toInt()),
        danger = TokenColor(0xFF000008.toInt()),
        trim = TokenColor(0xFF000009.toInt()),
        sent = TokenColor(0xFF00000A.toInt()),
        warning = TokenColor(0xFF00000B.toInt()),
    )

    // ---- TileStyle (ring colour) ----

    /** A failure means the MIC_BUSY ring does not use the danger colour. */
    @Test
    fun `MIC_BUSY ring uses the danger colour`() {
        for (mode in modes) {
            val palette = TruckingTokens.palette(mode)
            val look = TileStyle.look(TileState.MIC_BUSY, palette)
            assertEquals(
                "overlay: $mode MIC_BUSY ring expected the danger colour",
                palette.danger.argb,
                look.ring,
            )
        }
    }

    /** A failure means the MIC_BUSY look uses wrong palette fields for non-ring colours. */
    @Test
    fun `MIC_BUSY other colours match the non-recording states`() {
        for (mode in modes) {
            val palette = TruckingTokens.palette(mode)
            val look = TileStyle.look(TileState.MIC_BUSY, palette)
            assertEquals("overlay: $mode MIC_BUSY background expected surface", palette.surface.argb, look.background)
            assertEquals("overlay: $mode MIC_BUSY glyph expected primary", palette.primary.argb, look.glyph)
            assertEquals("overlay: $mode MIC_BUSY glyphOutline expected trim", palette.trim.argb, look.glyphOutline)
            assertEquals("overlay: $mode MIC_BUSY litSegment expected primary", palette.primary.argb, look.litSegment)
            assertEquals("overlay: $mode MIC_BUSY unlitSegment expected bg", palette.bg.argb, look.unlitSegment)
            assertEquals("overlay: $mode MIC_BUSY control expected text", palette.text.argb, look.control)
        }
    }

    /** A failure means the MIC_BUSY look differs from ARMED in more than the ring. */
    @Test
    fun `MIC_BUSY look differs from ARMED only in the ring`() {
        for (mode in modes) {
            val palette = TruckingTokens.palette(mode)
            val busy = TileStyle.look(TileState.MIC_BUSY, palette)
            val armed = TileStyle.look(TileState.ARMED, palette)
            assertEquals("overlay: $mode ARMED ring expected primary", palette.primary.argb, armed.ring)
            assertEquals("overlay: $mode MIC_BUSY ring expected danger", palette.danger.argb, busy.ring)
            assertEquals("overlay: $mode MIC_BUSY background expected same as ARMED", armed.background, busy.background)
            assertEquals("overlay: $mode MIC_BUSY glyph expected same as ARMED", armed.glyph, busy.glyph)
            assertEquals("overlay: $mode MIC_BUSY glyphOutline expected same as ARMED", armed.glyphOutline, busy.glyphOutline)
            assertEquals("overlay: $mode MIC_BUSY litSegment expected same as ARMED", armed.litSegment, busy.litSegment)
            assertEquals("overlay: $mode MIC_BUSY unlitSegment expected same as ARMED", armed.unlitSegment, busy.unlitSegment)
            assertEquals("overlay: $mode MIC_BUSY control expected same as ARMED", armed.control, busy.control)
        }
    }

    // ---- TileModel (shape) ----

    /** A failure means the MIC_BUSY shape is not COLLAPSED. */
    @Test
    fun `MIC_BUSY shape is COLLAPSED`() {
        val model = TileModel()
        model.setState(TileState.MIC_BUSY)
        assertEquals(
            "overlay: MIC_BUSY shape expected COLLAPSED",
            TileShape.COLLAPSED,
            model.shape,
        )
    }

    /** A failure means setState(MIC_BUSY) clears a notice that was never set. */
    @Test
    fun `MIC_BUSY does not clear notice on setState if none was set`() {
        val model = TileModel()
        model.setState(TileState.MIC_BUSY)
        assertEquals(
            "overlay: MIC_BUSY with no notice expected notice to stay null",
            null,
            model.notice,
        )
    }

    /** A failure means MIC_BUSY shows lit segments (it is not recording). */
    @Test
    fun `MIC_BUSY level is 0`() {
        val model = TileModel()
        model.setState(TileState.MIC_BUSY)
        val face = model.face(ThemeMode.LIGHT)
        assertEquals(
            "overlay: MIC_BUSY face expected 0 lit segments",
            0,
            face.litSegments,
        )
    }

    // ---- TileRouting (tap) ----

    /** A failure means a tap on the microphone while MIC_BUSY does not route to TAP. */
    @Test
    fun `MIC_BUSY mic tap routes to TAP`() {
        assertEquals(
            "overlay: MIC_BUSY mic tap expected TAP",
            TileAction.TAP,
            TileRouting.action(TileState.MIC_BUSY, TileZone.MIC),
        )
    }

    /** A failure means a tap on a non-mic zone while MIC_BUSY does not route to NONE. */
    @Test
    fun `MIC_BUSY non-mic tap routes to NONE`() {
        assertEquals(
            "overlay: MIC_BUSY non-mic tap expected NONE",
            TileAction.NONE,
            TileRouting.action(TileState.MIC_BUSY, TileZone.NONE),
        )
    }

    /** A failure means MIC_BUSY tap routing differs from IDLE for any zone. */
    @Test
    fun `MIC_BUSY tap routing matches IDLE`() {
        for (zone in TileZone.values()) {
            assertEquals(
                "overlay: MIC_BUSY tap on $zone expected same action as IDLE",
                TileRouting.action(TileState.IDLE, zone),
                TileRouting.action(TileState.MIC_BUSY, zone),
            )
        }
    }

    // ---- ArmedPulse (parameterization) ----

    /** A failure means armedPulseAlpha does not use the custom alphaMin. */
    @Test
    fun `armedPulseAlpha with custom alphaMin`() {
        assertEquals(
            "overlay: armedPulseAlpha(0f, 0.6f) expected 0.6f",
            0.6f,
            armedPulseAlpha(0f, 0.6f),
            0.001f,
        )
        assertEquals(
            "overlay: armedPulseAlpha(0.5f, 0.6f) expected 1.0f",
            1.0f,
            armedPulseAlpha(0.5f, 0.6f),
            0.001f,
        )
    }

    /** A failure means the default alphaMin is not PULSE_ALPHA_MIN (0.25). */
    @Test
    fun `armedPulseAlpha default alphaMin unchanged`() {
        assertEquals(
            "overlay: armedPulseAlpha(0f) expected PULSE_ALPHA_MIN (0.25)",
            PULSE_ALPHA_MIN,
            armedPulseAlpha(0f),
            0.001f,
        )
    }

    // ---- Pulse constants ----

    /** A failure means BUSY_PULSE_PERIOD_MS is not slower than PULSE_PERIOD_MS. */
    @Test
    fun `BUSY_PULSE_PERIOD_MS is slower than PULSE_PERIOD_MS`() {
        assertTrue(
            "overlay: BUSY_PULSE_PERIOD_MS ($BUSY_PULSE_PERIOD_MS) expected > PULSE_PERIOD_MS ($PULSE_PERIOD_MS)",
            BUSY_PULSE_PERIOD_MS > PULSE_PERIOD_MS,
        )
    }

    /** A failure means BUSY_PULSE_ALPHA_MIN is not higher than PULSE_ALPHA_MIN. */
    @Test
    fun `BUSY_PULSE_ALPHA_MIN is higher than PULSE_ALPHA_MIN`() {
        assertTrue(
            "overlay: BUSY_PULSE_ALPHA_MIN ($BUSY_PULSE_ALPHA_MIN) expected > PULSE_ALPHA_MIN ($PULSE_ALPHA_MIN)",
            BUSY_PULSE_ALPHA_MIN > PULSE_ALPHA_MIN,
        )
    }

    // ---- TileGlyph (slash rect) ----

    /** A failure means the glyph has no SLASH-role rectangle. */
    @Test
    fun `TileGlyph rects contains a SLASH-role rect`() {
        assertTrue(
            "overlay: TileGlyph.rects expected at least one SLASH-role rect",
            TileGlyph.rects.any { it.role == GlyphRole.SLASH },
        )
    }

    /** A failure means the SLASH rect lies outside the unit square. */
    @Test
    fun `SLASH rect lies inside the unit square`() {
        val slash = TileGlyph.rects.filter { it.role == GlyphRole.SLASH }
        assertTrue("overlay: expected at least one SLASH rect", slash.isNotEmpty())
        for ((index, rect) in slash.withIndex()) {
            assertTrue("overlay: SLASH rect $index left expected >= 0: $rect", rect.left >= 0f)
            assertTrue("overlay: SLASH rect $index top expected >= 0: $rect", rect.top >= 0f)
            assertTrue("overlay: SLASH rect $index right expected <= 1: $rect", rect.right <= 1f)
            assertTrue("overlay: SLASH rect $index bottom expected <= 1: $rect", rect.bottom <= 1f)
        }
    }

    // ---- No text on the tile ----

    /**
     * A failure means the tile draws text in the MIC_BUSY face, which the spec forbids: no text in any
     * MIC_BUSY state. The notice is the only text the view draws and it is drawn only from the NOTICE
     * branch of the shape switch; MIC_BUSY is a COLLAPSED shape, so its branch must draw nothing.
     */
    @Test
    fun `MIC_BUSY draws no text`() {
        val source = ModuleFiles.mainTexts()["TileView.kt"]
        assertTrue("overlay: TileView.kt must be found among the main sources", source != null)
        val code = SourceText.code(source!!)
        assertTrue(
            "overlay: the COLLAPSED branch must be a branch that draws nothing",
            Regex("""TileShape\.COLLAPSED\s*->\s*Unit\b""").containsMatchIn(code),
        )
        val collapsed = Regex("""TileShape\.COLLAPSED\s*->\s*([^\n]*)""").find(code)
        assertTrue(
            "overlay: the COLLAPSED branch must not call drawNotice, got '${collapsed?.groupValues?.get(1)}'",
            collapsed != null && !collapsed.groupValues[1].contains("drawNotice"),
        )
    }

    /** A failure means the busy pulse is built with the armed pulse's period or alpha floor, so it would not be slower and shallower. */
    @Test
    fun `busyPulse carries the busy period and the busy alpha floor`() {
        val pulse = busyPulse { }
        assertEquals("overlay: the busy pulse period expected the busy constant", BUSY_PULSE_PERIOD_MS, pulse.periodMs)
        assertEquals("overlay: the busy pulse alpha floor expected the busy constant", BUSY_PULSE_ALPHA_MIN, pulse.alphaMin, 0f)
    }
}
