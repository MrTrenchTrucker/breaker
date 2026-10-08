package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TokenColor
import dev.breaker.shared.tokens.TruckingPalette
import dev.breaker.shared.tokens.TruckingTokens
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The colours of each tile state, in both themes: each one is the named palette field, every colour is
 * opaque, the lit and unlit segments differ, and only the ring varies with the state. Also that a face
 * counts every one of its fields when two faces are compared.
 */
class TileStyleTest {

    private val modes = listOf(ThemeMode.LIGHT, ThemeMode.DARK)

    /** A palette whose eleven colours all differ, so a look that takes the wrong field (sent for primary, say) cannot hide behind equal values. */
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

    private fun ring(state: TileState, p: TruckingPalette): Int = when (state) {
        TileState.IDLE -> p.trim.argb
        TileState.ARMED -> p.primary.argb
        TileState.RECORDING -> p.primary.argb
        TileState.SENDING -> p.primary.argb
        TileState.FAILED -> p.danger.argb
        TileState.SENT -> p.sent.argb
        TileState.SENT_LOCAL -> p.warning.argb
    }

    private fun fields(look: TileLook): List<Int> =
        listOf(look.background, look.glyph, look.glyphOutline, look.ring, look.litSegment, look.unlitSegment, look.control)

    /** A failure means a state's colour is not the palette field the tile is meant to use, in the light theme, the dark theme or a palette of eleven different colours. */
    @Test
    fun `every state's look is made of the named palette fields`() {
        val palettes = modes.map { it.name to TruckingTokens.palette(it) } + ("DISTINCT" to distinct)
        for ((name, p) in palettes) {
            for (state in TileState.values()) {
                val look = TileStyle.look(state, p)
                val what = "$name $state"
                assertEquals("overlay: $what background expected the surface colour", p.surface.argb, look.background)
                assertEquals("overlay: $what glyph expected the primary colour", p.primary.argb, look.glyph)
                assertEquals("overlay: $what glyph outline expected the trim colour", p.trim.argb, look.glyphOutline)
                assertEquals("overlay: $what ring", ring(state, p), look.ring)
                assertEquals("overlay: $what lit segment expected the primary colour", p.primary.argb, look.litSegment)
                assertEquals("overlay: $what unlit segment expected the background colour", p.bg.argb, look.unlitSegment)
                assertEquals("overlay: $what control expected the text colour", p.text.argb, look.control)
            }
        }
    }

    /** A failure means the ring colours are not as documented: plain trim idle, green armed, recording and sending, red after a failure. */
    @Test
    fun `ring colours against the token values written out`() {
        val light = TruckingTokens.palette(ThemeMode.LIGHT)
        val dark = TruckingTokens.palette(ThemeMode.DARK)
        assertEquals("overlay: light idle ring expected 0xFF000000", 0xFF000000.toInt(), TileStyle.look(TileState.IDLE, light).ring)
        assertEquals("overlay: light armed ring expected 0xFF1E7A46", 0xFF1E7A46.toInt(), TileStyle.look(TileState.ARMED, light).ring)
        assertEquals("overlay: light recording ring expected 0xFF1E7A46", 0xFF1E7A46.toInt(), TileStyle.look(TileState.RECORDING, light).ring)
        assertEquals("overlay: light sending ring expected 0xFF1E7A46", 0xFF1E7A46.toInt(), TileStyle.look(TileState.SENDING, light).ring)
        assertEquals("overlay: light failed ring expected 0xFFC0392B", 0xFFC0392B.toInt(), TileStyle.look(TileState.FAILED, light).ring)
        assertEquals("overlay: dark idle ring expected 0xFFFFFFFF", 0xFFFFFFFF.toInt(), TileStyle.look(TileState.IDLE, dark).ring)
        assertEquals("overlay: dark armed ring expected 0xFF2E9E5B", 0xFF2E9E5B.toInt(), TileStyle.look(TileState.ARMED, dark).ring)
        assertEquals("overlay: dark recording ring expected 0xFF2E9E5B", 0xFF2E9E5B.toInt(), TileStyle.look(TileState.RECORDING, dark).ring)
        assertEquals("overlay: dark sending ring expected 0xFF2E9E5B", 0xFF2E9E5B.toInt(), TileStyle.look(TileState.SENDING, dark).ring)
        assertEquals("overlay: dark failed ring expected 0xFFE74C3C", 0xFFE74C3C.toInt(), TileStyle.look(TileState.FAILED, dark).ring)
    }

    /** A failure means the other colours are not the documented token values in the light or dark theme. */
    @Test
    fun `the other colours against the token values written out`() {
        val light = TileStyle.look(TileState.ARMED, TruckingTokens.palette(ThemeMode.LIGHT))
        assertEquals("overlay: light background expected 0xFFF4F6F4", 0xFFF4F6F4.toInt(), light.background)
        assertEquals("overlay: light glyph expected 0xFF1E7A46", 0xFF1E7A46.toInt(), light.glyph)
        assertEquals("overlay: light glyph outline expected 0xFF000000", 0xFF000000.toInt(), light.glyphOutline)
        assertEquals("overlay: light lit segment expected 0xFF1E7A46", 0xFF1E7A46.toInt(), light.litSegment)
        assertEquals("overlay: light unlit segment expected 0xFFFFFFFF", 0xFFFFFFFF.toInt(), light.unlitSegment)
        assertEquals("overlay: light control expected 0xFF111417", 0xFF111417.toInt(), light.control)
        val dark = TileStyle.look(TileState.ARMED, TruckingTokens.palette(ThemeMode.DARK))
        assertEquals("overlay: dark background expected 0xFF161B1E", 0xFF161B1E.toInt(), dark.background)
        assertEquals("overlay: dark glyph expected 0xFF2E9E5B", 0xFF2E9E5B.toInt(), dark.glyph)
        assertEquals("overlay: dark glyph outline expected 0xFFFFFFFF", 0xFFFFFFFF.toInt(), dark.glyphOutline)
        assertEquals("overlay: dark lit segment expected 0xFF2E9E5B", 0xFF2E9E5B.toInt(), dark.litSegment)
        assertEquals("overlay: dark unlit segment expected 0xFF0E1113", 0xFF0E1113.toInt(), dark.unlitSegment)
        assertEquals("overlay: dark control expected 0xFFF2F5F2", 0xFFF2F5F2.toInt(), dark.control)
    }

    /** A failure means a colour has a see-through alpha byte, which the view would draw faintly. */
    @Test
    fun `every colour is opaque`() {
        for (mode in modes) for (state in TileState.values()) {
            fields(TileStyle.look(state, TruckingTokens.palette(mode))).forEachIndexed { index, argb ->
                assertEquals("overlay: $mode $state colour number $index expected an opaque alpha byte", 0xFF, argb ushr 24)
            }
        }
    }

    /** A failure means a lit segment cannot be told from an unlit one, or the failed ring cannot be told from the others. */
    @Test
    fun `lit and unlit segments differ and a failure has its own ring`() {
        for (mode in modes) {
            val p = TruckingTokens.palette(mode)
            for (state in TileState.values()) {
                val look = TileStyle.look(state, p)
                assertNotEquals("overlay: $mode $state lit and unlit segments expected different colours", look.litSegment, look.unlitSegment)
            }
            val failed = TileStyle.look(TileState.FAILED, p).ring
            for (state in TileState.values().filter { it != TileState.FAILED }) {
                assertNotEquals("overlay: $mode the failed ring expected to differ from the $state ring", failed, TileStyle.look(state, p).ring)
            }
            assertNotEquals(
                "overlay: $mode the idle ring expected to differ from the armed ring",
                TileStyle.look(TileState.IDLE, p).ring,
                TileStyle.look(TileState.ARMED, p).ring,
            )
        }
    }

    /** A failure means something other than the ring varies with the state, or the look depends on anything but the state and palette. */
    @Test
    fun `only the ring depends on the state`() {
        for (mode in modes) {
            val p = TruckingTokens.palette(mode)
            val base = TileStyle.look(TileState.IDLE, p)
            for (state in TileState.values()) {
                assertEquals("overlay: $mode $state expected the same look as idle apart from the ring", base.copy(ring = ring(state, p)), TileStyle.look(state, p))
            }
            assertEquals("overlay: $mode the same inputs expected the same look", TileStyle.look(TileState.SENDING, p), TileStyle.look(TileState.SENDING, p))
        }
        assertNotEquals(
            "overlay: the light and dark looks of one state expected to differ",
            TileStyle.look(TileState.ARMED, TruckingTokens.palette(ThemeMode.LIGHT)),
            TileStyle.look(TileState.ARMED, TruckingTokens.palette(ThemeMode.DARK)),
        )
    }

    /** A failure means the look gained or lost a colour, or one of its seven colours is not the palette field the tile is meant to use. */
    @Test
    fun `the look is exactly seven colours in the expected order from distinct palette fields`() {
        val stored = TileLook::class.java.declaredFields.count { !Modifier.isStatic(it.modifiers) }
        assertEquals("overlay: the look should hold exactly seven colours", 7, stored)
        val expected = listOf(
            distinct.surface.argb,
            distinct.primary.argb,
            distinct.trim.argb,
            distinct.trim.argb,
            distinct.primary.argb,
            distinct.bg.argb,
            distinct.text.argb,
        )
        assertEquals(
            "overlay: the idle look should be surface, primary, trim, trim, primary, background, text",
            expected,
            fields(TileStyle.look(TileState.IDLE, distinct)),
        )
    }

    private fun base(): TileLook = TileStyle.look(TileState.IDLE, TruckingTokens.palette(ThemeMode.LIGHT))

    /** A failure means two faces that differ in one field compare equal, so a redraw could be skipped when something visible changed. */
    @Test
    fun `a face compares every one of its fields`() {
        val look = base()
        val face = TileFace(TileState.RECORDING, TileShape.RECORDING, 5, 12, look, "n", "d")
        assertEquals("overlay: a face copied unchanged expected equal", face, face.copy())
        val changed = listOf(
            "state" to face.copy(state = TileState.SENDING),
            "shape" to face.copy(shape = TileShape.NOTICE),
            "lit segments" to face.copy(litSegments = 6),
            "segments" to face.copy(segments = 16),
            "look" to face.copy(look = look.copy(ring = look.ring + 1)),
            "notice" to face.copy(notice = "m"),
            "no notice" to face.copy(notice = null),
            "description" to face.copy(description = "e"),
            "no description" to face.copy(description = null),
        )
        changed.forEach { (field, other) ->
            assertNotEquals("overlay: a face with a different $field expected not equal", face, other)
        }
    }
}
