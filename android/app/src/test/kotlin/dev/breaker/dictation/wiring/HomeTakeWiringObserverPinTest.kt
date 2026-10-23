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
 * PIN 1 - the wiring never takes the controller's one observer slot, so the app's existing observer
 * still runs. The app's observer in `BreakerApp.onCreate` does two things that an earlier, rejected fix
 * silently killed: `armedSwitch.onStateChanged(armed, reason)` (the user's off choice is persisted)
 * and `tileHost.onArmedChanged(armed)` (the floating tile's armed face). The wiring must add a
 * listener to `ArmedSwitch` instead of calling `DictationServiceController.setStateObserver`.
 *
 * WHAT THIS FILE PINS (behaviour, not a source scan): a wiring is built over the app's own observer,
 * exactly as `BreakerApp.onCreate` builds it, and a `HostFace` is the second thing that observer does.
 * Arming must reach BOTH - the off-store cleared (`isOff() == false`) and `HostFace.armed == true`.
 *
 * The `tileHost.onArmedChanged` half: `TileHost` is `internal class TileHost(context: Context, root:
 * BreakerCompositionRoot, isOn: () -> Boolean)`, its constructor touches `MainLooperPost()` (an
 * `android.os.Handler`, which is `final` in the unit-test android.jar), `ModelNotifications(context)`,
 * `appGesture(context)` (a `SensorManager` lookup) and `OverlayTilePort(context, ...)`. There is no
 * `MainPost` seam and no fake-able narrow port, no Robolectric on this module's test classpath, and no
 * `androidTest` source set. **So `TileHost` cannot be built on the JVM and cannot be faked here** - a
 * fake `onArmedChanged` would be this file's own stub, not the code under test. The requirement is
 * met instead by the chain this file builds, which is the app's real shape:
 *
 * `BreakerApp.onCreate` registers `controller.setStateObserver { armed, reason -> armedSwitch
 * .onStateChanged(...); tileHost.onArmedChanged(armed) }`. The wiring's fix is that it does NOT call
 * `setStateObserver` again - it adds a listener to the switch, which `onStateChanged` then notifies at
 * its end. This test builds that exact chain over a REAL controller and switch with a REAL
 * `DictationServiceController` observer registered before the wiring, and asserts the observer's two
 * effects both still land. A fix that replaces the observer (that rejected fix) makes
 * `controller.setStateObserver(...)` run from the wiring, which replaces the slot, so
 * `armedSwitch.onStateChanged` and `tileHost.onArmedChanged` are never called again: the off-store is
 * never cleared and `HostFace.armed` stays null. The assert below then fails.
 *
 * `HostFace` is this file's own fake of the thing `tileHost.onArmedChanged` does; it exists only
 * because the real `TileHost` cannot be built here (see the PLAN CHANGES note in the plan file). It is
 * not the pin - the pin is the wiring not replacing the observer.
 *
 * ORDER NOTE: `rePushState` calls `home?.showRecording(...)` BEFORE `screenMic?.setState(...)`, so the
 * Home control's log is always one step ahead of the mic's face log. This test drives both, so a mutant
 * that re-pushes one and not the other is caught.
 */
internal class HomeTakeWiringObserverPinTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, log)
    private val owner = TakeOwner()
    private val background = FakeBackground()
    private val main = FakeMainPost()
    private val busy = FakeMicBusyPort()

    /** The switch's off-store as the app's `FileOffStore` is stood in for in the JVM tests. */
    private val offStore = MemoryOffStore()

    /**
     * The wiring over a REAL controller and switch, with the app's own observer registered before it,
     * exactly as `BreakerApp.onCreate` registers it before the activity builds the wiring. [host] is
     * the second thing that observer does; [offStore] is the first.
     */
    private fun built(host: HostFace): ObserverPinBuilt {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, offStore)
        // The app's observer, verbatim in substance from BreakerApp.onCreate:115-118.
        controller.setStateObserver { armed, reason ->
            switch.onStateChanged(armed, reason)
            host.onArmedChanged(armed)
        }
        val mic = ObserverPinRecordingMic()
        val home = ObserverPinFaceHomeControl()
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
        return ObserverPinBuilt(wiring, controller, switch, mic, home, host)
    }

    private class ObserverPinBuilt(
        val wiring: HomeTakeWiring,
        val controller: DictationServiceController,
        val switch: ArmedSwitch,
        val mic: ObserverPinRecordingMic,
        val home: ObserverPinFaceHomeControl,
        val host: HostFace,
    )

    /** Arming clears the user's off choice: `armedSwitch.onStateChanged` still ran. */
    @Test
    fun `arming clears the stored off choice`() {
        offStore.off = true
        val built = built(HostFace())
        built.switch.switchOn()
        assertTrue("app: pin1: the arm must reach the service", built.controller.isArmed)
        assertEquals(
            "app: pin1: the arm must reach armedSwitch.onStateChanged, which clears the off choice",
            false,
            offStore.isOff(),
        )
    }

    /** Arming tells the tile host: `tileHost.onArmedChanged` still ran. */
    @Test
    fun `arming tells the tile host its armed face`() {
        val host = HostFace()
        val built = built(host)
        built.switch.switchOn()
        assertEquals(
            "app: pin1: the arm must reach tileHost.onArmedChanged through the app's observer",
            true,
            host.armed,
        )
    }

    /** Both at once, on the real chain, with the wiring built - the pin's one assert. */
    @Test
    fun `the app's observer still runs both arms after the wiring is built`() {
        offStore.off = true
        val host = HostFace()
        val built = built(host)
        built.switch.switchOn()
        assertTrue("app: pin1: the off-store must be cleared by the observer the app still owns", !offStore.isOff())
        assertEquals("app: pin1: the tile host must be told by the observer the app still owns", true, host.armed)
        assertTrue(
            "app: pin1: the wiring's own listener must also re-push the mic, so both survive together",
            built.mic.faces.contains(TileState.ARMED),
        )
    }

    /**
     * The disarm half: the user's off choice is STORED (`USER_WORD`) and the tile host is told it is
     * off. A fix that replaces the observer stops both, so this row dies with the observer mutant too.
     */
    @Test
    fun `a user disarm stores the off choice and tells the tile host`() {
        val host = HostFace()
        val built = built(host)
        built.switch.switchOn()
        built.switch.switchOff()
        assertTrue("app: pin1: the user's off choice must be stored by the observer", offStore.isOff())
        assertEquals("app: pin1: the tile host must be told it is off", false, host.armed)
    }

    /**
     * A service that ends by itself is not the user's off word: nothing is stored, and the tile host is
     * still told it is off. This is the reason the wiring must not own the observer - the off-store's
     * rule (only `USER_WORD` writes it) lives in `ArmedSwitch.onStateChanged`, which only runs while the
     * app's observer is registered.
     */
    @Test
    fun `the service ending by itself is not the user's off word`() {
        val host = HostFace()
        val built = built(host)
        built.switch.switchOn()
        built.controller.serviceEnded()
        assertEquals("app: pin1: a service that ends is not the user's off word", false, offStore.isOff())
        assertEquals("app: pin1: the tile host must still be told it is off", false, host.armed)
    }

    /** The wiring's own re-push still lands, so pin 1 and the face push are not traded against each other. */
    @Test
    fun `the re-push reaches the mic and is the derived face not a blanket armed`() {
        val built = built(HostFace())
        assertEquals("app: pin1: the fresh-launch creation push is computed while unarmed", listOf(TileState.IDLE), built.mic.faces)
        built.switch.switchOn()
        assertEquals(
            "app: pin1: the arm must re-push ARMED through the wiring's own listener",
            TileState.ARMED,
            built.mic.faces.last(),
        )
        built.wiring.onTap()
        assertEquals(
            "app: pin1: a recording take must push RECORDING, not a blanket ARMED",
            TileState.RECORDING,
            built.mic.faces.last(),
        )
    }

    /**
     * The re-push paints BOTH the Home control and the mic, in that order, so the Home's recording look
     * and the mic's face cannot drift apart. This is the shape `rePushState` has, and it is what a
     * mutant that re-pushes only one of the two breaks.
     */
    @Test
    fun `the re-push paints the home control and the mic in order`() {
        val built = built(HostFace())
        built.switch.switchOn()
        assertEquals(
            "app: pin1: the arm re-pushes the recording look (idle) then the ARMED face",
            listOf(false, false),
            built.home.recordings,
        )
        assertEquals(
            "app: pin1: the arm re-pushes the ARMED face after the recording look",
            listOf(TileState.IDLE, TileState.ARMED),
            built.mic.faces,
        )
    }

    /** The wiring's listener is a listener on the switch and not a second observer slot. */
    @Test
    fun `the wiring adds a switch listener and does not take the observer slot`() {
        val built = built(HostFace())
        // One registration, told once for the arm: the switch's own listener list, not the controller's
        // single observer (which a second setStateObserver call would have replaced).
        built.switch.switchOn()
        assertEquals("app: pin1: the arm re-pushes exactly once", 1, built.mic.faces.count { it == TileState.ARMED })
        // And the app's observer is still the one in charge of the off-store after the wiring is built.
        built.switch.switchOff()
        assertTrue("app: pin1: the app's observer must still own the off-store", offStore.isOff())
    }
}

/** The `tileHost.onArmedChanged` half of the app's observer, stood in because `TileHost` needs a Context. */
private class HostFace {
    var armed: Boolean? = null

    fun onArmedChanged(armed: Boolean) {
        this.armed = armed
    }
}

/** A [HomeMicPort] that records every face it is pushed. */
private class ObserverPinRecordingMic : HomeMicPort {
    val faces: MutableList<TileState> = ArrayList()

    override fun setState(state: TileState) {
        faces.add(state)
    }

    /** The level feed is not watched here; the face is. */
    override fun setLevel(level: Float) {}
}

/** A [HomeControl] that records every line and every recording look it is given. */
private class ObserverPinFaceHomeControl : HomeControl {
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
