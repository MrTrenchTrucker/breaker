package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.ui.HomeControl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE LISTENER IS TAKEN OFF THE SWITCH WHEN THE SCREEN GOES. The switch is app-wide and a
 * wiring is built per screen, so a listener left on it re-pushes a mic after the screen is gone. The
 * activity removes it in `onDestroy` through `unwatchSwitch`, which calls
 * `ArmedSwitch.removeSwitchListener`.
 *
 * WHAT THIS FILE PINS (the flip-after-teardown test): a wiring is built, the teardown step
 * the activity performs is driven, and then an arm/disarm flip must push NOTHING. The mic is watched
 * through the `installScreenMic` seam (the real mic is an Android view).
 *
 * `SettingsLauncherActivity.onDestroy` is an Android activity lifecycle callback, so it cannot be
 * driven on the JVM (no Robolectric on this module's test classpath, no `androidTest` source set). The
 * seam that performs the same step is `HomeTakeWiring.unwatchSwitch`, which is what `onDestroy`
 * calls. This test therefore pins the *behaviour* the teardown must produce (a flip after it pushes
 * nothing) and, in addition, that `unwatchSwitch` is the removal path. A source rule that `onDestroy`
 * calls `unwatchSwitch` would be a comment-scan, which is extra coverage and not the behaviour under test.
 *
 * WHAT IS NOT PINNED HERE: that `SettingsLauncherActivity.onDestroy` itself calls `unwatchSwitch`.
 * That line lives behind an Android lifecycle callback this test cannot drive. The variant "drop the
 * remove call" is caught by [a listener taken off is not told] and [teardown of one screen leaves the
 * other screen's re-push working] (the switch's removal behaviour), not by this file's activity line.
 * The behaviour under test is what the teardown produces.
 *
 * ORDER NOTE: `rePushState` calls `home?.showRecording(...)` BEFORE `screenMic?.setState(...)`, so the
 * Home control's log is always one step ahead of the mic's face log. This test drives both, so a flip
 * that re-pushes one and not the other is caught.
 */
internal class HomeTakeWiringTeardownPinTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, log)
    private val owner = TakeOwner()
    private val background = FakeBackground()
    private val main = FakeMainPost()
    private val busy = FakeMicBusyPort()

    /** The wiring over a REAL controller and switch, with the app's observer registered before it. */
    private fun built(): TeardownPinBuilt {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, MemoryOffStore())
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        val mic = TeardownPinRecordingMic()
        val home = TeardownPinFaceHomeControl()
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
        return TeardownPinBuilt(wiring, controller, switch, mic, home)
    }

    private class TeardownPinBuilt(
        val wiring: HomeTakeWiring,
        val controller: DictationServiceController,
        val switch: ArmedSwitch,
        val mic: TeardownPinRecordingMic,
        val home: TeardownPinFaceHomeControl,
    )

    /**
     * The behaviour under test: after the teardown step, a flip pushes nothing. The flip is driven on the
     * real chain (`switch.switchOn()` / `switch.switchOff()`), and BOTH the mic's face log and the Home
     * control's recording log are watched through the seam - the two halves of one re-push.
     */
    @Test
    fun `a flip after teardown pushes nothing`() {
        val built = built()
        val facesBefore: Int = built.mic.faces.size
        val recordingsBefore: Int = built.home.recordings.size
        // The step SettingsLauncherActivity.onDestroy performs: take the wiring's listener off the switch.
        built.wiring.unwatchSwitch(built.wiring.switchListener)
        built.switch.switchOn()
        assertEquals(
            "app: teardown: after teardown an arm must push no face",
            facesBefore,
            built.mic.faces.size,
        )
        assertEquals(
            "app: teardown: after teardown an arm must push no recording look",
            recordingsBefore,
            built.home.recordings.size,
        )
        built.switch.switchOff()
        assertEquals(
            "app: teardown: after teardown a disarm must push no face",
            facesBefore,
            built.mic.faces.size,
        )
        assertEquals(
            "app: teardown: after teardown a disarm must push no recording look",
            recordingsBefore,
            built.home.recordings.size,
        )
    }

    /** The same flip WITH the listener still on pushes both halves, so the behaviour above is a real difference. */
    @Test
    fun `a flip with the listener still on pushes the face and the recording look`() {
        val built = built()
        built.switch.switchOn()
        assertEquals(
            "app: teardown: with the listener on, an arm must re-push the face",
            listOf(TileState.IDLE, TileState.ARMED),
            built.mic.faces,
        )
        assertEquals(
            "app: teardown: with the listener on, an arm must re-push the recording look",
            listOf(false, false),
            built.home.recordings,
        )
    }

    /** The listener the activity removes is the wiring's own listener, so the removal is exact. */
    @Test
    fun `unwatchSwitch removes exactly the wiring's own listener`() {
        val built = built()
        built.switch.switchOn()
        assertEquals(
            "app: teardown: the listener is on, so the arm pushes once",
            1,
            built.mic.faces.count { it == TileState.ARMED },
        )
        built.wiring.unwatchSwitch(built.wiring.switchListener)
        val faces: Int = built.mic.faces.size
        val recordings: Int = built.home.recordings.size
        built.switch.switchOn()
        built.switch.switchOff()
        assertEquals("app: teardown: the listener is off, so no more faces push", faces, built.mic.faces.size)
        assertEquals("app: teardown: the listener is off, so no more recording looks push", recordings, built.home.recordings.size)
    }

    /**
     * `unwatchSwitch` is a removal on the switch (it calls `removeSwitchListener`), and a listener that
     * was never added is ignored rather than throwing - so the activity's `?.let` is safe on a first-run
     * path where the wiring was built but the listener was never held.
     */
    @Test
    fun `unwatchSwitch is a no-op when the listener was never added`() {
        val built = built()
        val faces: Int = built.mic.faces.size
        val neverAdded: (Boolean) -> Unit = { }
        built.wiring.unwatchSwitch(neverAdded)
        built.switch.switchOn()
        assertEquals(
            "app: teardown: a removal of a listener that was never added must not disturb the wiring's own",
            faces + 1,
            built.mic.faces.size,
        )
    }

    /**
     * Two wirings on one switch, as two screens: tearing one down must not take the other's listener
     * off. The removal is by identity, so the other screen keeps its re-push.
     */
    @Test
    fun `teardown of one screen leaves the other screen's re-push working`() {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, MemoryOffStore())
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        val first = HomeTakeWiring(
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
        val second = HomeTakeWiring(
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
        val firstMic = TeardownPinRecordingMic()
        val secondMic = TeardownPinRecordingMic()
        val firstHome = TeardownPinFaceHomeControl()
        val secondHome = TeardownPinFaceHomeControl()
        first.attach(firstHome)
        second.attach(secondHome)
        first.installScreenMic(firstMic)
        second.installScreenMic(secondMic)
        first.unwatchSwitch(first.switchListener)
        switch.switchOn()
        assertEquals("app: teardown: the torn-down screen's mic must not move", 1, firstMic.faces.size)
        assertEquals("app: teardown: the torn-down screen's home control must not move", 1, firstHome.recordings.size)
        assertEquals(
            "app: teardown: the other screen's mic must still re-push",
            2,
            secondMic.faces.size,
        )
        assertEquals(
            "app: teardown: the other screen's home control must still re-push",
            2,
            secondHome.recordings.size,
        )
    }
}

/** A [HomeMicPort] that records every face it is pushed. */
private class TeardownPinRecordingMic : HomeMicPort {
    val faces: MutableList<TileState> = ArrayList()

    override fun setState(state: TileState) {
        faces.add(state)
    }

    /** The level feed is not watched here; the face is. */
    override fun setLevel(level: Float) {}
}

/** A [HomeControl] that records every line and every recording look it is given. */
private class TeardownPinFaceHomeControl : HomeControl {
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
