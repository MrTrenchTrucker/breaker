package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState

/**
 * The one place that decides what the tile shows.
 *
 * Switched off always shows [TileState.IDLE]. While the text of a take is on its way ([sendPushed])
 * the tile shows [TileState.SENDING] whatever the runner reports, because the runner keeps saying it
 * is recording until the transcription is done. A take that failed ([lastFailed]) shows
 * [TileState.FAILED] because the runner forgets failures.
 *
 * After a send the tile shows [TileState.SENT] ([lastCommit] committed or copied) until the next
 * begin, cancel, tap or switch-off. A text that only reached the clipboard also shows SENT, because
 * the clipboard confirmation is what tells the user. A commit that failed shows [TileState.FAILED].
 * With no commit to show, the runner's [session] decides.
 */
fun tileStateFor(
    armed: Boolean,
    session: DictationState,
    sendPushed: Boolean,
    lastFailed: Boolean,
    lastCommit: CommitOutcome?,
    micBusy: Boolean = false,
): TileState {
    if (!armed) return TileState.IDLE
    if (micBusy) return TileState.MIC_BUSY
    if (sendPushed) return TileState.SENDING
    if (lastFailed) return TileState.FAILED
    return when (lastCommit) {
        CommitOutcome.COMMITTED, CommitOutcome.COPIED -> TileState.SENT
        CommitOutcome.FAILED -> TileState.FAILED
        null -> when (session) {
            DictationState.RECORDING, DictationState.TRANSCRIBING -> TileState.RECORDING
            DictationState.SENDING -> TileState.SENDING
            DictationState.ERROR -> TileState.FAILED
            DictationState.IDLE, DictationState.ARMED -> TileState.ARMED
        }
    }
}
