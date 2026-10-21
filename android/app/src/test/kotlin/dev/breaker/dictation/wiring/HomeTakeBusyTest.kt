package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.ui.HomeControl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home mic busy/hold path through the wiring: a begin answer forwards to the one busy port, and a
 * flip of the shared flag re-pushes the Home's current state. The Home mic's own tile face is drawn by
 * [HomeScreenMic], which needs an Android context, so these tests watch the port calls, the flag and the
 * recording look the wiring pushes; the flag's flip-only fire is pinned by [TakeYieldHookTest].
 */
internal class HomeTakeBusyTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, log)
    private val background = FakeBackground()
    private val main = FakeMainPost()

    /** The wiring over [busy] with [home] attached; [off] is the user's stored switch choice. */
    private fun built(busy: FakeMicBusyPort, home: RecordingHomeControl, off: Boolean = false): HomeTakeWiring {
        val launcher = FakeLauncher()
        val controller = DictationServiceController(FakePermission(), launcher)
        val offStore = MemoryOffStore()
        offStore.off = off
        val switch = ArmedSwitch(controller, offStore)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        val wiring = HomeTakeWiring(
            take = { take },
            history = { store },
            clipboard = clipboard,
            switch = switch,
            owner = TakeOwner(),
            busy = busy,
            background = background,
            main = main,
            modelReady = { true },
        )
        wiring.attach(home)
        return wiring
    }

    /** A taken begin refuses as taken through the port once and leaves the shared flag set. */
    @Test
    fun `a taken begin through the wiring refuses as taken and sets the busy flag`() {
        val busy = FakeMicBusyPort()
        val home = RecordingHomeControl()
        take.beginResult = BeginResult.Taken
        built(busy, home).onTap()
        assertEquals("app: busy: a taken begin refuses as taken exactly once", listOf("beginRefusedAsTaken"), busy.calls)
        assertEquals("app: busy: the taken begin leaves the shared flag set", true, busy.busy)
    }

    /** A recording begin after the busy flag clears it and accepts exactly once. */
    @Test
    fun `a recording begin after the busy flag clears it and accepts once`() {
        val busy = FakeMicBusyPort()
        val home = RecordingHomeControl()
        val wiring = built(busy, home)
        take.beginResult = BeginResult.Taken
        wiring.onTap()
        assertEquals("app: busy: the taken begin sets the flag first", true, busy.busy)
        take.beginResult = BeginResult.Recording
        wiring.onTap()
        assertEquals("app: busy: the accepted begin clears the flag", false, busy.busy)
        assertEquals("app: busy: accept follows refuse exactly once", listOf("beginRefusedAsTaken", "beginAccepted"), busy.calls)
    }

    /** A flip of the shared flag re-pushes the Home's current state, with no begin answer at all. */
    @Test
    fun `a busy-flag flip re-pushes the current state with no begin answer`() {
        val busy = FakeMicBusyPort()
        val home = RecordingHomeControl()
        take.listeningElsewhere()   // the Home's port is recording, so the re-push shows the recording look
        built(busy, home)
        assertEquals("app: busy: nothing is pushed before the flip", emptyList<Boolean>(), home.recordings)
        busy.flipTo(true)
        assertEquals("app: busy: the flip re-pushes the recording state with no begin answer", listOf(true), home.recordings)
    }
}

/**
 * A [MicBusyPort] for the busy tests: it records the three begin answers, holds the busy flag and fires
 * the wiring's one listener on a flip — as the tile's take-yield controller's setBusy does — and can be
 * flipped by hand with [flipTo] so a test can watch the wiring re-push without a begin answer.
 */
internal class FakeMicBusyPort : MicBusyPort {
    var busy: Boolean = false
        private set
    private var listener: ((Boolean) -> Unit)? = null
    val calls: MutableList<String> = ArrayList()

    override fun micBusy(): Boolean = busy
    override fun setBusyListener(listener: ((Boolean) -> Unit)?) { this.listener = listener }
    override fun beginAccepted() { record("beginAccepted"); flip(false) }
    override fun beginRefusedAsTaken() { record("beginRefusedAsTaken"); flip(true) }
    override fun beginFailed() { record("beginFailed"); flip(false) }

    /** Flips the flag by hand (no begin answer), firing the listener as setBusy would on a flip. */
    fun flipTo(value: Boolean) = flip(value)

    private fun record(call: String) { calls.add(call) }

    private fun flip(value: Boolean) {
        if (busy == value) return   // a set that does not change the flag fires nothing
        busy = value
        listener?.invoke(value)
    }
}

/** A [HomeControl] that records every recording look it is given. */
private class RecordingHomeControl : HomeControl {
    val recordings: MutableList<Boolean> = ArrayList()
    override fun showLine(line: String?) {}
    override fun showRecording(recording: Boolean) { recordings.add(recording) }
    override fun refresh() {}
}
