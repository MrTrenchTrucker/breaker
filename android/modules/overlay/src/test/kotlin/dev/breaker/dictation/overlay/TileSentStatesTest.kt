package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TokenColor
import dev.breaker.shared.tokens.TruckingPalette
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two finished outcomes, SENT (sent colour, green) and SENT_LOCAL (warning colour, orange): the ring
 * each one is drawn with in both themes, the window shape each one takes, how a tap on each one is routed,
 * and that no state other than idle is drawn with the idle ring.
 */
class TileSentStatesTest {

    /** A palette whose colours all differ, so the sent colour cannot hide behind the primary colour as it does in both real themes. */
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

    private val palettes: List<Pair<String, TruckingPalette>> = listOf(
        "LIGHT" to TruckingTokens.palette(ThemeMode.LIGHT),
        "DARK" to TruckingTokens.palette(ThemeMode.DARK),
        "DISTINCT" to distinct,
    )

    /** A failure means SENT is not drawn with the palette's sent colour in one of the themes. */
    @Test
    fun `the SENT ring is the palette sent colour in both themes`() {
        for ((mode, p) in palettes) {
            assertEquals("overlay: $mode SENT ring expected the sent colour", p.sent.argb, TileStyle.look(TileState.SENT, p).ring)
        }
    }

    /** A failure means SENT_LOCAL is not drawn with the palette's warning colour in one of the themes. */
    @Test
    fun `the SENT_LOCAL ring is the palette warning colour in both themes`() {
        for ((mode, p) in palettes) {
            assertEquals("overlay: $mode SENT_LOCAL ring expected the warning colour", p.warning.argb, TileStyle.look(TileState.SENT_LOCAL, p).ring)
        }
    }

    /** A failure means the committed-on-server ring and the committed-on-phone ring cannot be told apart in one of the themes. */
    @Test
    fun `the SENT and SENT_LOCAL rings differ in both themes`() {
        for ((mode, p) in palettes) {
            assertNotEquals(
                "overlay: $mode the SENT ring expected to differ from the SENT_LOCAL ring",
                TileStyle.look(TileState.SENT, p).ring,
                TileStyle.look(TileState.SENT_LOCAL, p).ring,
            )
        }
    }

    /** A failure means a state other than idle is drawn with the idle ring, so the sent states would look like a tile that is off. */
    @Test
    fun `no state other than idle is drawn with the idle ring`() {
        for ((mode, p) in palettes) {
            val idle = TileStyle.look(TileState.IDLE, p).ring
            for (state in TileState.values()) {
                if (state == TileState.IDLE) continue
                assertNotEquals("overlay: $mode $state ring expected to differ from the idle ring", idle, TileStyle.look(state, p).ring)
            }
        }
    }

    /** A failure means SENT or SENT_LOCAL is given a window shape other than the one a finished failure gets, with or without a notice. */
    @Test
    fun `SENT and SENT_LOCAL take the collapsed or notice shape and never the recording shape`() {
        for (state in listOf(TileState.SENT, TileState.SENT_LOCAL)) {
            assertEquals("overlay: shape for $state with no notice", TileShape.COLLAPSED, shapeOf(state, null))
            assertEquals("overlay: shape for $state with a notice", TileShape.NOTICE, shapeOf(state, "x"))
        }
        assertEquals("overlay: shape for SENT expected what FAILED gives", shapeOf(TileState.FAILED, "x"), shapeOf(TileState.SENT, "x"))
        assertEquals("overlay: shape for SENT_LOCAL expected what FAILED gives", shapeOf(TileState.FAILED, null), shapeOf(TileState.SENT_LOCAL, null))
    }

    /** A failure means a tap on SENT or SENT_LOCAL is routed in a zone differently from a tap on FAILED, the state each one mirrors. */
    @Test
    fun `a tap on SENT and on SENT_LOCAL routes in every zone as a tap on FAILED does`() {
        for (zone in TileZone.values()) {
            val failed = TileRouting.action(TileState.FAILED, zone)
            assertEquals("overlay: a tap on $zone while SENT expected what FAILED gives", failed, TileRouting.action(TileState.SENT, zone))
            assertEquals("overlay: a tap on $zone while SENT_LOCAL expected what FAILED gives", failed, TileRouting.action(TileState.SENT_LOCAL, zone))
        }
        assertTrue("overlay: a tap on the microphone while SENT expected a plain tap", TileRouting.action(TileState.SENT, TileZone.MIC) == TileAction.TAP)
    }
}
