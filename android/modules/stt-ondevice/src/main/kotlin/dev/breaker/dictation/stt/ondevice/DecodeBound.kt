package dev.breaker.dictation.stt.ondevice

import java.util.concurrent.CompletableFuture
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Arms a one-shot deadline for one decode. */
fun interface DecodeDeadline {
    /** Starts the deadline for a clip of [audioMs] milliseconds; [onExpired] is called at most once. The returned handle disarms it. */
    fun arm(audioMs: Long, onExpired: () -> Unit): AutoCloseable

    companion object {
        /** The production deadline: [decodeLimitMs] after arming, measured on Dispatchers.Default. The only clock in this module's production code. */
        fun afterAudio(): DecodeDeadline = DecodeDeadline { audioMs, onExpired ->
            val timer = CoroutineScope(Dispatchers.Default).launch {
                delay(decodeLimitMs(audioMs))
                onExpired()
            }
            AutoCloseable { timer.cancel() }
        }
    }
}

/** 30_000 + 3 * audioMs. Not measured on a device. */
internal fun decodeLimitMs(audioMs: Long): Long = 30_000L + 3L * audioMs

/** Thrown by the wrapper on the slot thread when the deadline expired first. Not a RuntimeException, not a SherpaTranscriptionException. */
internal class DecodeExpiredException : Exception()

/**
 * Bounds a native decode that may never return.
 *
 * A native call cannot be cancelled, so the only lever is to stop waiting for
 * it. [wrap] returns a recognizer whose decode hands ONLY the native call to a
 * worker and waits for whichever comes first: the worker's result or the
 * deadline. When the deadline wins, the wait ends with [DecodeExpiredException]
 * and the worker is abandoned: it keeps running until the native call returns,
 * and it then releases the recognizer itself. While it runs, [abandonedRunning]
 * is true.
 *
 * One abandoned decode can leak one worker thread and one recognizer until the
 * native call returns. The limit that decides "stuck" is not measured on a device.
 */
internal class DecodeBound(
    private val deadline: DecodeDeadline,
    private val workers: CoroutineDispatcher,
    private val marked: (block: () -> SherpaTranscript) -> SherpaTranscript,
) {
    /** What ended a decode, decided by whoever completes the outcome future first. */
    private sealed class Outcome {
        class Decoded(val transcript: SherpaTranscript) : Outcome()

        class Threw(val error: Throwable) : Outcome()

        object Expired : Outcome()
    }

    /** The two futures of one decode: how it ended, and the worker's last act. */
    private class Handle(val outcome: CompletableFuture<Outcome>, val workerDone: CompletableFuture<Unit>)

    // Written only on the slot thread, read from any thread.
    @Volatile
    private var current: Handle? = null

    /** True while an abandoned (expired) decode's worker has not returned from native code yet. */
    val abandonedRunning: Boolean
        get() {
            val handle = current ?: return false
            val expired = handle.outcome.isDone && handle.outcome.join() is Outcome.Expired
            val running = !handle.workerDone.isDone
            return expired && running
        }

    /** Wraps [inner]; decode goes through the worker and the deadline, release follows the ownership rule in [Bounded.release]. */
    fun wrap(inner: SherpaRecognizer): SherpaRecognizer = Bounded(inner)

    private inner class Bounded(private val inner: SherpaRecognizer) : SherpaRecognizer {
        // Set on the slot thread when the deadline won; read by release() on the same thread.
        @Volatile
        private var abandoned = false

        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
            val audioMs = pcm.size * 1000L / sampleRateHz
            val outcome = CompletableFuture<Outcome>()
            val workerDone = CompletableFuture<Unit>()
            current = Handle(outcome, workerDone)
            val armed = deadline.arm(audioMs) { outcome.complete(Outcome.Expired) }
            try {
                // Argued exception to the module rule "use coroutines for every concurrent thing".
                // The decode runs on a worker because a native call cannot be cancelled: the only
                // lever is to stop waiting for it, so the wait must be something the deadline can
                // end while the native call is still running. The worker is a plain dispatch on the
                // worker dispatcher, not a coroutine of the engine's slot: a task on the slot would
                // wait behind the very decode it is meant to bound.
                workers.dispatch(
                    EmptyCoroutineContext,
                    Runnable {
                        var lost = false
                        try {
                            try {
                                val transcript = marked { inner.decode(pcm, sampleRateHz) }
                                if (!outcome.complete(Outcome.Decoded(transcript))) lost = true
                            } catch (t: Throwable) {
                                if (!outcome.complete(Outcome.Threw(t))) lost = true
                            }
                        } finally {
                            if (lost) {
                                try {
                                    inner.release()
                                } catch (_: Throwable) {
                                }
                            }
                            workerDone.complete(Unit)
                        }
                    },
                )
                val result = park(outcome)
                return when (result) {
                    is Outcome.Decoded -> result.transcript
                    is Outcome.Threw -> throw result.error
                    is Outcome.Expired -> {
                        abandoned = true
                        throw DecodeExpiredException()
                    }
                }
            } finally {
                armed.close()
            }
        }

        override fun release() {
            if (abandoned) return
            inner.release()
        }

        // Argued exception to the module rule "use coroutines for every concurrent thing".
        // The wait must hold the engine's single slot WITHOUT suspending: a suspension hands the
        // slot to the next queued decode, and two native decodes would overlap. It must also pump
        // no event loop: a nested blocking coroutine bridge on a caller's own loop could run the
        // next queued body inside this wait. A plain JDK future parks the calling thread and
        // pumps nothing, and either the worker or the deadline can complete it.
        private fun park(outcome: CompletableFuture<Outcome>): Outcome {
            try {
                return outcome.get()
            } catch (_: InterruptedException) {
                // Put back the interrupt flag the wait cleared, so the caller's own interrupt handling still sees it.
                Thread.currentThread().interrupt()
                outcome.complete(Outcome.Expired)
                return outcome.getNow(Outcome.Expired)
            }
        }
    }
}
