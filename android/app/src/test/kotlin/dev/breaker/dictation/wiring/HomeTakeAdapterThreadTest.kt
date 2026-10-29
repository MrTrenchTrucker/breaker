package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.usecase.SendResult
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Home begin runs on the shared take worker, never on the calling thread. Each test guards a specific
 claim in its KDoc and fails if that claim is false; each test waits only on signals via a bounded latch
 (max 5 s), never on a clock; one worker thread at a time, no swarm or busy loop. Each test builds its
 own fakes so HomeTakeFakes.kt stays untouched.
 */
internal class HomeTakeAdapterThreadTest {

    // ---- helpers shared by the tests below ----

    /** One real single-thread executor as [Background]; captures that one worker thread and records each submit in order. */
    private class RecordingWorker : Background {
        private val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "breaker-dictation").apply { isDaemon = true } }
        @Volatile var worker: Thread? = null
        @Volatile private var submitted = 0
        val done = CountDownLatch(1)

        override fun submit(block: () -> Unit) {
            submitted += 1
            ex.execute {
                if (worker == null) worker = Thread.currentThread()
                try {
                    block()
                } finally {
                    // Counts down whatever happens — a begin that throws must still release the latch.
                    done.countDown()
                }
            }
        }

        fun submitCount(): Int = submitted

        /** Wait for the single submit block to finish its work; bounded so a hang fails the test, not hangs it. */
        fun await(timeoutMs: Long = 5_000): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** A take that records the thread which ran its begin and what it actually did; it always looks like it holds a recording. */
    private class RecordingTake : TakePort {
        var beginCalledOn: Thread? = null
        val ran = ArrayList<String>()
        override val sessionState: DictationState get() = DictationState.RECORDING
        override fun begin(): BeginResult {
            beginCalledOn = Thread.currentThread()
            ran.add("begin")
            return BeginResult.Recording
        }

        override fun cancel() {
            ran.add("cancel")
        }

        override fun send(): SendResult? = null
        override fun resetAfterTaken() {}
        override fun finish(trimBeforeMs: Long?): FinishResult = FinishResult.ReadyToSend(SAMPLE)
    }

    /** A take whose begin throws, so a submit block never runs its answer. */
    private class ThrowingBeginTake : TakePort {
        override val sessionState: DictationState get() = DictationState.RECORDING
        override fun begin(): BeginResult { throw IllegalStateException("begin broke") }

        override fun cancel() {}
        override fun send(): SendResult? = null
        override fun resetAfterTaken() {}
        override fun finish(trimBeforeMs: Long?): FinishResult = FinishResult.ReadyToSend(SAMPLE)
    }

    /** A take whose begin records, so the owner holds it until cancelled or finished. */
    private class RecordingBeginTake : TakePort {
        val ran = ArrayList<String>()
        @Volatile private var session = DictationState.IDLE
        override val sessionState: DictationState get() = session

        override fun begin(): BeginResult {
            ran.add("begin")
            session = DictationState.RECORDING
            return BeginResult.Recording
        }

        override fun cancel() {
            // A cancel that returns the owner to IDLE, so a following begin runs.
            ran.add("cancel")
            session = DictationState.IDLE
        }

        override fun send(): SendResult? = null
        override fun resetAfterTaken() {}
        override fun finish(trimBeforeMs: Long?): FinishResult = FinishResult.ReadyToSend(SAMPLE)
    }

    /** Queues every posted block until [drain] runs them on the test thread; records what they called. */
    private class QueuingMainPost : MainPost {
        val queued = ArrayList<() -> Unit>()

        override fun post(block: () -> Unit) { queued.add(block) }

        fun drain() {
            while (queued.isNotEmpty()) queued.removeAt(0)()
        }
    }

    /** Runs posted blocks at once, in order — the inline [MainPost], recording no lines. */
    private class InlineMainPost : MainPost {
        override fun post(block: () -> Unit) = block()
    }

    /** A background whose submit throws immediately, so a begin or cancel never runs its block. */
    private class ThrowingBackground : Background {
        override fun submit(block: () -> Unit) {
            throw IllegalStateException("background refused")
        }
    }

    /** The one owner per test instance, so the adapter and OwnedTakePort share the same holder. */
    private val owner = TakeOwner()

    /**
     * Builds the armed switch exactly as [HomeTakeAdapterTest.homeSwitch] builds it: a controller over the
     * fake launcher, an in-memory off store whose [off] is set, and the state observer wiring the switch's
     * arms to the controller. Returns the raw [ArmedSwitch] so a tap can be armed or stored off.
     */
    private fun armed(off: Boolean): ArmedSwitch {
        val launcher = FakeLauncher()
        val controller = DictationServiceController(FakePermission(), launcher)
        val offStore = MemoryOffStore()
        offStore.off = off
        val switch = ArmedSwitch(controller, offStore)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        return switch
    }

    /** Wrap [take] so the Home owner tracks it until settle; reuse by every adapter() call in this test. */
    private fun owned(take: TakePort) = OwnedTakePort(take, owner, TakeOwnerKind.HOME)

    private fun adapter(
        off: Boolean,
        background: Background,
        take: TakePort,
        main: MainPost,
        lines: MutableList<String?> = ArrayList(),
        states: MutableList<DictationState> = ArrayList(),
    ) = HomeTakeAdapter(
        take = owned(take),
        owner = owner,
        settle = TakeSettle(null, FakeTakenClipboard()),
        switch = armed(off),
        background = background,
        main = main,
        onLine = { lines.add(it) },
        onState = { states.add(it) },
    )

    /** A begin runs on the shared background worker, never the caller thread. */
    @Test
    fun `a Home begin records its worker thread inside the submit block`() {
        val bg = RecordingWorker()
        val take = RecordingTake()

        adapter(off = false, background = bg, take = take, main = InlineMainPost()).onTap()

        // Wait for the single submit to finish before inspecting what it recorded.
        assertTrue("app: home: the worker must run before we assert its identity", bg.await())

        assertSame("app: home: begin must run on the shared background worker", bg.worker, take.beginCalledOn)
        assertNotSame("app: home: begin must never run on the caller thread", Thread.currentThread(), take.beginCalledOn)
    }

    /** The stored-off tap submits nothing at all; only the off line is answered. */
    @Test
    fun `a stored-off Home tap submits no begin`() {
        val bg = RecordingWorker()
        val take = RecordingTake()
        val lines = ArrayList<String?>()
        val states = ArrayList<DictationState>()

        adapter(off = true, background = bg, take = take, main = InlineMainPost(), lines = lines, states = states).onTap()

        assertEquals("app: home: a stored-off tap begins nothing", 0, bg.submitCount())
        assertEquals("app: home: a stored-off tap answers the off line", HomeSentences.SWITCHED_OFF, lines.single())
        assertEquals("app: home: a stored-off tap pushes no state", emptyList<DictationState>(), states)
    }

    /** Nothing is shown before drain runs; after drain the RECORDING state is pushed once. */
    @Test
    fun `nothing is shown before main runs, then RECORDING is pushed once`() {
        val bg = RecordingWorker()
        val take = RecordingTake()
        val lines = ArrayList<String?>()
        val states = ArrayList<DictationState>()
        val main = QueuingMainPost()

        adapter(off = false, background = bg, take = take, main = main, lines = lines, states = states).onTap()

        // The begin ran on the worker and only queued its post; nothing has run on main yet.
        assertTrue("app: home: the worker must run before we inspect main", bg.await())
        assertEquals("app: home: nothing is shown before drain", 0, lines.size)
        assertEquals("app: home: nothing is shown before drain", 0, states.size)

        // Draining runs the queued post exactly once, showing RECORDING a single time.
        main.drain()
        assertEquals("app: home: RECORDING is pushed once after drain", listOf(DictationState.RECORDING), states)
    }

    /** The actual take ran begin, cancel, then begin again — in that exact order over one FIFO worker. */
    @Test
    fun `the Home hold cancels the first begin and a quick re-begin follows`() {
        // Inline Background: blocks run at submit time (in call order), no position labels — the proof is take.ran == [begin, cancel, begin].
        val bg = object : Background {
            override fun submit(block: () -> Unit) = block()
        }
        val take = RecordingBeginTake()

        val cancelStates = ArrayList<DictationState>()
        adapter(off = false, background = bg, take = take, main = InlineMainPost(), lines = ArrayList(), states = cancelStates).onTap()
        adapter(off = false, background = bg, take = take, main = InlineMainPost(), lines = ArrayList(), states = cancelStates).onCancel()
        adapter(off = false, background = bg, take = take, main = InlineMainPost(), lines = ArrayList(), states = cancelStates).onTap()

        // The actual run order proves begin, cancel, begin.
        assertEquals("app: home: the actual run order is begin, cancel, begin", listOf("begin", "cancel", "begin"), take.ran)
        // Owner marked HOME by OwnedTakePort.begin (owner.begun(HOME)); after onCancel it must no longer be held, so the follow-up tap begins; onCancel also pushes IDLE last → states == [RECORDING, IDLE, RECORDING].
        assertEquals("app: home: onCancel leaves states ending with IDLE", listOf(DictationState.RECORDING, DictationState.IDLE, DictationState.RECORDING), cancelStates)
    }

    /** A begin that throws answers COULD_NOT_RECORD and pushes no state. */
    @Test
    fun `a thrown begin answers COULD_NOT_RECORD and pushes no state`() {
        // Inline Background (runs the submit block on the caller thread) so lines.last() reads after begin has thrown, no race.
        val bg = object : Background {
            override fun submit(block: () -> Unit) = block()
        }
        val main = InlineMainPost()
        val take = ThrowingBeginTake()
        val lines = ArrayList<String?>()
        val states = ArrayList<DictationState>()

        adapter(off = false, background = bg, take = take, main = main, lines = lines, states = states).onTap()

        assertEquals("app: home: a thrown begin answers COULD_NOT_RECORD", RunnerSentences.COULD_NOT_RECORD, lines.last())
        assertEquals("app: home: a thrown begin pushes no RECORDING state", 0, states.count { it == DictationState.RECORDING })
    }

    /** A background whose submit throws: onTap answers COULD_NOT_RECORD and pushes no state. */
    @Test
    fun `a submit-throwing background answers COULD_NOT_RECORD and pushes no state`() {
        val bg = ThrowingBackground()
        val main = InlineMainPost()
        val take = RecordingTake()
        val lines = ArrayList<String?>()
        val states = ArrayList<DictationState>()

        adapter(off = false, background = bg, take = take, main = main, lines = lines, states = states).onTap()

        assertEquals("app: home: a submit-refuse answers COULD_NOT_RECORD", RunnerSentences.COULD_NOT_RECORD, lines.last())
        assertEquals("app: home: a submit-refuse pushes no RECORDING state", 0, states.count { it == DictationState.RECORDING })
    }
}
