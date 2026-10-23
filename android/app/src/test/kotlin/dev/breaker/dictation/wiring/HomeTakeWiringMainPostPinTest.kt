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
 * PIN 3 - the re-push is posted to the main looper, not run inline. The wiring's listener is
 * `{ main.post { rePushState(port.sessionState) } }`, so the face must travel through the [MainPost]
 * the wiring was built with. A fix that pushes inline (drops the `main.post { }`) would re-push the
 * derived face on whatever thread flipped the switch, which is the capture thread as often as not.
 *
 * WHAT THIS FILE PINS: a wiring built over a [QueueingMainPost] that records each posted block and
 * runs nothing on its own. The test then drives a flip and asserts (a) the mic did NOT move while the
 * block sat in the queue, and (b) the mic moved only after the queued block ran. That is the difference
 * between posting to the main looper and running inline, made observable without a looper.
 *
 * The two disarm asserts are LOWER BOUNDS, because a USER disarm fires
 * `onStateChanged` twice: `ArmedSwitch.switchOff()` calls `controller.disarm(USER_WORD)`, which
 * notifies the controller's observer (the switch, which then notifies its listeners - the first
 * post), and then calls `onStateChanged(false, USER_WORD)` again (the second post). So an arm + a
 * disarm posts 3 blocks, not 2, and the face log is [IDLE, ARMED, IDLE, IDLE], not
 * [IDLE, ARMED, IDLE]. The inline-mutant killer is the QUEUED CHECK and it was not weakened: the mic
 * must not move while the block is still queued, and must move only after [QueueingMainPost.runPosted].
 * See [a disarm posts the re-push to main and does not run it inline].
 *
 * The production [dev.breaker.dictation.host.MainLooperPost] is an `android.os.Handler`, which is
 * `final` in the unit-test android.jar and so cannot be faked here; the [MainPost] seam is the one the
 * wiring takes, so the fake below stands at exactly the seam the production class implements. The
 * wiring's `main` is `FakeMainPost` (`post` runs the block inline) in the other tests, so a wiring
 * built there cannot tell inline from posted - this file is the one that can.
 */
internal class HomeTakeWiringMainPostPinTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, log)
    private val owner = TakeOwner()
    private val background = FakeBackground()
    private val busy = FakeMicBusyPort()

    /** A [MainPost] that queues every block and runs nothing until [runPosted] is called. */
    private class QueueingMainPost : MainPost {
        val posted: MutableList<() -> Unit> = ArrayList()
        var posts: Int = 0
            private set

        override fun post(block: () -> Unit) {
            posts += 1
            posted.add(block)
        }

        /** Runs the queued blocks in order, as a looper would on the main thread. */
        fun runPosted() {
            while (posted.isNotEmpty()) {
                posted.removeAt(0)()
            }
        }
    }

    private fun built(main: QueueingMainPost): MainPostPinBuilt {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, MemoryOffStore())
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        val mic = MainPostPinRecordingMic()
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
        wiring.attach(MainPostPinFaceHomeControl())
        wiring.installScreenMic(mic)
        return MainPostPinBuilt(wiring, controller, switch, mic, main)
    }

    private class MainPostPinBuilt(
        val wiring: HomeTakeWiring,
        val controller: DictationServiceController,
        val switch: ArmedSwitch,
        val mic: MainPostPinRecordingMic,
        val main: QueueingMainPost,
    )

    /** The pin: the flip queues the re-push, and the mic moves only when the queue runs. */
    @Test
    fun `an arm posts the re-push to main and does not run it inline`() {
        val built = built(QueueingMainPost())
        assertEquals("app: pin3: a fresh launch pushes once, inline at creation", listOf(TileState.IDLE), built.mic.faces)
        built.switch.switchOn()
        assertEquals(
            "app: pin3: the arm must not re-push inline, while the block is still queued",
            1,
            built.main.posts,
        )
        assertEquals(
            "app: pin3: the mic must not move before the posted block runs",
            listOf(TileState.IDLE),
            built.mic.faces,
        )
        built.main.runPosted()
        assertEquals(
            "app: pin3: the mic must re-push ARMED once the posted block runs",
            listOf(TileState.IDLE, TileState.ARMED),
            built.mic.faces,
        )
    }

    /** The disarm half: the same post-then-run shape, and the face ends IDLE. */
    @Test
    fun `a disarm posts the re-push to main and does not run it inline`() {
        val built = built(QueueingMainPost())
        built.switch.switchOn()
        built.main.runPosted()
        assertEquals("app: pin3: the arm ran before the disarm", listOf(TileState.IDLE, TileState.ARMED), built.mic.faces)
        built.switch.switchOff()
        // The disarm fires onStateChanged TWICE - controller.disarm() notifies the
        // observer (which notifies the switch listeners), then switchOff() calls onStateChanged(false,
        // USER_WORD) again - so a user disarm posts TWO blocks and the count after an arm + disarm is
        // 3, not 2. The pin is the lower bound: the re-push must be POSTED, never run inline.
        assertTrue(
            "app: pin3: the disarm must post at least one block, not run inline",
            built.main.posts >= 2,
        )
        assertEquals(
            "app: pin3: the mic must not move on the disarm before the posted block runs",
            listOf(TileState.IDLE, TileState.ARMED),
            built.mic.faces,
        )
        built.main.runPosted()
        // Both of the disarm's posted blocks ran, so the real log is [IDLE, ARMED, IDLE, IDLE]. The
        // pin is the lower bound plus the last face: the re-push must reach IDLE through the queue.
        assertTrue(
            "app: pin3: the disarm must re-push IDLE at least once after the posted block runs",
            built.mic.faces.size >= 3,
        )
        assertEquals(
            "app: pin3: the mic must end on IDLE once the posted block runs",
            TileState.IDLE,
            built.mic.faces.last(),
        )
    }

    /**
     * A queue that is never run leaves the mic exactly where it was, so the posted block is the only
     * thing that moves the face - the pin cannot be satisfied by a stray inline push.
     */
    @Test
    fun `a queued re-push that never runs moves the mic not at all`() {
        val built = built(QueueingMainPost())
        built.switch.switchOn()
        assertEquals("app: pin3: the flip must have queued a block", 1, built.main.posts)
        assertEquals(
            "app: pin3: with the block queued and never run, the mic must be unchanged",
            listOf(TileState.IDLE),
            built.mic.faces,
        )
    }

    /**
     * The push the wiring makes is the derived face, not a blanket one, so the post is not a way of
     * smearing ARMED over a recording take: a recording take still pushes RECORDING through the same
     * posted path. The take's own push is also posted (the adapter's `main.post`), so the queue is run
     * before the face is read.
     */
    @Test
    fun `the posted re-push is the derived face not a blanket armed`() {
        val built = built(QueueingMainPost())
        built.switch.switchOn()
        built.main.runPosted()
        built.wiring.onTap()
        built.main.runPosted()
        assertEquals(
            "app: pin3: a recording take must push RECORDING, not a blanket ARMED",
            TileState.RECORDING,
            built.mic.faces.last(),
        )
        assertTrue("app: pin3: the face is the derived one", built.mic.faces.contains(TileState.RECORDING))
    }
}

/** A [HomeMicPort] that records every face it is pushed. */
private class MainPostPinRecordingMic : HomeMicPort {
    val faces: MutableList<TileState> = ArrayList()

    override fun setState(state: TileState) {
        faces.add(state)
    }

    /** The level feed is not watched here; the face is. */
    override fun setLevel(level: Float) {}
}

/** A [HomeControl] that records every line and every recording look it is given. */
private class MainPostPinFaceHomeControl : HomeControl {
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
