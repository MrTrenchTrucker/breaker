package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import java.util.concurrent.atomic.AtomicBoolean

/** The lines a taken take settles with: the taken words, one set for the whole taken branch. */
private val TAKEN_LINES: OutcomeLines = OutcomeLines(
    TakenSentences.SAVED_AND_COPIED,
    TakenSentences.COPIED_NOT_SAVED,
    TakenSentences.TAKEN_NOTHING_HEARD,
    TakenSentences.TAKEN_NOTHING_HEARD,
    TakenSentences.NOT_CONVERTED,
)

// wiring/TakeYield.kt - no android.* names, no platform naming.
internal class TakeYieldController(
    private val background: Background,
    private val main: MainPost,
    private val take: TakePort,
    private val settle: TakeSettle,
    private val takenNotice: (String?) -> Unit,
    private val pushState: (DictationState) -> Unit,
    private val onSettled: () -> Unit,
    private val generation: () -> Int,
) {
    /** The MIC_BUSY face flag: the single source of truth. */
    val micBusy = AtomicBoolean(false)

    /** The one listener fired with the new value exactly when the busy flag flips (never on an unchanged set). */
    var onBusyChanged: ((Boolean) -> Unit)? = null

    private val yieldRunning = AtomicBoolean(false)   // one yield at a time

    /** Sets the busy flag, firing [onBusyChanged] with the new value only when it flips. */
    private fun setBusy(v: Boolean) {
        val flipped = micBusy.compareAndSet(!v, v)
        if (flipped) onBusyChanged?.invoke(v)
    }

    /** True while the taken branch is running (a begin attempt in
     *  the middle of it is a no-op, not a state change). */
    fun yieldRunning(): Boolean = yieldRunning.get()

    /** A take was cut by the microphone being taken. */
    fun onTakeMicTaken() {
        if (!yieldRunning.compareAndSet(false, true)) return
        // A second yield while the first transcribes is dropped: the
        // device is gone, there is nothing left to cut.
        setBusy(true)
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
                // saves there too). It has its own try, so a throw
                // cannot skip the settle. The result is an immutable
                // local value, made before main.post and captured by
                // the lambda; main never reads a shared variable.
                val saveFailed: Boolean = settle.save(finished)
                main.post {
                    if (epoch == generation()) yieldSettle(finished, saveFailed)
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
    private fun yieldSettle(finished: FinishResult?, saveFailed: Boolean) {
        // The copy is on main (the Android clipboard is main-thread); the save already ran on the background.
        val line: String = settle.line(finished, saveFailed, TAKEN_LINES)
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
        setBusy(true)     // the face is the busy face, not FAILED
        pushState(DictationState.IDLE)   // nothing is recording
        takenNotice(TakenSentences.STILL_TAKEN)   // the refused-open line
    }

    /** A begin from the busy face that started (the next state
     *  change). */
    fun onBeginAccepted() {
        setBusy(false)
        takenNotice(null)     // the plain line comes back
    }

    /** A begin that failed on the device (a FAILED tile is not a
     *  busy tile). */
    fun onBeginFailed() {
        setBusy(false)
    }

    /** The next take end that is NOT a taken one (a clear point). */
    fun onTakeEndedCleared() {
        setBusy(false)
        takenNotice(null)
    }

    /** The switch off (a clear point). */
    fun disarmCleared() {
        yieldRunning.set(false)
        setBusy(false)
        takenNotice(null)
    }
}
