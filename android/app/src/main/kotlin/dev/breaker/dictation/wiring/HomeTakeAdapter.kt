package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState

/**
 * The Home screen's one take path, walked by a tap on the mic. It drives the shared [TakePort]
 * - no second runner, no new port, never [TakePort.send], never a committer.
 *
 * A tap while no take runs answers the stored-off line or begins once. A tap while the Home holds its take
 * finishes it and settles it on the shared [TakeSettle], in one order: the finish and the History
 * save on the background, the reset, then the clipboard copy and the outcome line on the main
 * thread. A settled take ends IDLE with its line, never SENT: no commit outcome is set anywhere.
 */
internal class HomeTakeAdapter(
    private val take: TakePort,
    private val owner: TakeOwner,
    private val settle: TakeSettle,
    private val switch: ArmedSwitch,
    private val background: Background,
    private val main: MainPost,
    private val onLine: (String?) -> Unit,
    private val onState: (DictationState) -> Unit,
) {

    /**
     * A tap on the mic: the start of a take, or its end. Only a take the Home holds is ended here; the
     * owner is read before the port's state, so a tap with no take the Home holds never reads the port, and
     * a take the tile began goes to begin, and the runner answers its own sentence.
     */
    fun onTap() {
        if (owner.heldByHome() && take.sessionState == DictationState.RECORDING) finishAndSettle() else beginFromTap()
    }

    /**
     * The X on the Home mic: the take is cancelled. Nothing is saved, the microphone is released,
     * and the Home goes back to ready. Only a take the Home holds is cancelled.
     */
    fun onCancel() {
        if (owner.heldByHome() && take.sessionState == DictationState.RECORDING) {
            // Queue the cancel on the single FIFO worker before any later begin could be queued, so a quick
            // tap after this can never start a take. The submit is swallowed like the original: a background
            // that refuses to run the cancel still settles idle below it; the settle-idle always runs after,
            // on the caller thread, whether or not the background ran the cancel.
            try {
                background.submit { take.cancel() }
            } catch (e: Exception) {
                // A background that refuses to run the cancel; nothing survives, so settle idle after all.
            }
            owner.settled()
            onState(DictationState.IDLE)
        }
    }

    /**
     * The check on the Home mic (or a second tap): the take is finished, saved to History and
     * copied to the clipboard. The outcome line answers on the main thread.
     */
    fun onSend() {
        if (owner.heldByHome() && take.sessionState == DictationState.RECORDING) finishAndSettle()
    }

    /**
     * The first tap. The stored-off check is the first thing done here: with the switch stored off there is
     * no begin, no cold start and no arm call of any kind: only the line. Otherwise the take begins once on
     * the shared background worker: [take.begin] runs inside a submit so it never runs on the caller thread,
     * and the answer reaches Home through [main] exactly as the tile's does (mirroring TileCoordinator), with
     * a submit-refuse answered through RunnerSentences.COULD_NOT_RECORD and no state pushed.
     */
    private fun beginFromTap() {
        if (switch.isUserSwitchedOff()) {
            onLine(HomeSentences.SWITCHED_OFF)
            return
        }
        try {
            background.submit {
                val answer: BeginResult =
                    try { take.begin() } catch (e: Exception) { BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD) }
                when (answer) {
                    BeginResult.Recording -> main.post { onLine(null); onState(DictationState.RECORDING) }
                    is BeginResult.Refused -> main.post { onLine(answer.sentence) } // the runner's own line; nothing started
                    is BeginResult.Failed -> main.post { onLine(answer.sentence) } // the runner's own line; nothing started
                    BeginResult.Taken -> main.post { onLine(HomeSentences.MIC_TAKEN) } // the busy face; no save, no copy, no settle
                }
            }
        } catch (e: Exception) {
            // A background that refuses to run the submit: nothing started, so a runner line answers and no state is pushed.
            onLine(RunnerSentences.COULD_NOT_RECORD)
        }
    }

    /**
     * The second tap: the finish, the save and the reset run on the background; the copy and the
     * outcome line run on the main thread. A failed reset never drops the save/copy decision; a
     * thrown finish settles as not-converted.
     */
    private fun finishAndSettle() {
        // Cleared here on the main thread, before the finish runs, so a second tap meanwhile cannot finish it twice.
        owner.settled()
        try {
            background.submit {
                val finished: FinishResult? =
                    try { take.finish() } catch (e: Exception) { null }
                val saveFailed: Boolean = settle.save(finished)
                try {
                    take.resetAfterTaken()
                } catch (e: Exception) {
                    // The settle still runs; a failed reset must not drop the save/copy decision.
                }
                main.post {
                    val line: String = settle.line(finished, saveFailed, HomeSentences.lines)
                    onState(DictationState.IDLE)
                    onLine(line)
                }
            }
        } catch (e: Exception) {
            // A background that refuses: the take settles at once, as not-converted.
            val line: String = settle.line(null, false, HomeSentences.lines)
            onState(DictationState.IDLE)
            onLine(line)
        }
    }
}
