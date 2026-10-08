package dev.breaker.dictation.overlay

/**
 * What the floating tile shows. The app pushes the state; the tile never alters it by itself,
 * not even on a tap.
 *
 * [IDLE]: dictation is switched off, or not ready. The tile shows the microphone and a plain ring.
 *
 * [ARMED]: dictation is switched on and ready. A tap on the microphone starts recording. The ring
 * is steady, not pulsing.
 *
 * [RECORDING]: the microphone is open. The tile widens to show a cancel button, the level meter
 * above the microphone, and a send button.
 *
 * [SENDING]: recording is over and the text is on its way. The tile ignores taps.
 *
 * [FAILED]: the last try did not work. The ring turns to the failure colour, and a tap on the
 * microphone is passed to the app like a tap in [IDLE].
 *
 * [SENT] and [SENT_LOCAL] are the two finished outcomes, after [SENDING]; a tap on either is passed
 * to the app like a tap in [IDLE].
 */
enum class TileState {
    IDLE,
    ARMED,
    RECORDING,
    SENDING,
    FAILED,

    /** The text was committed, and the ring is the palette's sent colour (green). */
    SENT,

    /** The text was committed on the phone after the server path failed, and the ring is the palette's warning colour (orange). */
    SENT_LOCAL,
}
