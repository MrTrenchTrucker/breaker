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
 */
internal class SlotWatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {

    /** One element per task handed to this dispatcher, in the order handed. */
    val dispatched = Channel<Unit>(Channel.UNLIMITED)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched.trySend(Unit)
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
internal fun newSlotWatcher(): SlotWatcher = SlotWatcher(Dispatchers.IO.limitedParallelism(1))

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
 */
internal class HoldingRecognizer(
    private val holdUntil: CompletableDeferred<Unit>,
    val log: Channel<String>,
) : SherpaRecognizer {
    override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
        log.trySend("enter")
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
 * Waits until [watcher] reports a task, and fails with [message] when the
 * decode logs "enter" on [log] first. A decode that starts before the call was
 * handed to the engine's own dispatcher never reported a task, so the wait ends
 * with an assertion instead of waiting for a report that never comes. When both
 * are ready the report wins, so a correct engine, which reports before it
 * forwards, never trips it.
 */
internal fun expectSlotBeforeDecode(watcher: SlotWatcher, log: Channel<String>, message: String) {
    runBlocking {
        select<Unit> {
            watcher.dispatched.onReceive { }
            log.onReceive { throw AssertionError(message) }
        }
    }
}
