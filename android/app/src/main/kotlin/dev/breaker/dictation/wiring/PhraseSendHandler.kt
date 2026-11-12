package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.port.PhraseTrigger
import dev.breaker.dictation.core.port.WordStream
import dev.breaker.dictation.core.usecase.SendResult
import dev.breaker.dictation.phrases.PhraseDetector
import dev.breaker.dictation.stt.ondevice.FedWordStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The one owner of the spoken send. It starts and stops the phrase detector for a take, holds the word
 * stream that the audio tee feeds, makes the once-per-take rule for a spoken send, and turns an accepted
 * spoken send into the surface's own send, run on main.
 *
 * [begun] and [stop] run on the take worker only; [PhraseTakePort] calls them from take calls that already
 * run there. [onTakeEnded] may be called from any thread: it hands its stop to the take worker. The detector
 * reports on its own thread, and a report only claims the send of its take and posts it to main.
 */
class PhraseSendHandler(
    private val background: Background,
    private val main: MainPost,
    private val streams: PhraseStreamSource,
    private val newDetector: (WordStream) -> PhraseTrigger = { PhraseDetector(it) },
) {
    private val current: AtomicReference<TakeRun?> = AtomicReference(null)

    /** The word stream of the take that runs now, or null. The audio tee reads it on the capture thread. */
    fun currentStream(): FedWordStream? = current.get()?.stream

    /** The trim that an accepted spoken send left for the finish of its take, or null. */
    fun takePendingTrim(): Long? = current.get()?.trim?.get()

    /** Called on the take worker after a begin that answered recording. [sendEntry] is the surface's own send. */
    fun begun(sendEntry: () -> Unit) {
        stop()
        try {
            val stream: FedWordStream = streams.newStream() ?: return
            val run = TakeRun(newDetector(stream), stream, sendEntry)
            current.set(run)
            run.trigger.start { event -> onEvent(run, event) }
        } catch (e: Exception) {
            stop()
        } catch (e: LinkageError) {
            stop()
        }
    }

    /** Stops the detector of the take that runs now. Called on the take worker; a second call does nothing. */
    fun stop() {
        val run: TakeRun = current.getAndSet(null) ?: return
        try {
            run.trigger.stop()
        } catch (e: Exception) {
            // The run is already dropped; a detector that fails to stop does not affect the take.
        } catch (e: LinkageError) {
            // Same as above.
        }
    }

    /** Called when a take ends by itself. Any thread may call it; the stop runs on the take worker. */
    fun onTakeEnded() {
        try {
            background.submit { stop() }
        } catch (e: Exception) {
            // The background refused the stop; the next begin or finish stops the detector.
        }
    }

    /** A report from the detector of [run]: only the take that is running may act on a spoken send. */
    private fun onEvent(run: TakeRun, event: PhraseEvent) {
        if (event !is PhraseEvent.Send) return
        if (current.get() !== run) return
        if (!run.claimSend()) return
        run.trim.set(event.trimBeforeMs)
        try {
            main.post { if (current.get() === run) run.sendEntry() }
        } catch (e: Exception) {
            run.trim.set(null)
        }
    }
}

/** The state of one take that a phrase detector listens for. */
private class TakeRun(
    val trigger: PhraseTrigger,
    val stream: FedWordStream,
    val sendEntry: () -> Unit,
) {
    val trim: AtomicReference<Long?> = AtomicReference(null)
    private val claimed: AtomicBoolean = AtomicBoolean(false)

    /** The one definition of the rule that a second spoken send in one take is dropped. */
    fun claimSend(): Boolean = claimed.compareAndSet(false, true)
}

/**
 * The take port a surface drives, with the phrase hooks around the take. Begin and the other take calls pass
 * to [inner]; each one that ends or stops the take stops the detector first. A finish takes the trim that an
 * accepted spoken send left, unless a trim is given. [sendEntry] is the surface's own send.
 */
internal class PhraseTakePort(
    private val inner: TakePort,
    private val handler: PhraseSendHandler,
    private val sendEntry: () -> Unit,
) : TakePort {
    override val sessionState: DictationState
        get() = inner.sessionState

    override fun begin(): BeginResult {
        val answer: BeginResult = inner.begin()
        if (answer == BeginResult.Recording) handler.begun(sendEntry)
        return answer
    }

    override fun finish(): FinishResult = finishTrimmed(null)

    override fun finishTrimmed(trimBeforeMs: Long?): FinishResult {
        val trim: Long? = trimBeforeMs ?: handler.takePendingTrim()
        handler.stop(); return inner.finishTrimmed(trim)
    }

    override fun send(): SendResult? {
        handler.stop()
        return inner.send()
    }

    override fun cancel() {
        handler.stop(); inner.cancel()
    }

    override fun resetAfterTaken() {
        handler.stop(); inner.resetAfterTaken()
    }
}
