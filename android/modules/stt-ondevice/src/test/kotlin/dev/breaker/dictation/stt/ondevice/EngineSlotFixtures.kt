package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.SttRequest
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import org.junit.Assert.assertTrue

/*
 * Shared helpers for the engine tests that care about the single decode slot.
 * Nothing here keeps state between tests: every helper builds a fresh value.
 */

/**
 * A dispatcher that reports every task handed to it, then forwards the task to
 * the real [delegate]. It exists so a test can see the moment an engine puts
 * work on its own slot, which happens before any wait on that slot.
 *
 * With a [trace], each hand-over also appends "dispatch" to it. The append comes
 * after the report and before the task is forwarded, so a task that runs and
 * then writes to the same trace always lands after "dispatch".
 */
internal class SlotWatcher(
    private val delegate: CoroutineDispatcher,
    private val trace: Channel<String>? = null,
) : CoroutineDispatcher() {

    /** One element per task handed to this dispatcher, in the order handed. */
    val dispatched = Channel<Unit>(Channel.UNLIMITED)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched.trySend(Unit)
        trace?.trySend("dispatch")
        delegate.dispatch(context, block)
    }

    /** Consumes [n] reports, or fails with [what] when fewer tasks were handed over. */
    fun expectDispatched(n: Int, what: String) {
        repeat(n) { assertTrue(what, dispatched.tryReceive().isSuccess) }
    }

    /** Fails with [what] when a task was handed over that no earlier check consumed. */
    fun expectNoMoreDispatched(what: String) {
        assertTrue(what, dispatched.tryReceive().isFailure)
    }
}

/** A watcher over a fresh single-slot view of the shared IO pool, the shape the engine uses by default. */
internal fun newSlotWatcher(trace: Channel<String>? = null): SlotWatcher =
    SlotWatcher(Dispatchers.IO.limitedParallelism(1), trace)

/**
 * Waits for [call] to finish. When the engine hands a second task to the slot
 * its caller already holds, the wait ends with an assertion carrying [message]
 * instead of waiting forever. The test must consume the reports it expects
 * with [SlotWatcher.expectDispatched] before it calls this.
 */
internal fun <T> awaitUnlessStuck(call: Deferred<T>, watcher: SlotWatcher, message: String): T =
    runBlocking {
        select<T> {
            call.onAwait { it }
            watcher.dispatched.onReceive { throw AssertionError(message) }
        }
    }

/**
 * Waits for [call] to finish, without a result. When the engine hands a second
 * task to the slot its caller already holds, the wait ends with an assertion
 * carrying [message] instead of waiting forever. The test must consume the
 * reports it expects with [SlotWatcher.expectDispatched] before it calls this.
 */
internal fun joinUnlessStuck(call: Job, watcher: SlotWatcher, message: String) {
    runBlocking {
        select<Unit> {
            call.onJoin { }
            watcher.dispatched.onReceive { throw AssertionError(message) }
        }
    }
}

/** Empties [channel] without waiting and returns what it held, in order. */
internal fun <T> drain(channel: Channel<T>): List<T> {
    val out = ArrayList<T>()
    while (true) {
        val received = channel.tryReceive()
        if (!received.isSuccess) break
        out.add(received.getOrThrow())
    }
    return out
}

/** A loader that always answers with the same result. */
internal class FakeLoader(private val result: ModelLoader.LoadResult) : ModelLoaderPort {
    override fun load(modelId: String): ModelLoader.LoadResult = result
}

/** A loader that always yields [recognizer], so a test can pick the recognizer it watches. */
internal fun readyLoader(recognizer: SherpaRecognizer): FakeLoader =
    FakeLoader(
        ModelLoader.LoadResult.Ready(
            SherpaModel("tiny", File("unused-model-dir"), "unused-digest"),
            recognizer,
        ),
    )

/** One fixed, valid request, so no test has to repeat the audio fields. */
internal fun validSlotRequest(): SttRequest = SttRequest(
    pcm = floatArrayOf(0.1f, 0.2f, 0.3f),
    wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46),
    model = "tiny",
    language = "en",
    sampleRateHz = AudioFormat.SAMPLE_RATE_HZ,
)

/**
 * A recognizer that counts decodes. The count is written inside the decode and
 * read by a test after the call has completed, which orders the two.
 */
internal class CountingRecognizer : SherpaRecognizer {
    var count = 0

    override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
        count++
        return SherpaTranscript("hello", emptyList(), "en")
    }

    override fun release() {}
}

/**
 * A recognizer whose decode holds the slot until [holdUntil] completes, and
 * logs "enter" and "exit" on [log] in the order they really happen.
 *
 * decode is a blocking call, so a fake can hold the slot only by parking the
 * slot's thread on a signal. A test that uses this completes [holdUntil] in a
 * finally block, so a failing assertion never leaves the slot held.
 *
 * With a [trace], "enter" is also appended to it at the same point as on [log].
 */
internal class HoldingRecognizer(
    private val holdUntil: CompletableDeferred<Unit>,
    val log: Channel<String>,
    private val trace: Channel<String>? = null,
) : SherpaRecognizer {
    override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
        log.trySend("enter")
        trace?.trySend("enter")
        runBlocking { holdUntil.await() }
        log.trySend("exit")
        return SherpaTranscript("hello", emptyList(), "en")
    }

    override fun release() {}
}

/**
 * Makes [call] on a thread of [callers] and returns a handle to what it gave
 * back. A call that parks its own thread (a decode run inline by its caller)
 * then parks a thread of [callers], never the thread of the test.
 */
internal fun <T> startOffThread(callers: CoroutineScope, call: () -> Deferred<T>): Deferred<Deferred<T>> =
    callers.async { call() }

/**
 * Reads the next record of [trace] and checks that it is "dispatch", the record a
 * hand-over to the engine's slot leaves. A correct engine writes that record
 * before it forwards the task, so the task's decode ("enter") can never come
 * first. Any other first record fails with [message]: "enter" means the decode
 * started before the call was handed over, and "complete" means the call ended
 * without one. The report that the same hand-over sent on [watcher] is consumed
 * here. Only one record is read.
 */
internal fun expectSlotBeforeDecode(watcher: SlotWatcher, trace: Channel<String>, message: String) {
    val first = runBlocking { trace.receive() }
    assertTrue(message, first == "dispatch")
    assertTrue(message, watcher.dispatched.tryReceive().isSuccess)
}
