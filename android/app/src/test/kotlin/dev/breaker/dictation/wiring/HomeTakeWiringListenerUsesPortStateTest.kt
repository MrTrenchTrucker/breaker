package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.ui.HomeControl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE SWITCH LISTENER RE-PUSHES THE PORT'S OWN STATE, NOT A FIXED IDLE.
 *
 * `HomeTakeWiring.switchListener` is `{ main.post { rePushState(port.sessionState) } }`, so a flip of the
 * armed switch repaints the mic with the face the port's CURRENT state derives. A variant that re-pushed
 * `rePushState(DictationState.IDLE)` instead would paint the idle-derived face (ARMED while the switch is
 * on) over a recording take.
 *
 * WHAT THIS FILE PINS (behaviour, not a source scan): the Home's port is reporting RECORDING, the wiring is
 * built over it, and an ARM re-pushes the RECORDING face and the recording look. The creation push is
 * watched too: it is computed while the switch is still off, so it is IDLE, and the arm's push is the one
 * that must carry the port's own state.
 *
 * WHY THE ROW ONLY PASSES WITH THE PORT'S OWN STATE: the assert reads the face the listener pushed to
 * the mic, which is the very value a fixed-IDLE re-push corrupts - RECORDING becomes ARMED, and the
 * recording look becomes false.
 */
internal class HomeTakeWiringListenerUsesPortStateTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, log)
    private val owner = TakeOwner()
    private val background = FakeBackground()
    private val main = FakeMainPost()
    private val busy = FakeMicBusyPort()

    /** The wiring over a REAL controller and switch, with [home] attached and the fake mic installed. */
    private fun built(): ListenerStateBuilt {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, MemoryOffStore())
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        val mic = ListenerStateMic()
        val home = ListenerStateHomeControl()
        val wiring = HomeTakeWiring(
            take = { take },
            history = { store },
            clipboard = clipboard,
            switch = switch,
            owner = owner,
            busy = busy,
            background = background,
            main = main,
            modelReady = { true },
        )
        wiring.attach(home)
        wiring.installScreenMic(mic)
        return ListenerStateBuilt(wiring, controller, switch, mic, home)
    }

    private class ListenerStateBuilt(
        val wiring: HomeTakeWiring,
        val controller: DictationServiceController,
        val switch: ArmedSwitch,
        val mic: ListenerStateMic,
        val home: ListenerStateHomeControl,
    )

    /** The port reports RECORDING before the wiring is built and still does when the switch is armed. */
    @Test
    fun `an arm with the port recording re-pushes the recording face from the port state`() {
        take.listeningElsewhere()
        val built = built()
        assertEquals(
            "app: port-state re-push: the creation push is computed while the switch is still off, so it is IDLE",
            listOf(TileState.IDLE),
            built.mic.faces,
        )
        built.switch.switchOn()
        assertEquals(
            "app: port-state re-push: the arm must re-push the port's own state, so the face stays RECORDING and not ARMED or IDLE",
            listOf(TileState.IDLE, TileState.RECORDING),
            built.mic.faces,
        )
        assertEquals(
            "app: port-state re-push: the arm must re-push the recording look of the port's own state",
            listOf(true, true),
            built.home.recordings,
        )
    }
}

/** A [HomeMicPort] that records every face it is pushed. */
private class ListenerStateMic : HomeMicPort {
    val faces: MutableList<TileState> = ArrayList()

    override fun setState(state: TileState) {
        faces.add(state)
    }

    /** The level feed is not watched here; the face is. */
    override fun setLevel(level: Float) {}
}

/** A [HomeControl] that records every recording look it is given. */
private class ListenerStateHomeControl : HomeControl {
    val recordings: MutableList<Boolean> = ArrayList()

    override fun showLine(line: String?) {}

    override fun showRecording(recording: Boolean) {
        recordings.add(recording)
    }

    override fun refresh() {}
}
