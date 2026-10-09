package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import dev.breaker.dictation.core.model.WordUpdate
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/*
 * A scripted native engine for the on-device word stream tests. The fake reads no clock:
 * every hold is a CompletableDeferred that the test completes, and every wait in a test is
 * a receive or an await on a signal. Each call is logged with whether it ran on the single
 * slot, so a test can check where the native work ran.
 */

/** True on a thread that is inside a block dispatched by [ProbeDispatcher]. */
internal val ON_SLOT: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

/** The word marker as text, for building scripted pieces. */
internal val TIMED_MARK: String = "$WORD_MARKER"

/** A scripted result with no words. */
internal val TIMED_NO_WORDS: NativeResult = timedResult(emptyList(), emptyList())

/** One call into the fake: its name, a short detail and whether it ran on the single slot. */
internal class TimedCall(val name: String, val detail: String, val onSlot: Boolean) {
    override fun toString(): String = if (detail.isEmpty()) name else "$name $detail"
}

/** One update as the callback saw it, with whether it was delivered on the single slot. */
internal class SeenUpdate(val words: List<HeardWord>, val final: Boolean, val onSlot: Boolean)

/** A scripted result whose text is the pieces joined. */
internal fun timedResult(tokens: List<String>, seconds: List<Float>): NativeResult =
    NativeResult(tokens.joinToString(""), tokens, seconds)

/** Files the fake never opens; the adapter only passes them on. */
internal fun timedFiles(): TransducerFiles =
    TransducerFiles(File("encoder.onnx"), File("decoder.onnx"), File("joiner.onnx"), File("tokens.txt"))

/** A block of [samples] samples, every one 0.5. */
internal fun timedChunk(samples: Int = 160): FloatArray = FloatArray(samples) { 0.5f }

/** A flush deadline that never expires: the production clock is never started by these tests. */
internal val NEVER_EXPIRES: DecodeDeadline = DecodeDeadline { _, _ -> AutoCloseable { } }

/** A flush deadline the test fires by hand: [armed] completes with the expiry callback once the stop arms it. */
internal class StubDeadline : DecodeDeadline {
    val armed = CompletableDeferred<() -> Unit>()

    override fun arm(audioMs: Long, onExpired: () -> Unit): AutoCloseable {
        armed.complete(onExpired)
        return AutoCloseable { }
    }
}

/**
 * A single-slot dispatcher for the tests. Every block it runs has [ON_SLOT] set, and it counts
 * the blocks running at once, so a test can show that native work and updates never overlap.
 */
internal class ProbeDispatcher : CoroutineDispatcher() {
    private val slot: CoroutineDispatcher = singleSlot(Dispatchers.Default)
    private val running = AtomicInteger(0)

    /** The largest number of blocks that ran at the same time. */
    val maxBlocks = AtomicInteger(0)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        slot.dispatch(context, Runnable {
            maxBlocks.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            ON_SLOT.set(true)
            try {
                block.run()
            } finally {
                ON_SLOT.set(false)
                running.decrementAndGet()
            }
        })
    }
}

/** A word stream over [fake] with the test defaults; named arguments keep the constructor order out of the tests. */
internal fun timedStream(
    fake: FakeTimedNative,
    capacity: Int = OnDeviceWordStream.DEFAULT_QUEUE_CAPACITY,
    dispatcher: CoroutineDispatcher = ProbeDispatcher(),
    flushDeadline: DecodeDeadline = NEVER_EXPIRES,
    onStopWaiting: () -> Unit = {},
): OnDeviceWordStream = OnDeviceWordStream(
    files = timedFiles(),
    numThreads = 1,
    opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
    queueCapacity = capacity,
    dispatcher = dispatcher,
    flushDeadline = flushDeadline,
    onStopWaiting = onStopWaiting,
)

/** Receives every value that is already in [channel] without waiting for more. */
internal fun <T> drainNow(channel: Channel<T>): List<T> {
    val out = ArrayList<T>()
    while (true) {
        val result = channel.tryReceive()
        if (!result.isSuccess) return out
        out.add(result.getOrThrow())
    }
}

/**
 * Returns once every block queued on [probe] before this call has run. The slot runs one block at a time, so a
 * worker that has ended (or is ending) has finished its block by then. Used where a test must know that an
 * abandoned worker is gone, without a clock.
 */
internal fun awaitSlotIdle(probe: ProbeDispatcher) {
    val idle = CompletableDeferred<Unit>()
    CoroutineScope(probe).launch { idle.complete(Unit) }
    runBlocking { idle.await() }
}

/** Collects the updates a callback sees. [awaitExpected] waits for the first [expected] of them. */
internal class UpdateSink(private val expected: Int) {
    private val seen = CopyOnWriteArrayList<SeenUpdate>()
    private val arrived = Channel<Unit>(Channel.UNLIMITED)

    val updates: List<SeenUpdate> get() = seen.toList()

    fun record(update: WordUpdate) {
        seen.add(SeenUpdate(update.words, update.final, ON_SLOT.get()))
        arrived.trySend(Unit)
    }

    fun awaitExpected() {
        runBlocking { repeat(expected) { arrived.receive() } }
    }
}

/**
 * The scripted recogniser and every stream it gives out. A stream is ready once per accepted
 * block (one decode each), or always when [neverReady] is set; in that mode [pollOverflow] is
 * set if the adapter polls far past any sane bound, so a missing bound shows as a failed
 * assertion and never as a hang. Results come from the script in order, the last one repeats,
 * and after the input is finished result() gives [finalResult] (or the last scripted one).
 */
internal class FakeTimedNative(
    private val results: List<NativeResult>,
    private val finalResult: NativeResult? = null,
    private val neverReady: Boolean = false,
) : NativeStreamingRecognizer {

    init {
        require(results.isNotEmpty()) { "the fake needs at least one scripted result" }
    }

    private val log = CopyOnWriteArrayList<TimedCall>()
    private val failures = ConcurrentHashMap<Pair<String, Int>, Throwable>()
    private val holds = ConcurrentHashMap<Pair<String, Int>, CompletableDeferred<Unit>>()
    private val entries = ConcurrentHashMap<Pair<String, Int>, CompletableDeferred<Unit>>()
    private val audio = CopyOnWriteArrayList<FloatArray>()
    private val pending = AtomicInteger(0)
    private val served = AtomicInteger(0)
    private val polls = AtomicInteger(0)
    private val finished = AtomicBoolean(false)

    /** Counted down, as a deferred, when the recogniser itself is released (even if that release throws). */
    val recognizerReleased = CompletableDeferred<Unit>()

    /** Completed when the stream is released (even if that release throws). */
    val streamReleased = CompletableDeferred<Unit>()

    /** Set when a neverReady stream was polled past the poll guard: the adapter has no working bound. */
    val pollOverflow = AtomicBoolean(false)

    /** Every call in order. */
    val calls: List<TimedCall> get() = log.toList()

    /** The names of the calls in order. */
    fun names(): List<String> = calls.map { it.name }

    /** How many times the method [name] was called, whether or not it threw. */
    fun count(name: String): Int = log.count { it.name == name }

    /** The size of every block the streams accepted, in order. */
    fun sizes(): List<Int> = audio.map { it.size }

    /** Every block of audio the streams accepted, as a copy, in order. */
    val received: List<FloatArray> get() = audio.toList()

    /** Makes the [nth] call (1-based) of the method [name] throw [error]. The call is logged first. */
    fun throwOn(name: String, error: Throwable, nth: Int = 1) {
        failures[name to nth] = error
    }

    /** Makes the [nth] call (1-based) of the method [name] wait until [hold] is completed. */
    fun holdOn(name: String, hold: CompletableDeferred<Unit>, nth: Int = 1) {
        holds[name to nth] = hold
    }

    /** Completed when the [nth] call (1-based) of [name] is entered, before it waits or throws. Safe to ask before or after. */
    fun entered(name: String, nth: Int = 1): CompletableDeferred<Unit> = entryOf(name to nth)

    private fun entryOf(key: Pair<String, Int>): CompletableDeferred<Unit> =
        entries.computeIfAbsent(key) { CompletableDeferred<Unit>() }

    private fun enter(name: String, detail: String = "") {
        val n = count(name) + 1
        log.add(TimedCall(name, detail, ON_SLOT.get()))
        entryOf(name to n).complete(Unit)
        holds[name to n]?.let { hold -> runBlocking { hold.await() } }
        failures[name to n]?.let { throw it }
    }

    /** The opener the adapter is given: logs the open and returns this fake. */
    fun open(threads: Int): NativeStreamingRecognizer {
        enter("open", "threads $threads")
        return this
    }

    override fun createStream(): NativeStream {
        enter("createStream")
        return TimedStream()
    }

    override fun release() {
        try {
            enter("release")
        } finally {
            recognizerReleased.complete(Unit)
        }
    }

    private inner class TimedStream : NativeStream {
        override fun acceptWaveform(samples: FloatArray, sampleRateHz: Int) {
            enter("acceptWaveform", "${samples.size} @$sampleRateHz")
            audio.add(samples.copyOf())
            pending.incrementAndGet()
        }

        override fun inputFinished() {
            enter("inputFinished")
            finished.set(true)
        }

        override fun isReady(): Boolean {
            if (!neverReady) return pending.get() > 0
            if (polls.incrementAndGet() > POLL_GUARD) {
                pollOverflow.set(true)
                return false
            }
            return true
        }

        override fun decode() {
            enter("decode")
            if (!neverReady) pending.decrementAndGet()
        }

        override fun result(): NativeResult {
            enter("result")
            if (finished.get()) return finalResult ?: results.last()
            return results[minOf(served.getAndIncrement(), results.lastIndex)]
        }

        override fun text(): String {
            enter("text")
            return ""
        }

        override fun release() {
            try {
                enter("streamRelease")
            } finally {
                streamReleased.complete(Unit)
            }
        }
    }

    private companion object {
        const val POLL_GUARD = 10_000
    }
}
