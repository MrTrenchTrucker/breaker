package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import dev.breaker.dictation.core.model.WordUpdate
import dev.breaker.dictation.core.port.WordStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select

/**
 * The longest the tail flush may wait in stop(), in milliseconds: 2 s, not the engine's decode limit.
 * The reason: stop() may block the main thread; Android's not-responding limit is 5 s for input.
 * NOT MEASURED on a device. The device check measures the real tail decode time.
 */
internal const val FLUSH_LIMIT_MS = 2_000L

/** The flush limit for a clip of [audioMs]: it does not depend on the clip length, see [FLUSH_LIMIT_MS]. */
internal fun flushLimitMs(@Suppress("UNUSED_PARAMETER") audioMs: Long): Long = FLUSH_LIMIT_MS

/**
 * Reports the words heard while one capture runs, from the streaming engine.
 *
 * start() queues the worker on the single slot of [dispatcher], which is the word stream's own
 * slot and not the batch decode slot. The worker opens the recogniser and one stream on that slot.
 * The slot does all the native work: it feeds every chunk, decodes while the engine is ready, and reports the whole
 * current hypothesis when it has changed. feed() only offers a copy of a chunk to a bounded
 * channel and never blocks; a chunk that does not fit is dropped and counted.
 *
 * stop() ends the capture. Audio still queued at stop is discarded. The worker feeds only
 * the tail padding, finishes the input and reports one final update. The times are
 * milliseconds from the first sample fed after start(). Overflow makes the word times early
 * by the dropped audio.
 *
 * stop() from a plain thread returns at once if the worker has not yet got past the native open
 * (it is queued behind another run, or it is inside the open: a model load, when no stream exists
 * yet). That run is abandoned: the flush deadline is not armed,
 * nothing is waited for, and when the open returns the worker releases the recogniser it got and
 * ends. It delivers no update, no final and no tail flush, and it counts no flush timeout.
 *
 * Otherwise stop() waits, without a bound, for the update that is already running and for the
 * worker to reach the tail flush. Only the tail flush (the padding, the end of input and the drain)
 * is bounded, by [flushDeadline], which is [FLUSH_LIMIT_MS] (2 s) by default. When the deadline
 * expires, the final is abandoned and [flushTimeouts] counts it; stop() returns, and the abandoned
 * flush may still run on the slot, where it delivers nothing and releases the native objects.
 * stop() called from inside an update returns at once, with no tail flush and no final. It acts on
 * the run whose callback is running, not on the newest run. An update callback must never wait on
 * the thread that calls stop(): it posts the update and returns.
 * If the thread that calls stop() is interrupted while it waits, the wait ends early and stop returns,
 * and the running update or tail flush may still finish after stop returned. This is NOT HANDLED yet.
 * stop() must not be called from a coroutine running on the stream's own single slot, except from inside
 * onUpdate: it would wait for work that needs that slot. The slot is private to the stream, so the app
 * cannot reach it.
 *
 * Each start() makes a fresh run with its own flags, its own counters and a new generation number.
 * start() claims the stream with one compare-and-set on the newest run, so two starts at the same
 * moment make one run and the other returns as a double start does. A check followed by a plain set
 * would let both starts pass the check and both launch a worker; the race test counts the opens to
 * show that difference. After a stop that returned, a start begins a new run; while an abandoned
 * worker still holds the slot, the new run waits behind it. The generation check keeps a late worker of
 * an older run from delivering updates or writing the flags of the stream into a newer one. The run's own
 * stopReturned and phase state already cover the normal paths, so the generation checks are a second line
 * of defence, kept on purpose. A second start() while a run is going is a no-op.
 *
 * An Exception from a native call, or one thrown by the update callback, ends the run quietly: nothing is
 * thrown, no final update is sent and the native objects are released. Each such Exception is counted
 * in [failures] of its own run; the count is the signal. A link failure (a LinkageError) from the
 * native open ends the run the same way and is counted. No other Error is caught. An Error (an
 * AssertionError or an OutOfMemoryError, for example, or a LinkageError from any other native call)
 * leaves the worker after the native objects are released and the run's own flags are set, and it
 * reaches the uncaught-exception handler of the scope, which is [onUncaught] when one is given and the
 * thread's handler otherwise. Coroutine cancellation is rethrown and is not counted. The port has no
 * error channel, so an Exception is a known gap.
 */
internal class OnDeviceWordStream(
    private val files: TransducerFiles,
    private val numThreads: Int,
    private val opener: NativeStreamingOpener = SherpaOnnxBinding,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val dispatcher: CoroutineDispatcher = singleSlot(Dispatchers.IO),
    private val flushDeadline: DecodeDeadline = DecodeDeadline.afterAudio(::flushLimitMs),
    private val onStopWaiting: () -> Unit = {},
    private val onUncaught: CoroutineExceptionHandler? = null,
    private val beforeStartClaim: () -> Unit = {},
) : WordStream {

    private val capacity: Int = queueCapacity

    init {
        require(queueCapacity > 0) { "the word stream needs a queue of at least one chunk: $queueCapacity" }
    }

    private val scope = CoroutineScope(dispatcher + SupervisorJob() + (onUncaught ?: EmptyCoroutineContext))

    /**
     * One capture run: its queue, its flags, its counters, its generation and its worker. A new run is made
     * by every start(), and the worker writes only the flags and counters of its own run, never those of a
     * newer one. The generation is set right after the claim, before the worker starts.
     */
    private class Run(capacity: Int) {
        val channel = Channel<FloatArray>(capacity)
        val running = AtomicBoolean(true)
        val stopping = AtomicBoolean(false)
        val abandoned = AtomicBoolean(false)

        /**
         * Claimed once: by the worker when it ends (in its finally), or by the flush timer when it expires.
         * Whichever claims first decides: an expiry after the worker ended claims nothing and counts nothing.
         */
        val finalDone = AtomicBoolean(false)
        val flushAbandoned = AtomicBoolean(false)
        val expired = CompletableDeferred<Unit>()

        /** Exceptions of this run's native calls and callback, and link failures at the open. */
        val failures = AtomicLong(0L)

        /** Tail flushes of this run that outlived the flush deadline. */
        val flushTimeouts = AtomicLong(0L)

        /** The generation of this run, set once by start() right after the claim. */
        @Volatile
        var generation: Long = 0L

        /**
         * OPENING until the worker's open returns; then OPENED, or ABANDONED_OPEN when stop() got there
         * first. One compare-and-set from each side decides whether a stream is ever made.
         */
        val phase = AtomicInteger(OPENING)

        /** Completed by the worker just before the tail flush, and also when the worker ends before it. */
        val tailStarted = CompletableDeferred<Unit>()

        /** Completed by the worker when it has ended and released its native objects. */
        val done = CompletableDeferred<Unit>()

        /** Set when a stop() on this run has returned. */
        @Volatile
        var stopReturned: Boolean = false

        /** Set once, in start(), before the claim, so a stop that sees this run always finds its job. */
        @Volatile
        var job: Job? = null
    }

    // stop() must know whether the calling thread is inside the update callback, and which run that callback
    // belongs to. Only a ThreadLocal answers that for non-suspend code. The run is set and reset for the
    // callback in deliver(), in the same style as the engine's decodingHere.
    private val updateRun: ThreadLocal<Run?> = ThreadLocal()

    /** The newest run, claimed by start() with one compare-and-set. */
    private val current = AtomicReference<Run?>(null)

    /** The generation of the newest run; a worker whose generation is not this one is stale. */
    private val generation = AtomicLong(0L)

    private val droppedChunksCount = AtomicLong(0L)
    private val droppedSamplesCount = AtomicLong(0L)

    override val isRunning: Boolean
        get() = current.get()?.running?.get() ?: false

    /** Chunks dropped since the last start() because the queue was full. */
    val droppedChunks: Long
        get() = droppedChunksCount.get()

    /** Samples in the chunks dropped since the last start(). */
    val droppedSamples: Long
        get() = droppedSamplesCount.get()

    /** Tail flushes of the newest run that outlived the flush deadline. */
    val flushTimeouts: Long
        get() = current.get()?.flushTimeouts?.get() ?: 0L

    /** Exceptions of the newest run from native calls and from the update callback. Errors are not counted, except a link failure at the open. */
    val failures: Long
        get() = current.get()?.failures?.get() ?: 0L

    /** The job of the newest run's worker, for the tests. */
    internal val currentJob: Job?
        get() = current.get()?.job

    override fun start(onUpdate: (WordUpdate) -> Unit) {
        // Refused only while the previous run has neither ended nor been stopped, so a double start is a
        // no-op. A stop that returned on the deadline path, from inside an update, or during the open,
        // does not block a fresh run.
        val previous = current.get()
        if (previous != null && !previous.done.isCompleted && !previous.stopReturned) return
        beforeStartClaim()
        val run = Run(capacity)
        // The job is made before the claim, and started only after it, so a stop that finds the run finds its job.
        val job = scope.launch(start = CoroutineStart.LAZY) { runWorker(run, onUpdate) }
        run.job = job
        // One compare-and-set claims the stream for this run. A start that lost the claim is refused as a
        // double start is, and its worker never runs.
        if (!current.compareAndSet(previous, run)) {
            job.cancel()
            return
        }
        run.generation = generation.incrementAndGet()
        droppedChunksCount.set(0L)
        droppedSamplesCount.set(0L)
        job.start()
    }

    override fun stop() {
        val inside = updateRun.get()
        if (inside != null) {
            // Called from inside the update of this run, on the thread that delivers it, so it cannot wait for
            // itself. It acts on the run whose callback is running, not on the newest run. No tail flush and no
            // final: the worker ends as soon as the callback returns.
            inside.abandoned.set(true)
            inside.stopping.set(true)
            inside.channel.close()
            inside.running.set(false)
            inside.stopReturned = true
            return
        }
        val run = current.get() ?: return
        try {
            stopFromPlainThread(run)
        } finally {
            run.stopReturned = true
        }
    }

    /**
     * The stop from a plain thread: at once while the native open runs, otherwise the unbounded wait for
     * the running update and then the bounded tail flush.
     */
    private fun stopFromPlainThread(run: Run) {
        if (run.expired.isCompleted) return
        val job = run.job ?: return
        if (job.isCompleted) return
        run.stopping.set(true)
        run.channel.close()
        // No stream exists while the open runs, so there is nothing to wait for. The compare-and-set decides
        // the race with the worker, which makes the same change right after the open returns.
        if (run.phase.compareAndSet(OPENING, ABANDONED_OPEN)) {
            run.abandoned.set(true)
            run.running.set(false)
            return
        }
        onStopWaiting()
        // Not bounded by the flush deadline: a running update must end before the tail flush is timed.
        // The worker completes tailStarted just before the tail.
        waitFor(job, run.tailStarted)
        // Only the tail flush is bounded. Expiry claims the final, so no final can follow it.
        val handle = flushDeadline.arm(TAIL_MS) {
            // A worker that ended already claimed the final in its finally, so nothing is claimed or counted here.
            if (run.finalDone.compareAndSet(false, true)) {
                run.flushAbandoned.set(true)
                run.flushTimeouts.incrementAndGet()
                run.expired.complete(Unit)
            }
        }
        try {
            waitFor(job, run.expired)
            // After an expired flush the worker may still be inside the native call; isRunning must read
            // false when stop() returns. Only this run's flag is written here, and its worker writes it too.
            run.running.set(false)
        } finally {
            handle.close()
        }
    }

    /** Offers a copy of [samples] (16 kHz mono) to the worker. Never blocks; a full queue drops the chunk and counts it. */
    fun feed(samples: FloatArray) {
        if (samples.isEmpty()) return
        val run = current.get() ?: return
        val sent = run.channel.trySend(samples.copyOf())
        if (sent.isSuccess || sent.isClosed) return
        droppedChunksCount.incrementAndGet()
        droppedSamplesCount.addAndGet(samples.size.toLong())
    }

    /** Waits for the worker or for [signal], whichever comes first, from a plain thread. */
    private fun waitFor(job: Job, signal: CompletableDeferred<Unit>) {
        try {
            runBlocking {
                select<Unit> {
                    job.onJoin { }
                    signal.onAwait { }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** True while [run] is the newest run. A worker checks this before each update it sends and before it writes the run's flags. */
    private fun isCurrent(run: Run): Boolean = run.generation == generation.get()

    /**
     * The worker's whole life. An Exception from a native call, or one thrown by the update callback, ends the run
     * quietly and is counted in the run's failures. A link failure (a LinkageError) at the open ends the run
     * the same way and is counted. Any other Error is not caught: it escapes once the native objects are
     * released. A coroutine cancellation is rethrown and is not counted. An Exception from a release is counted
     * in the run's failures and swallowed by [releaseCounted]; an Error from a release propagates only after both
     * releases ran. If both releases throw an Error, the recogniser's Error replaces the stream's.
     */
    private suspend fun runWorker(run: Run, onUpdate: (WordUpdate) -> Unit) {
        var recognizer: NativeStreamingRecognizer? = null
        var stream: NativeStream? = null
        try {
            recognizer = try {
                opener.open(files, numThreads)
            } catch (linked: LinkageError) {
                // Only the open is guarded for this Error: a link failure there is counted, and the run ends with no stream.
                run.failures.incrementAndGet()
                return
            }
            // The open has returned. If stop() abandoned the run during the open, nothing is created or fed:
            // the recogniser is released in finally.
            if (!run.phase.compareAndSet(OPENING, OPENED)) return
            stream = recognizer.createStream()
            captureLoop(run, stream, onUpdate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            run.failures.incrementAndGet()
        } finally {
            // Claims the final for this run, so a flush timer that fires after this worker ended counts nothing.
            run.finalDone.set(true)
            run.channel.close()
            run.tailStarted.complete(Unit)
            try {
                releaseCounted(run) { stream?.release() }
            } finally {
                try {
                    releaseCounted(run) { recognizer?.release() }
                } finally {
                    if (isCurrent(run)) run.running.set(false)
                    run.done.complete(Unit)
                }
            }
        }
    }

    private suspend fun captureLoop(run: Run, stream: NativeStream, onUpdate: (WordUpdate) -> Unit) {
        var last: List<HeardWord> = emptyList()
        for (chunk in run.channel) {
            // Audio still queued at stop is discarded, never fed to the engine.
            if (run.stopping.get()) continue
            stream.acceptWaveform(chunk, SherpaOnnxRecognizer.SAMPLE_RATE_HZ)
            decodeWithin(stream, boundFor(chunk.size))
            val words = wordsOf(stream)
            if (words != last) {
                last = words
                if (!deliverUpdate(run, WordUpdate(words, false), onUpdate)) return
            }
        }
        if (run.abandoned.get() || run.flushAbandoned.get()) return
        run.tailStarted.complete(Unit)
        // Tail flush: only the fixed tail padding, then the end of input, then the drain. The drain
        // is bounded by boundFor(TAIL_PADDING_SAMPLES) = 10560 / 160 + 16 = 82 decode steps; one more
        // step is a native failure and ends the run quietly. The wait for this flush is bounded by the
        // flush deadline in stop(), which is armed only after tailStarted.
        stream.acceptWaveform(FloatArray(SherpaOnnxRecognizer.TAIL_PADDING_SAMPLES), SherpaOnnxRecognizer.SAMPLE_RATE_HZ)
        stream.inputFinished()
        decodeWithin(stream, boundFor(SherpaOnnxRecognizer.TAIL_PADDING_SAMPLES))
        deliverFinal(run, WordUpdate(wordsOf(stream), true), onUpdate)
    }

    /** Delivers a changed hypothesis. False when the worker must stop delivering. */
    private fun deliverUpdate(run: Run, update: WordUpdate, onUpdate: (WordUpdate) -> Unit): Boolean {
        if (run.flushAbandoned.get() || !isCurrent(run)) return false
        deliver(run, update, onUpdate)
        return !run.abandoned.get() && !run.flushAbandoned.get()
    }

    /** Delivers the final update at most once per run, never after the flush deadline has expired, and never for an older run. */
    private fun deliverFinal(run: Run, update: WordUpdate, onUpdate: (WordUpdate) -> Unit) {
        if (run.flushAbandoned.get() || !isCurrent(run)) return
        if (!run.finalDone.compareAndSet(false, true)) return
        deliver(run, update, onUpdate)
    }

    /** Runs the callback with [run] recorded as the run whose update it is, so a stop from inside it acts on [run]. */
    private fun deliver(run: Run, update: WordUpdate, onUpdate: (WordUpdate) -> Unit) {
        updateRun.set(run)
        try {
            onUpdate(update)
        } finally {
            updateRun.set(null)
        }
    }

    private fun decodeWithin(stream: NativeStream, limit: Int) {
        var steps = 0
        while (stream.isReady()) {
            steps++
            if (steps > limit) throw IllegalStateException(MESSAGE_STEPS)
            stream.decode()
        }
    }

    private fun wordsOf(stream: NativeStream): List<HeardWord> {
        val result = stream.result()
        return mergeWords(result.tokens, result.timestampsSeconds)
    }

    /**
     * Runs a release. An Exception is counted in the run's failures and swallowed, because the run has already
     * ended; a CancellationException is rethrown; an Error is not caught and propagates. If both releases throw
     * an Error, the recogniser's Error replaces the stream's (the plain rule; nothing here catches Throwable).
     */
    private inline fun releaseCounted(run: Run, block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            run.failures.incrementAndGet()
        }
    }

    companion object {
        /** Chunks that may wait for the worker before feed() starts to drop them. */
        const val DEFAULT_QUEUE_CAPACITY = 64

        private const val SAMPLES_PER_FRAME = 160
        private const val STEP_SLACK = 16
        private const val MESSAGE_STEPS = "the on-device word decode did not finish"

        /** Run phases: the open is in progress, the open returned and the run goes on, or stop() abandoned the run during the open. */
        private const val OPENING = 0
        private const val OPENED = 1
        private const val ABANDONED_OPEN = 2

        /** The tail padding length in milliseconds, the audio length the flush deadline is armed with. */
        private const val TAIL_MS: Long = SherpaOnnxRecognizer.TAIL_PADDING_SAMPLES * 1000L / SherpaOnnxRecognizer.SAMPLE_RATE_HZ

        /** The most decode steps for a block of [samples]: its frames plus a little slack. */
        private fun boundFor(samples: Int): Int = samples / SAMPLES_PER_FRAME + STEP_SLACK
    }
}
