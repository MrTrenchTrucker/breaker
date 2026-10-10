package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.port.HistoryStore
import java.util.concurrent.atomic.AtomicBoolean

// wiring/TakeYield.kt - no android.* names, no platform naming.
// The file's own copy of the empty-take sentence (the module convention:
// TileCoordinator keeps its own private copy at :9; a private const is not
// visible across files, so this file declares its own - same string, no
// import, no touch to the coordinator's).
private const val EMPTY_TAKE_SENTENCE: String = "No audio was captured"

internal class TakeYieldController(
    private val background: Background,
    private val main: MainPost,
    private val take: TakePort,
    private val history: HistoryStore?,
    private val clipboard: TakenClipboard,
    private val takenNotice: (String?) -> Unit,
    private val pushState: (DictationState) -> Unit,
    private val onSettled: () -> Unit,
    private val generation: () -> Int,
) {
    /** The MIC_BUSY face flag: the single source of truth. */
    val micBusy = AtomicBoolean(false)
    private val yieldRunning = AtomicBoolean(false)   // one yield at a time

    /** True while the taken branch is running (a begin attempt in
     *  the middle of it is a no-op, not a state change). */
    fun yieldRunning(): Boolean = yieldRunning.get()

    /** A take was cut by the microphone being taken. */
    fun onTakeMicTaken() {
        if (!yieldRunning.compareAndSet(false, true)) return
        // A second yield while the first transcribes is dropped: the
        // device is gone, there is nothing left to cut.
        micBusy.set(true)
        takenNotice(TakenSentences.LEAD)   // the lead line, shown at the
        // cut; the settle replaces it with the outcome's line
        pushState(DictationState.SENDING)
        // The explicit state: the face maps to MIC_BUSY through the
        // micBusy clause (it comes before the sendPushed clause), and it
        // stays MIC_BUSY after the settle (the flag lasts until the next state change).
        val epoch: Int = generation()
        val queued: Boolean = try {
            background.submit {
                val finished: FinishResult? =
                    try { take.finish() } catch (e: Exception) { null }
                // The reset runs BEFORE the settle, on the
                // background, whether finish succeeded or threw:
                try { take.resetAfterTaken() } catch (e: Exception) {
                    // The settle still runs; a failed reset must not
                    // drop the save/copy decision.
                }
                // The save is on the background (the send path
                // saves there too):
                if (finished is FinishResult.ReadyToSend &&
                    finished.transcription.text.isNotBlank() &&
                    history != null) {
                    history.save(finished.transcription)
                }
                main.post {
                    if (epoch == generation()) yieldSettle(finished)
                }
            }
            true
        } catch (e: Exception) { false }
        if (!queued) {
            // A background that refuses: the cut is abandoned at once.
            // The reset runs first (no audio stop; the device is
            // already gone) so the settle push never maps a live session.
            try { take.resetAfterTaken() } catch (e: Exception) {
                // The settle still runs.
            }
            yieldRunning.set(false)
            onSettled()
            pushState(DictationState.IDLE)
            // The face stays the busy face. The line goes to the not-converted sentence: the
            // transcription never ran, so the text was never converted -
            // the same outcome the settle gives a thrown finish.
            takenNotice(TakenSentences.NOT_CONVERTED)
        }
    }

    /** The settle of the taken take (on main; its three outcomes, the
     *  PER-OUTCOME line: the full outcome line replaces the LEAD). */
    private fun yieldSettle(finished: FinishResult?) {
        val line: String = when {
            finished is FinishResult.ReadyToSend &&
                finished.transcription.text.isNotBlank() -> {
                // The save already ran on the background; the copy is on
                // main (the Android clipboard is main-thread).
                try { clipboard.copy(finished.transcription.text) }
                catch (e: Exception) {
                    // A clipboard that throws must not stop the settle
                    // (the coordinator's guarded{} pattern).
                }
                TakenSentences.SAVED_AND_COPIED   // saved and copied
            }
            finished is FinishResult.ReadyToSend ->
                TakenSentences.TAKEN_NOTHING_HEARD   // blank: nothing was heard
            finished is FinishResult.Failed &&
                finished.sentence == EMPTY_TAKE_SENTENCE ->
                TakenSentences.TAKEN_NOTHING_HEARD   // the empty-take sentence (the
                // coordinator's own split, finishTake :195)
            else ->
                TakenSentences.NOT_CONVERTED   // any other Failed (could not be
                // converted), a throw or null - never the engine detail
        }
        yieldRunning.set(false)
        onSettled()   // the coordinator clears its sendPushed
        pushState(DictationState.IDLE)
        takenNotice(line)   // the outcome's line: one post, same id
        // The face STAYS MIC_BUSY (the flag is NOT cleared here - the
        // flag lifetime: the next state change clears it, and only a
        // clear point restores the plain line).
    }

    /** A tap (or shake) whose open was refused as taken. */
    fun beginRefusedAsTaken() {
        micBusy.set(true)     // the face is the busy face, not FAILED
        pushState(DictationState.IDLE)   // nothing is recording
        takenNotice(TakenSentences.STILL_TAKEN)   // the refused-open line
    }

    /** A begin from the busy face that started (the next state
     *  change). */
    fun onBeginAccepted() {
        micBusy.set(false)
        takenNotice(null)     // the plain line comes back
    }

    /** A begin that failed on the device (a FAILED tile is not a
     *  busy tile). */
    fun onBeginFailed() {
        micBusy.set(false)
    }

    /** The next take end that is NOT a taken one (a clear point). */
    fun onTakeEndedCleared() {
        micBusy.set(false)
        takenNotice(null)
    }

    /** The switch off (a clear point). */
    fun disarmCleared() {
        yieldRunning.set(false)
        micBusy.set(false)
        takenNotice(null)
    }
}
