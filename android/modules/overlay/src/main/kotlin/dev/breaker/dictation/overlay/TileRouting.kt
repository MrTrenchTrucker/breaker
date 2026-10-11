package dev.breaker.dictation.overlay

/**
 * What a tap does: nothing, the plain tap the app answers, begin recording, cancel, or send.
 */
internal enum class TileAction { NONE, TAP, BEGIN, CANCEL, SEND, DISMISS }

/** Decides which [TileAction] a tap on a [TileZone] means in a [TileState]. */
internal object TileRouting {

    /**
     * The action for a tap on [zone] while the tile is in [state].
     *
     * Idle, failed and the two finished outcomes (sent and sent on the phone): the microphone is a plain
     * tap, the strip dismisses a notice. Armed: the microphone begins recording, the strip dismisses.
     * Recording: the microphone and the send button send, the cancel button cancels. Sending: nothing.
     * Any other pair does nothing.
     */
    fun action(state: TileState, zone: TileZone): TileAction = when (state) {
        TileState.IDLE, TileState.FAILED, TileState.SENT, TileState.SENT_LOCAL, TileState.MIC_BUSY ->
            when (zone) {
                TileZone.MIC -> TileAction.TAP
                TileZone.STRIP -> TileAction.DISMISS
                else -> TileAction.NONE
            }
        TileState.ARMED -> when (zone) {
            TileZone.MIC -> TileAction.BEGIN
            TileZone.STRIP -> TileAction.DISMISS
            else -> TileAction.NONE
        }
        TileState.RECORDING -> when (zone) {
            TileZone.MIC, TileZone.SEND -> TileAction.SEND
            TileZone.CANCEL -> TileAction.CANCEL
            TileZone.STRIP, TileZone.NONE -> TileAction.NONE
        }
        TileState.SENDING -> TileAction.NONE
    }
}
