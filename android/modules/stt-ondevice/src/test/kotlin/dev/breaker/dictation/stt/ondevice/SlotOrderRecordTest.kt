package dev.breaker.dictation.stt.ondevice

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Checks the slot record that the slot helpers read. No engine is built here:
 * each test writes the records by hand, or by a fake, and checks what the
 * helpers do with them. No test reads a clock and no test waits on a timer.
 */
class SlotOrderRecordTest {

    // A net for the failure arm only: a broken helper must end the test with a message, never a hang.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    private val message = "the slot record check failed"

    /** Hands one no-op task to [watcher], which writes "dispatch" and reports it. */
    private fun handOver(watcher: SlotWatcher) {
        watcher.dispatch(EmptyCoroutineContext, Runnable { })
    }

    /** Runs [check] and returns the assertion it threw, or null when it passed. */
    private fun failureOf(check: () -> Unit): AssertionError? =
        try {
            check()
            null
        } catch (e: AssertionError) {
            e
        }

    @Test
    fun `a dispatch record followed by an enter record lets the slot helper return`() {
        val trace = Channel<String>(Channel.UNLIMITED)
        val watcher = newSlotWatcher(trace)
        handOver(watcher)
        trace.trySend("enter")

        expectSlotBeforeDecode(watcher, trace, message)

        assertEquals(listOf("enter"), drain(trace))
        watcher.expectNoMoreDispatched("the helper must consume the hand-over report it read")
    }

    @Test
    fun `an enter record before any hand-over makes the slot helper fail with its own message`() {
        val trace = Channel<String>(Channel.UNLIMITED)
        val watcher = newSlotWatcher(trace)
        trace.trySend("enter")

        val failure = failureOf { expectSlotBeforeDecode(watcher, trace, message) }

        assertEquals(message, failure?.message)
    }

    @Test
    fun `a watched call that completes with no hand-over makes the bound wait fail with its message`() {
        val rig = BoundRig(false, CountingRecognizer())
        try {
            val completed = CompletableDeferred<Int>()
            completed.complete(1)
            val call: Deferred<Int> = completed

            val failure = failureOf { rig.awaitSlotTask(call, message) }

            assertEquals(message, failure?.message)
        } finally {
            rig.stop()
        }
    }

    @Test
    fun `a hand-over appended by another thread before the helper waits is read first`() {
        val trace = Channel<String>(Channel.UNLIMITED)
        val watcher = newSlotWatcher(trace)
        runBlocking {
            async(Dispatchers.IO) { handOver(watcher) }.await()
        }

        expectSlotBeforeDecode(watcher, trace, message)

        assertEquals(emptyList<String>(), drain(trace))
        watcher.expectNoMoreDispatched("the helper must consume the hand-over report it read")
    }

    @Test
    fun `a hand-over appended by another thread while the helper waits is read first`() {
        val trace = Channel<String>(Channel.UNLIMITED)
        val watcher = newSlotWatcher(trace)
        val outcome = runBlocking {
            val helper = async(Dispatchers.IO) {
                failureOf { expectSlotBeforeDecode(watcher, trace, message) }
            }
            val producer = async(Dispatchers.IO) { handOver(watcher) }
            producer.await()
            helper.await()
        }

        assertNull("the helper must return without failing", outcome)
        watcher.expectNoMoreDispatched("the helper must consume the hand-over report it read")
    }

    @Test
    fun `two hand-overs in a row are consumed one per helper read`() {
        val trace = Channel<String>(Channel.UNLIMITED)
        val watcher = newSlotWatcher(trace)
        handOver(watcher)
        handOver(watcher)

        expectSlotBeforeDecode(watcher, trace, message)

        assertEquals(listOf("dispatch"), drain(trace))
        watcher.expectDispatched(1, "the second hand-over report must still be pending")
        watcher.expectNoMoreDispatched("no hand-over report may be left after both were consumed")
    }

    @Test
    fun `a decode writes enter to the slot record and enter then exit to its own log`() {
        val trace = Channel<String>(Channel.UNLIMITED)
        val log = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        release.complete(Unit)

        HoldingRecognizer(release, log, trace).decode(FloatArray(0), 16_000)

        assertEquals(listOf("enter"), drain(trace))
        assertEquals(listOf("enter", "exit"), drain(log))
    }

    @Test
    fun `an enter record ahead of a pending hand-over report still makes the slot helper fail`() {
        val trace = Channel<String>(Channel.UNLIMITED)
        val watcher = newSlotWatcher(trace)
        watcher.dispatched.trySend(Unit)
        trace.trySend("enter")

        val failure = failureOf { expectSlotBeforeDecode(watcher, trace, message) }

        assertEquals(message, failure?.message)
    }

    @Test
    fun `a completion record ahead of a pending hand-over report still makes the bound wait fail`() {
        val rig = BoundRig(false, CountingRecognizer())
        try {
            rig.watcher.dispatched.trySend(Unit)
            val completed = CompletableDeferred<Int>()
            completed.complete(1)
            val call: Deferred<Int> = completed

            val failure = failureOf { rig.awaitSlotTask(call, message) }

            assertEquals(message, failure?.message)
        } finally {
            rig.stop()
        }
    }

    @Test
    fun `a bound wait on a handed-over call consumes the hand-over report`() {
        val rig = BoundRig(false, CountingRecognizer())
        try {
            rig.watcher.dispatch(EmptyCoroutineContext, Runnable { })
            val call = CompletableDeferred<Int>()

            rig.awaitSlotTask(call, message)
            rig.watcher.expectNoMoreDispatched("the bound wait must consume the hand-over report it read")
            call.complete(1)
            rig.awaitCompletion(message)
        } finally {
            rig.stop()
        }
    }

    @Test
    fun `a hand-over is recorded before the task it forwards can write its own record`() {
        val trace = Channel<String>(Channel.UNLIMITED)
        val direct = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                block.run()
            }
        }
        val watcher = SlotWatcher(direct, trace)

        watcher.dispatch(EmptyCoroutineContext, Runnable { trace.trySend("enter") })

        assertEquals(listOf("dispatch", "enter"), drain(trace))
    }
}
