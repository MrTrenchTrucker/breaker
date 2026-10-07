package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.SttRequest
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/*
 * Helpers for the tests of the decode bound. Nothing here reads a clock: the
 * deadline is fired by the test, and every wait is on a signal.
 *
 * Each counter below has a single writer thread (the engine's slot, or the test)
 * and is read by the test thread after the call returned, which is why a volatile
 * field is enough and nothing needs to be guarded.
 */

/**
 * A deadline the test fires by hand. It records each arm, keeps the callback,
 * and counts how often the handle it returned was closed.
 */
internal class FakeDeadline : DecodeDeadline {

    /** Completed by the first arm. */
    val armed = CompletableDeferred<Unit>()

    @Volatile
    private var callback: (() -> Unit)? = null

    @Volatile
    private var lengths: List<Long> = emptyList()

    @Volatile
    private var closes = 0

    /** The clip length of every arm, in the order armed. */
    val audioMs: List<Long> get() = lengths

    /** How many times the engine armed this deadline. */
    val armCount: Int get() = lengths.size

    /** How many times a handle returned by [arm] was closed. */
    val closeCount: Int get() = closes

    override fun arm(audioMs: Long, onExpired: () -> Unit): AutoCloseable {
        lengths = lengths + audioMs
        callback = onExpired
        armed.complete(Unit)
        return AutoCloseable { closes++ }
    }

    /** Calls the callback of the latest arm on the CALLING thread, and fails by name when nothing was armed. */
    fun fire() {
        val stored = callback
            ?: throw AssertionError("the deadline was never armed, so there is nothing to fire")
        stored()
    }
}

/**
 * A recognizer whose decode parks until [parkUntil] completes, logging "enter"
 * and "exit" on [log]. It records how often it was released and whether any
 * release happened while its decode was still running.
 */
internal open class ParkingRecognizer(
    private val parkUntil: CompletableDeferred<Unit>,
    val log: Channel<String>,
) : SherpaRecognizer {

    @Volatile
    var inDecode = false

    @Volatile
    var releaseCount = 0

    @Volatile
    var releasedWhileDecoding = false

    override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
        inDecode = true
        log.trySend("enter")
        runBlocking { parkUntil.await() }
        log.trySend("exit")
        inDecode = false
        return afterPark()
    }

    /** What the decode gives back once it was released from the park and [inDecode] is false again. */
    protected open fun afterPark(): SherpaTranscript = SherpaTranscript("late transcript", emptyList(), "en")

    override fun release() {
        releaseCount++
        if (inDecode) releasedWhileDecoding = true
    }
}

/** A [ParkingRecognizer] whose decode throws [SherpaTranscriptionException] once it is released from the park. */
internal class ParkThenThrowRecognizer(
    parkUntil: CompletableDeferred<Unit>,
    log: Channel<String>,
) : ParkingRecognizer(parkUntil, log) {
    override fun afterPark(): SherpaTranscript = throw SherpaTranscriptionException("late failure")
}

/** A recognizer whose decode throws [SherpaTranscriptionException]. */
internal class FailingRecognizer : SherpaRecognizer {
    override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript =
        throw SherpaTranscriptionException("decode failed on purpose")

    override fun release() {}
}

/**
 * The dispatcher the engine hands its decode to. It reports each task handed to
 * it on [dispatched], forwards the task to [delegate], and reports on
 * [finished] when the task's block has returned.
 */
internal class RecordingWorkers(
    private val delegate: CoroutineDispatcher = Dispatchers.IO,
) : CoroutineDispatcher() {

    /** One element per task handed to this dispatcher. */
    val dispatched = Channel<Unit>(Channel.UNLIMITED)

    /** One element per task whose block has returned. */
    val finished = Channel<Unit>(Channel.UNLIMITED)

    @Volatile
    private var handedOver = 0

    @Volatile
    private var reported = 0

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handedOver++
        dispatched.trySend(Unit)
        delegate.dispatch(
            context,
            Runnable {
                try {
                    block.run()
                } finally {
                    finished.trySend(Unit)
                }
            },
        )
    }

    /** Consumes [n] dispatch reports, or fails with [what] when fewer tasks were handed over. */
    fun expectDispatched(n: Int, what: String) {
        repeat(n) { assertTrue(what, dispatched.tryReceive().isSuccess) }
    }

    /** Fails with [what] when a task was handed over that no earlier check consumed. */
    fun expectNoMoreDispatched(what: String) {
        assertTrue(what, dispatched.tryReceive().isFailure)
    }

    /** Waits for the "block returned" report of one task that was handed over and not yet reported. */
    fun awaitOneFinished() {
        reported++
        runBlocking { finished.receive() }
    }

    /** Waits until every task handed over has returned. The tests complete what they parked first. */
    fun awaitAllFinished() {
        val missing = handedOver - reported
        repeat(missing) { awaitOneFinished() }
    }
}

/**
 * A loader that hands out a fresh recognizer per load, in the order given,
 * and counts loads. When the queue is empty it refuses, so a surplus load
 * shows up as a wrong result and a wrong count, never as a hang.
 */
internal class QueueLoader(recognizers: List<SherpaRecognizer>) : ModelLoaderPort {
    private val queue = ArrayDeque(recognizers)

    @Volatile
    private var count = 0

    /** How often the engine asked for a model. */
    val loads: Int get() = count

    override fun load(modelId: String): ModelLoader.LoadResult {
        count++
        val next = queue.removeFirstOrNull()
            ?: return ModelLoader.LoadResult.Refused(ModelLoader.Refusal.NOT_INSTALLED, "unused detail")
        return ModelLoader.LoadResult.Ready(SherpaModel("tiny", File("unused-model-dir"), "unused-digest"), next)
    }
}

/**
 * One engine wired to every watcher the bound tests need. With [parkFirst] the
 * first load yields [parked]; every later load yields the next of [later].
 */
internal class BoundRig private constructor(
    parkFirst: Boolean,
    makeParked: (CompletableDeferred<Unit>, Channel<String>) -> ParkingRecognizer,
    later: List<SherpaRecognizer>,
) {
    constructor(parkFirst: Boolean, vararg later: SherpaRecognizer) :
        this(parkFirst, { until, log -> ParkingRecognizer(until, log) }, later.toList())

    val parkUntil = CompletableDeferred<Unit>()
    val log = Channel<String>(Channel.UNLIMITED)
    val parked = makeParked(parkUntil, log)
    val loader = QueueLoader(if (parkFirst) listOf<SherpaRecognizer>(parked) + later else later)
    val watcher = newSlotWatcher()
    val deadline = FakeDeadline()
    val workers = RecordingWorkers()
    val engine = OnDeviceSttEngine(loader, watcher, deadline, workers)

    companion object {
        /** A rig whose first load parks like [ParkingRecognizer] and then throws once it is released. */
        fun parkThenThrow(vararg later: SherpaRecognizer): BoundRig =
            BoundRig(true, { until, log -> ParkThenThrowRecognizer(until, log) }, later.toList())
    }

    /**
     * Waits for the engine to hand [call] to its slot and consumes that report. A call that
     * returns first fails with [what]. Use it for a blocking call made on another scope.
     */
    fun <T> awaitSlotTask(call: Deferred<T>, what: String) {
        val reported = runBlocking {
            select<Boolean> {
                watcher.dispatched.onReceive { true }
                call.onAwait { false }
            }
        }
        assertTrue(what, reported)
    }

    /**
     * Waits until the parked recognizer logged "enter". A call that returns first, or a second
     * task handed to the slot the call already holds, fails with [what] instead of waiting.
     */
    fun <T> awaitEnter(call: Deferred<T>, what: String) {
        val entered = runBlocking {
            select<String> {
                log.onReceive { it }
                call.onAwait { "the call returned" }
                watcher.dispatched.onReceive { "a second task for the slot" }
            }
        }
        assertEquals(what, "enter", entered)
    }

    /**
     * Drives a call whose slot task was already consumed to the point where its decode is parked,
     * fires the deadline on the calling test thread, and checks the engine saw the expiry. The
     * park is NOT released here. Every wait is raced against the call and the slot, and a failed
     * check throws before anything waits, so no broken engine can hang the test.
     */
    fun <T> expireWhileParked(call: Deferred<T>, what: String) {
        awaitEnter(call, "$what: the decode must reach the recognizer")
        assertTrue("$what: the deadline must be armed before the decode runs", deadline.armed.isCompleted)
        workers.expectDispatched(1, "$what: the decode must be handed to the decode workers")
        deadline.fire()
        assertTrue("$what: firing the deadline must mark the decode abandoned", engine.abandonedDecodeRunning)
    }

    /** Frees everything a test parked, closes the engine, and waits until no worker is left running. */
    fun stop() {
        parkUntil.complete(Unit)
        engine.close()
        workers.awaitAllFinished()
    }
}

/** A valid request of [samples] samples at [rate] Hz. At 16 kHz, 16000 samples are 1000 ms. */
internal fun clipRequest(samples: Int = 16_000, rate: Int = AudioFormat.SAMPLE_RATE_HZ): SttRequest = SttRequest(
    pcm = FloatArray(samples) { 0.1f },
    wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46),
    model = "tiny",
    language = "en",
    sampleRateHz = rate,
)
