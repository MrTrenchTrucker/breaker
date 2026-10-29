package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.WordUpdate
import dev.breaker.dictation.stt.ondevice.FedWordStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred

/**
 * The word stream the phrase tests use. It records its starts, its stops and the frames fed to it, and
 * completes [firstFeed] with the first frame, so a test can wait for the capture to reach the stream.
 *
 * The two dropped-audio counters are reported as zero, so every fake of the stream reports them the
 * same way. The overrides need the stream interface to declare both members.
 */
internal class PhraseTestStream : FedWordStream {
    var starts: Int = 0
        private set
    var stops: Int = 0
        private set
    val feeds = CopyOnWriteArrayList<FloatArray>()
    val firstFeed = CompletableDeferred<FloatArray>()
    private var running: Boolean = false
    private var updates: ((WordUpdate) -> Unit)? = null

    override val isRunning: Boolean
        get() = running

    override fun start(onUpdate: (WordUpdate) -> Unit) {
        starts += 1
        running = true
        updates = onUpdate
    }

    override fun stop() {
        stops += 1
        running = false
        updates = null
    }

    override fun feed(samples: FloatArray) {
        feeds.add(samples)
        firstFeed.complete(samples)
    }

    /** Delivers [update] to the callback of the current run, on the caller's thread; with no run it does nothing. */
    fun emit(update: WordUpdate) {
        updates?.invoke(update)
    }

    override val droppedChunks: Long
        get() = 0L

    override val droppedSamples: Long
        get() = 0L
}
