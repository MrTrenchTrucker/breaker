package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.ui.HomeControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home mic's face on arm and disarm: with the dictation service armed and nothing recording, the
 * mic must be pushed ARMED (the green ring); disarmed, IDLE. The wiring derives the face from the
 * process-wide [DictationServiceController.isArmed] at the moment it pushes, and nothing re-pushes
 * when the armed switch flips — so the mic keeps the face it was given when it was created, which is
 * IDLE after a fresh launch and ARMED after a recreate.
 *
 * The mic is a [HomeScreenMic], which wraps an Android view, so a JVM test cannot stand one in: the
 * test installs a [RecordingMic] through [HomeTakeWiring.installScreenMic] — the seam that does what
 * [HomeTakeWiring.createScreenMic] does once it has built the real mic — and watches the faces the
 * wiring pushes. The wiring is built over a REAL controller and switch, with the app's state observer
 * registered before it, as [dev.breaker.dictation.BreakerApp] registers it in onCreate before the
 * activity builds the wiring.
 *
 * RED on the base: the arm and the disarm never re-push, and the fresh-launch order computes the face
 * while unarmed and never corrects it. The recording row is the guard that a fix is a re-push of the
 * derived face and not a blanket ARMED.
 */
internal class HomeMicFacePushTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, log)
    private val owner = TakeOwner()
    private val background = FakeBackground()
    private val main = FakeMainPost()
    private val busy = FakeMicBusyPort()
    private val home = FaceHomeControl()

    /**
     * The wiring over a REAL controller and switch, with [home] attached and the fake mic installed.
     * The controller's observer is registered before the wiring is built, as the app registers it in
     * onCreate; the install pushes the derived face once, as a fresh launch does at creation.
     */
    private fun built(): Built {
        val launcher = FakeLauncher()
        val controller = DictationServiceController(FakePermission(), launcher)
        val offStore = MemoryOffStore()
        val switch = ArmedSwitch(controller, offStore)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        val mic = RecordingMic()
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
        return Built(wiring, controller, switch, mic)
    }

    private class Built(
        val wiring: HomeTakeWiring,
        val controller: DictationServiceController,
        val switch: ArmedSwitch,
        val mic: RecordingMic,
    )

    /** The Home is attached, then the service is armed: the mic must be pushed ARMED. */
    @Test
    fun `arm with Home open`() {
        val built = built()
        built.switch.switchOn()
        assertEquals("app: face push: the arm must reach the service", true, built.controller.isArmed)
        assertEquals(
            "app: face push: arming with the Home open must re-push the mic ARMED, not leave the creation face",
            listOf(TileState.IDLE, TileState.ARMED),
            built.mic.faces,
        )
    }

    /** The Home is attached and armed, then the service is disarmed: the mic must be pushed IDLE. */
    @Test
    fun `disarm with Home open`() {
        val built = built()
        built.switch.switchOn()
        val beforeDisarm: Int = built.mic.faces.size
        built.switch.switchOff()
        assertEquals("app: face push: the disarm must reach the service", false, built.controller.isArmed)
        assertTrue(
            "app: face push: the disarm must re-push the mic, not leave the face it had",
            built.mic.faces.size > beforeDisarm,
        )
        assertEquals(
            "app: face push: disarming with the Home open must leave the mic IDLE",
            TileState.IDLE,
            built.mic.faces.last(),
        )
    }

    /**
     * The fresh-launch order: the mic is created and its face computed FIRST, while unarmed, and the
     * service is armed AFTER, exactly as onCreate builds the mic before onStart's armAtStart. The mic
     * must end ARMED.
     */
    @Test
    fun `fresh-launch order`() {
        val built = built()
        assertEquals(
            "app: face push: the fresh-launch creation push is computed while unarmed, so it is IDLE",
            listOf(TileState.IDLE),
            built.mic.faces,
        )
        built.switch.armAtStart()
        assertEquals("app: face push: the fresh-launch arm must reach the service", true, built.controller.isArmed)
        assertEquals(
            "app: face push: the mic must end ARMED after the fresh-launch arm",
            TileState.ARMED,
            built.mic.faces.last(),
        )
    }

    /** A recording take pushes RECORDING: the push is the state's face, not a blanket ARMED. */
    @Test
    fun `a recording take pushes the recording face and not a blanket armed`() {
        val built = built()
        built.switch.switchOn()
        built.wiring.onTap()
        assertEquals(
            "app: face push: a recording take must push RECORDING, not a blanket ARMED",
            TileState.RECORDING,
            built.mic.faces.last(),
        )
    }
}

/** A [HomeMicPort] that records every face it is pushed. */
private class RecordingMic : HomeMicPort {
    val faces: MutableList<TileState> = ArrayList()

    override fun setState(state: TileState) {
        faces.add(state)
    }

    /** The level feed is not watched here; the face is. */
    override fun setLevel(level: Float) {}
}

/** A [HomeControl] that records every line and every recording look it is given. */
private class FaceHomeControl : HomeControl {
    val lines: MutableList<String?> = ArrayList()
    val recordings: MutableList<Boolean> = ArrayList()

    override fun showLine(line: String?) {
        lines.add(line)
    }

    override fun showRecording(recording: Boolean) {
        recordings.add(recording)
    }

    override fun refresh() {}
}
