package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home begin's three busy-port calls, at the adapter: each begin answer forwards to its own port
 * method and to no other, and each call runs inside the main-post block, on the main thread like the
 * line and the state — never at submit time on the background worker.
 */
internal class HomeTakeAdapterBusyTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val settle = TakeSettle(store, clipboard)
    private val states = ArrayList<DictationState>()

    /** The adapter over a fresh owner and [take], with [busy] and the shared fakes. */
    private fun adapter(
        busy: MicBusyPort,
        background: Background,
        main: MainPost,
        take: FakeHomeTake,
        off: Boolean = false,
    ): HomeTakeAdapter {
        val owner = TakeOwner()
        return HomeTakeAdapter(
            take = OwnedTakePort(take, owner, TakeOwnerKind.HOME),
            owner = owner,
            settle = settle,
            switch = homeSwitch(off).switch,
            background = background,
            main = main,
            busy = busy,
            onLine = { },
            onState = { state -> states.add(state) },
        )
    }

    private fun homeSwitch(off: Boolean): HomeSwitch {
        val launcher = FakeLauncher()
        val controller = DictationServiceController(FakePermission(), launcher)
        val offStore = MemoryOffStore()
        offStore.off = off
        val switch = ArmedSwitch(controller, offStore)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        return HomeSwitch(switch, launcher)
    }

    private class HomeSwitch(val switch: ArmedSwitch, val launcher: FakeLauncher)

    /** A Taken answer forwards to beginRefusedAsTaken and to no other busy method. */
    @Test
    fun `a taken begin forwards to beginRefusedAsTaken and no other busy method`() {
        val busy = FakeMicBusyPort()
        val take = FakeHomeTake(committer, log)
        take.beginResult = BeginResult.Taken
        adapter(busy, FakeBackground(), FakeMainPost(), take).onTap()
        assertEquals("app: busy: a taken begin forwards only to beginRefusedAsTaken", listOf("beginRefusedAsTaken"), busy.calls)
    }

    /** A Recording answer forwards to beginAccepted and to no other busy method. */
    @Test
    fun `a recording begin forwards to beginAccepted and no other busy method`() {
        val busy = FakeMicBusyPort()
        val take = FakeHomeTake(committer, log)
        take.beginResult = BeginResult.Recording
        adapter(busy, FakeBackground(), FakeMainPost(), take).onTap()
        assertEquals("app: busy: a recording begin forwards only to beginAccepted", listOf("beginAccepted"), busy.calls)
    }

    /** A Failed answer forwards to beginFailed and to no other busy method. */
    @Test
    fun `a failed begin forwards to beginFailed and no other busy method`() {
        val busy = FakeMicBusyPort()
        val take = FakeHomeTake(committer, log)
        take.beginResult = BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD)
        adapter(busy, FakeBackground(), FakeMainPost(), take).onTap()
        assertEquals("app: busy: a failed begin forwards only to beginFailed", listOf("beginFailed"), busy.calls)
    }

    /** The busy-port call runs inside the main-post block, not at submit time on the background worker. */
    @Test
    fun `the busy-port call runs inside the main post, not at submit time`() {
        val busy = FakeMicBusyPort()
        val main = QueuingMainPost()
        val take = FakeHomeTake(committer, log)
        take.beginResult = BeginResult.Recording
        adapter(busy, FakeBackground(), main, take).onTap()
        assertEquals("app: busy: the port call is deferred into the post block", emptyList<String>(), busy.calls)
        main.drain()
        assertEquals("app: busy: draining the post runs the port call once", listOf("beginAccepted"), busy.calls)
    }
}

/** A [MainPost] that holds each block until [drain] runs it on the test thread. */
private class QueuingMainPost : MainPost {
    val queued: MutableList<() -> Unit> = ArrayList()
    override fun post(block: () -> Unit) { queued.add(block) }
    fun drain() { while (queued.isNotEmpty()) queued.removeAt(0)() }
}
