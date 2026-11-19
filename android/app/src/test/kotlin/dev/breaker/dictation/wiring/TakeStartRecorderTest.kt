package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.TakeStartAnchor
import dev.breaker.dictation.commit.TakeStartWindow
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.service.LaunchResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The take-start capture seam on [DictationRunner.begin], proven with a fake recorder.
 *
 * The app cannot construct the commit module's real capture (its constructor is internal to another
 * Gradle module), so these tests prove the WIRING: capture() runs once per take, before recording
 * starts, and a recorder that throws takes the same rollback as a failed start. The fake writes and
 * clears the anchor the way the real recorder does, so the rollback assertion is a real one.
 *
 * Entry points that reach DictationRunner.begin() (ADDENDUM_EVIDENCE_T4B.md section 4):
 *   tile   TakePortAdapter.kt:11      override fun begin(): BeginResult = runner.begin()
 *   Home   HomeTakeWiring.kt:184     override fun begin(): BeginResult = port.begin()
 *   phrase PhraseSendHandler.kt:113  val answer: BeginResult = inner.begin()
 *   spoken TakeOwner.kt:76           val answer: BeginResult = inner.begin()
 *
 * UNCOMPILED: no JVM/Gradle/kotlinc in this environment. No test was run; no RED/GREEN is claimed.
 */
class TakeStartRecorderTest {

    @Before
    fun clearBefore() {
        TakeStartAnchor.clear()
    }

    @After
    fun clearAfter() {
        TakeStartAnchor.clear()
    }

    /** A recorder that counts captures; [failClosed] mimics the production capture's never-throw contract. */
    private class FakeTakeStartRecorder : TakeStartRecorder {
        var captures: Int = 0
            private set
        var recordedFailures: Int = 0
            private set
        var failClosed: Boolean = false
        var throwing: Boolean = false
        /** Reads the runner and the audio at the moment of a capture, for the ordering assertion. */
        var onCapture: () -> Unit = {}

        override fun capture() {
            captures += 1
            onCapture()
            // Model the real recorder: a capture WRITES the anchor, a fail-closed one writes the
            // no-field value. The write comes BEFORE the throw on purpose: a recorder that captured
            // and then failed is the case where the runner's rollback clear matters. Without the
            // write the anchor assertion below was vacuous - it passed whether or not the runner
            // rolled its take back.
            if (failClosed) {
                recordedFailures += 1
                TakeStartAnchor.capture(null)
            } else {
                TakeStartAnchor.capture(TakeStartWindow(windowId = 500 + captures, packageName = "com.recorder.take"))
            }
            if (throwing) throw IllegalStateException("the take-start recorder failed")
        }

        override fun clear() {
            TakeStartAnchor.clear()
        }
    }

    /** A Home wiring over the real runner, so a Home tap reaches DictationRunner.begin(). */
    private fun homeWiring(runner: DictationRunner): HomeTakeWiring {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, MemoryOffStore())
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        return HomeTakeWiring(
            take = { TakePortAdapter(runner) },
            history = { InMemoryHistoryStore() },
            clipboard = FakeTakenClipboard(),
            switch = switch,
            owner = TakeOwner(),
            background = FakeBackground(),
            main = FakeMainPost(),
            modelReady = { true },
        )
    }

    @Test
    fun `begin captures the take start exactly once and before the audio starts`() {
        val fake = FakeTakeStartRecorder()
        var startsAtCapture: Int = -1
        var stateAtCapture: DictationState? = null
        val rig = Rig(takeStart = fake)
        fake.onCapture = { startsAtCapture = rig.audio.starts; stateAtCapture = rig.runner.sessionState }
        assertEquals("app: begin should answer Recording", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: begin should capture the take start exactly once", 1, fake.captures)
        assertEquals("app: the capture must run before the audio starts", 0, startsAtCapture)
        assertEquals("app: the capture must run after the session won the take", DictationState.RECORDING, stateAtCapture)
        assertEquals("app: begin should start the audio once", 1, rig.audio.starts)
    }

    @Test
    fun `a busy session refuses a second begin and captures nothing more`() {
        val fake = FakeTakeStartRecorder()
        val rig = Rig(takeStart = fake)
        assertEquals("app: the first begin should record", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the first begin should capture once", 1, fake.captures)
        assertEquals("app: a second begin should be refused as busy", BeginResult.Refused(RunnerSentences.BUSY), rig.runner.begin())
        assertEquals("app: a refused begin must not capture again", 1, fake.captures)
        assertEquals("app: a refused begin must not start the audio again", 1, rig.audio.starts)
    }

    @Test
    fun `a cold start the platform refuses captures nothing`() {
        val fake = FakeTakeStartRecorder()
        val rig = Rig(armed = false, launchResult = LaunchResult.Refused, takeStart = fake)
        assertEquals(
            "app: a refused cold start should answer the open-Breaker sentence",
            BeginResult.Refused("Open Breaker once to switch dictation on."),
            rig.runner.begin(),
        )
        assertEquals("app: a refused cold start must not capture", 0, fake.captures)
        assertEquals("app: a refused cold start must not start the audio", 0, rig.audio.starts)
        assertEquals("app: a refused cold start must leave the session idle", DictationState.IDLE, rig.runner.sessionState)
    }

    @Test
    fun `the tile take adapter captures the take start once`() {
        val fake = FakeTakeStartRecorder()
        val rig = Rig(takeStart = fake)
        assertEquals("app: the tile adapter should answer Recording", BeginResult.Recording, TakePortAdapter(rig.runner).begin())
        assertEquals("app: the tile take should capture once", 1, fake.captures)
    }

    @Test
    fun `the home take wiring captures the take start once`() {
        val fake = FakeTakeStartRecorder()
        val rig = Rig(takeStart = fake)
        homeWiring(rig.runner).onTap()
        assertEquals("app: a Home tap should capture the take start once", 1, fake.captures)
        assertEquals("app: a Home tap should leave the runner recording", DictationState.RECORDING, rig.runner.sessionState)
    }

    @Test
    fun `the phrase take port captures the take start once`() {
        val fake = FakeTakeStartRecorder()
        val rig = Rig(takeStart = fake)
        val handler = phraseHandlerFor(FakeBackground(), FakeMainPost())
        val port = PhraseTakePort(TakePortAdapter(rig.runner), handler) {}
        assertEquals("app: the phrase take port should answer Recording", BeginResult.Recording, port.begin())
        assertEquals("app: the phrase take should capture once", 1, fake.captures)
    }

    @Test
    fun `the owned take port captures the take start once`() {
        val fake = FakeTakeStartRecorder()
        val rig = Rig(takeStart = fake)
        val owner = TakeOwner()
        val port = OwnedTakePort(TakePortAdapter(rig.runner), owner, TakeOwnerKind.TILE)
        assertEquals("app: the owned take port should answer Recording", BeginResult.Recording, port.begin())
        assertEquals("app: the spoken-path take should capture once", 1, fake.captures)
        assertEquals("app: the owned take port should hold the take", TakeOwnerKind.TILE, owner.current())
    }

    @Test
    fun `a fail-closed capture returns normally and the take still records`() {
        val fake = FakeTakeStartRecorder()
        fake.failClosed = true
        val rig = Rig(takeStart = fake)
        assertEquals("app: a fail-closed capture must not stop the take", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the fail-closed capture should still be called once", 1, fake.captures)
        assertEquals("app: the fail-closed capture records its failure internally", 1, fake.recordedFailures)
        assertEquals("app: the take should be recording", DictationState.RECORDING, rig.runner.sessionState)
    }

    /**
     * RED-FIRST, STATIC — UNCOMPILED, so this was NOT watched fail.
     *
     * Before the fix, `takeStart?.capture()` sat OUTSIDE the try, after the compareAndSet
     * (old DictationRunner.kt:113 CAS, old :116 capture). A throw there propagated out of begin()
     * with no catch on that path: the session stayed RECORDING, audio.stop() was never called, and
     * every later begin() returned Refused(BUSY) forever. The fix moved the call to the first
     * statement inside the existing try (now :121), so a throw takes the same rollback as a failed
     * start. This is a static red proof, not a watched RED.
     */
    @Test
    fun `a recorder that throws fails the take closed and leaves the runner usable`() {
        TakeStartAnchor.clear()
        val fake = FakeTakeStartRecorder()
        fake.throwing = true
        val rig = Rig(takeStart = fake)
        assertEquals(
            "app: a throwing recorder should fail the take with the existing could-not-record sentence",
            BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD),
            rig.runner.begin(),
        )
        assertEquals("app: a failed take should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: a failed take should stop the audio once", 1, rig.audio.stops)
        assertNull("app: a failed take must not leave a take-start anchor", TakeStartAnchor.current())
        fake.throwing = false
        assertEquals("app: the next take with a good recorder should record", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the next take should capture exactly once more", 2, fake.captures)
        assertEquals("app: the next take should start the audio once", 1, rig.audio.starts)
    }
}
