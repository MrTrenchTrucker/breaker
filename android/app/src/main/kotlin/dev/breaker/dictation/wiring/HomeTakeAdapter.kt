package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState

/**
 * The Home screen's one take path, walked by a tap on the big mic. It drives the shared [TakePort]
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
     * A tap on the big mic: the start of a take, or its end. Only a take the Home holds is ended here; the
     * owner is read before the port's state, so a tap with no take the Home holds never reads the port, and
     * a take the tile began goes to begin, and the runner answers its own sentence.
     */
    fun onTap() {
        if (owner.heldByHome() && take.sessionState == DictationState.RECORDING) finishAndSettle() else beginFromTap()
    }

    /**
     * The first tap. The stored-off check is the first thing done here: with the switch stored off there is
     * no begin, no cold start and no arm call of any kind: only the line. Otherwise the take begins once.
     */
    private fun beginFromTap() {
        if (switch.isUserSwitchedOff()) {
            onLine(HomeSentences.SWITCHED_OFF)
            return
        }
        when (val answer = take.begin()) {
            BeginResult.Recording -> {
                onLine(null)
                onState(DictationState.RECORDING)
            }
            is BeginResult.Refused -> onLine(answer.sentence) // the runner's own line; nothing started
            is BeginResult.Failed -> onLine(answer.sentence) // the runner's own line; nothing started
            BeginResult.Taken -> onLine(HomeSentences.MIC_TAKEN) // the busy face; no save, no copy, no settle
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
