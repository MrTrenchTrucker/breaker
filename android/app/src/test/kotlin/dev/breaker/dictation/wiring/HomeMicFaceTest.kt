package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home mic's face for a push, pinned per clause boundary. The order is the ruling: armed-off wins,
 * else the shared busy flag, else the sticky sending flag, else RECORDING, else ARMED. Two of these kill
 * an order swap — busy moved above RECORDING, and the off-clause moved below busy.
 */
internal class HomeMicFaceTest {

    /** Armed-off wins over busy and over RECORDING: the face is IDLE. */
    @Test
    fun `armed off wins over busy and recording showing idle`() {
        assertEquals("app: face: armed off must win over busy and recording", TileState.IDLE, homeMicFace(false, true, false, DictationState.RECORDING))
    }

    /** Armed-off wins over busy with an idle state: the face is IDLE. */
    @Test
    fun `armed off wins over busy with an idle state showing idle`() {
        assertEquals("app: face: armed off must win over busy", TileState.IDLE, homeMicFace(false, true, false, DictationState.IDLE))
    }

    /** Armed-off with no busy and an idle state: the face is IDLE. */
    @Test
    fun `armed off with no busy and an idle state shows idle`() {
        assertEquals("app: face: armed off with no busy must be idle", TileState.IDLE, homeMicFace(false, false, false, DictationState.IDLE))
    }

    /** Busy beats RECORDING (kills the swap-of-busy-and-RECORDING mutant): the face is MIC_BUSY. */
    @Test
    fun `busy beats recording showing mic busy`() {
        assertEquals("app: face: busy must beat recording", TileState.MIC_BUSY, homeMicFace(true, true, false, DictationState.RECORDING))
    }

    /** Busy beats the plain armed face: the face is MIC_BUSY. */
    @Test
    fun `busy beats the plain armed face showing mic busy`() {
        assertEquals("app: face: busy must beat the armed face", TileState.MIC_BUSY, homeMicFace(true, true, false, DictationState.IDLE))
    }

    /** RECORDING when switched on and not busy: the face is RECORDING. */
    @Test
    fun `recording when switched on and not busy shows recording`() {
        assertEquals("app: face: recording when on and not busy", TileState.RECORDING, homeMicFace(true, false, false, DictationState.RECORDING))
    }

    /** ARMED is the rest (kills the off-clause-down / ARMED-up swap): the face is ARMED. */
    @Test
    fun `armed is the rest showing armed`() {
        assertEquals("app: face: armed is the rest", TileState.ARMED, homeMicFace(true, false, false, DictationState.IDLE))
    }

    /** TRANSCRIBING/SENDING/ERROR with the switch on and not busy are ARMED (kills the `state != IDLE -> RECORDING` mutant): the face is ARMED. */
    @Test
    fun `armed on with no busy maps the non recording states to armed`() {
        assertEquals("app: face: armed on transcribing must be armed", TileState.ARMED, homeMicFace(true, false, false, DictationState.TRANSCRIBING))
        assertEquals("app: face: armed on sending must be armed", TileState.ARMED, homeMicFace(true, false, false, DictationState.SENDING))
        assertEquals("app: face: armed on error must be armed", TileState.ARMED, homeMicFace(true, false, false, DictationState.ERROR))
    }

    /** SENDING when switched on, not busy and the take has ended: the amber face (kills a missing sending clause). */
    @Test
    fun `sending shows the amber face when switched on and not busy`() {
        assertEquals("app: face: sending must show the amber face", TileState.SENDING, homeMicFace(true, false, true, DictationState.IDLE))
    }

    /** Busy beats sending (kills the busy/sending order swap): the face is MIC_BUSY. */
    @Test
    fun `busy beats sending showing mic busy`() {
        assertEquals("app: face: busy must beat sending", TileState.MIC_BUSY, homeMicFace(true, true, true, DictationState.IDLE))
    }

    /** Sending beats RECORDING (kills a sending clause placed below RECORDING): the face is SENDING. */
    @Test
    fun `sending beats recording showing the amber face`() {
        assertEquals("app: face: sending must beat recording", TileState.SENDING, homeMicFace(true, false, true, DictationState.RECORDING))
    }

    /** Switch-off beats sending (kills a sending clause placed above the off-clause): the face is IDLE. */
    @Test
    fun `switch off beats sending showing idle`() {
        assertEquals("app: face: switch off must beat sending", TileState.IDLE, homeMicFace(false, false, true, DictationState.IDLE))
    }
}
