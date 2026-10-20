package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.usecase.SendResult
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Three tests: the cancel runs on the shared background worker, never on the caller thread, and is queued
 * before a later begin; a refused cancel still releases the owner it holds and pushes IDLE. Each test waits
 * only on a bounded marker task (5 s) on the one worker thread, and builds its own fakes so neither
 * HomeTakeFakes nor the neighbour tests are edited.
 */
internal class HomeTakeAdapterCancelTest {

    private val owner = TakeOwner()

    // ---- helpers: real single-thread background + inline main, exactly like the neighbour test ----

    /** One real single-thread executor as [Background]; records the worker thread; each await hands it a marker task. */
    private class RecordingBackground : Background {
        private val ex = Executors.newSingleThreadExecutor { Thread(it, "breaker-dictation").apply { isDaemon = true } }
        @Volatile var worker: Thread? = null

        override fun submit(block: () -> Unit) = ex.execute { if (worker == null) worker = Thread.currentThread(); block() }

        /** Wait for every earlier submit to have run before this marker; the FIFO executor releases it once all prior blocks ran. */
        fun await(): Boolean {
            val l = CountDownLatch(1)
            ex.execute { l.countDown() }
            return l.await(5_000, TimeUnit.MILLISECONDS)
        }
    }

    /** Runs posted blocks inline on the calling thread (the [MainPost] mirror used by the neighbour test). */
    private class InlineMainPost : MainPost {
        override fun post(block: () -> Unit) = block()
    }

    /** A background whose submit throws, so a cancel never runs its block. */
    private class RefusingBackground : Background {
        override fun submit(block: () -> Unit) = throw IllegalStateException("background refused")
    }

    /** A take that records the thread which cancelled it and looks like it always holds a recording. */
    private class ThreadRecordingTake : TakePort {
        @Volatile var cancelThread: Thread? = null
        override val sessionState: DictationState get() = DictationState.RECORDING
        override fun begin(): BeginResult = BeginResult.Recording
        override fun cancel() { cancelThread = Thread.currentThread() }
        override fun send(): SendResult? = null
        override fun resetAfterTaken() {}
        override fun finish(): FinishResult = FinishResult.ReadyToSend(SAMPLE)
    }

    /** A take that records each submit's label in order and goes back to IDLE after a cancel, so a later tap begins. */
    private class FifoTake : TakePort {
        val ran = ArrayList<String>()
        @Volatile private var recording = true
        override val sessionState: DictationState get() = if (recording) DictationState.RECORDING else DictationState.IDLE
        override fun begin(): BeginResult { ran.add("begin"); recording = true; return BeginResult.Recording }
        override fun cancel() { ran.add("cancel"); recording = false }
        override fun send(): SendResult? = null
        override fun resetAfterTaken() { recording = false }
        override fun finish(): FinishResult = FinishResult.ReadyToSend(SAMPLE)
    }

    /** The switch the Home take shares, over the in-memory off-store; [off] is the user's stored choice. */
    private fun armed(off: Boolean): ArmedSwitch {
        val launcher = FakeLauncher()
        val controller = DictationServiceController(FakePermission(), launcher)
        val offStore = MemoryOffStore()
        offStore.off = off
        val switch = ArmedSwitch(controller, offStore)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        return switch
    }

    /** Builds the adapter exactly as the neighbour tests do: an owned take, a settle, the switch and the fakes. */
    private fun adapter(off: Boolean, background: Background, take: TakePort, states: MutableList<DictationState>): HomeTakeAdapter =
        HomeTakeAdapter(
            take = OwnedTakePort(take, owner, TakeOwnerKind.HOME),
            owner = owner,
            settle = TakeSettle(null, FakeTakenClipboard()),
            switch = armed(off),
            background = background,
            main = InlineMainPost(),
            onLine = {},
            onState = { states.add(it) },
        )

    /** The cancel runs on the worker thread, never on the caller thread. */
    @Test
    fun `a cancel runs on the worker thread and never on the caller thread`() {
        val bg = RecordingBackground()
        val take = ThreadRecordingTake()
        val states = ArrayList<DictationState>()

        adapter(off = false, background = bg, take = take, states = states).onTap() // begin holds the take HOME
        assertTrue("app: home: the begin must run before cancel", bg.await())

        adapter(off = false, background = bg, take = take, states = states).onCancel() // cancel queued on the worker
        assertTrue("app: home: the cancel must run before we assert", bg.await())

        assertNotSame("app: home: a take cancelled here was never run on the caller thread", Thread.currentThread(), take.cancelThread)
        assertEquals("app: home: the cancel must run on the shared background worker", bg.worker, take.cancelThread)
    }

    /** A cancel is queued on the single FIFO worker before a later begin, so a quick tap cannot start the take before that cancel runs; it pins the run order and the IDLE→RECORDING state. */
    @Test
    fun `a cancel queues before a later begin on the one worker and pushes IDLE`() {
        owner.begun(TakeOwnerKind.HOME)
        assertTrue("app: home: the owner is held HOME before cancel", owner.heldByHome())

        val bg = RecordingBackground()
        val take = FifoTake()
        val states = ArrayList<DictationState>()

        adapter(off = false, background = bg, take = take, states = states).onCancel() // cancel first
        assertTrue("app: home: the cancel must run before the follow-up tap", bg.await())

        adapter(off = false, background = bg, take = take, states = states).onTap() // owner settled -> beginFromTap
        assertTrue("app: home: the begin must run after the cancel", bg.await())

        assertEquals("app: home: cancel then begin on one worker", listOf("cancel", "begin"), take.ran)
        assertEquals("app: home: the cancel settles IDLE last", listOf(DictationState.IDLE, DictationState.RECORDING), states)
    }

    /** A refusing background never runs OwnedTakePort.cancel, so only onCancel's own owner.settled() releases the owner. */
    @Test
    fun `a refused cancel still releases the owner the Home held`() {
        val take = FifoTake()
        val states = ArrayList<DictationState>()

        owner.begun(TakeOwnerKind.HOME)
        assertTrue("app: home: the owner is held HOME before cancel", owner.heldByHome())

        states.clear()
        adapter(off = false, background = RefusingBackground(), take = take, states = states).onCancel()

        assertFalse("app: home: a refused cancel still releases the owner", owner.heldByHome())
        assertEquals("app: home: a refused cancel settles IDLE", listOf(DictationState.IDLE), states)
    }
}
