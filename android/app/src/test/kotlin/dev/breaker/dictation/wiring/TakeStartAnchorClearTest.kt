package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.TakeStartAnchor
import dev.breaker.dictation.commit.TakeStartWindow
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TILE owns no take-start clear at all. Every end of a take is cleared by the take's own
 * terminal step, on the worker that ends it: DictationRunner.send()'s finally, its finish and
 * cancel paths, and LazyTakePort for Home. Those are pinned with a REAL runner in
 * [TakeStartFunnelTest] and [TakeStartRaceTest].
 *
 * What this file pins is the other half: the tile must NOT touch the global anchor, because a
 * settle posted to main runs LATE. If it cleared there, take N's settle could wipe take N+1's
 * window after N+1 began, and N+1's commit would read null = INACTIVE and skip the window check.
 * `a late settle cannot clear the next take's anchor` is that race.
 *
 * This rig drives a FAKE take, which neither clears nor rolls back, so an anchor still present
 * after a tile end is the EXPECTED result here, not a leak: the fake has no funnel. The tests
 * below therefore assert the tile left the anchor alone, and name the real-runner test that
 * pins the clear itself.
 *
 * Home ends: finish and resetAfterTaken (both run in finishAndSettle) and cancel. Home's
 * finishTrimmed and send delegates are NOT reachable from HomeTakeAdapter — see NOT DONE in
 * PLAN_T4B_S4T.md for the grep evidence.
 *
 * UNCOMPILED: no JVM/Gradle/kotlinc in this environment. No test was run; no RED/GREEN is claimed.
 */
class TakeStartAnchorClearTest {

    private val stale: TakeStartWindow = TakeStartWindow(windowId = 77, packageName = "com.stale.field")

    /**
     * The Home tests need a recorder of their own: the shared [Rig] defaults to none, so a Home
     * take would never write an anchor and the assertions below would pass on nothing. This is
     * built here, not added to WiringFakes. Each capture writes a FRESH window, so a later take
     * can be told apart from an earlier one.
     */
    private val homeRecorder: TakeStartRecorder = object : TakeStartRecorder {
        private var n = 0
        override fun capture() {
            n += 1
            TakeStartAnchor.capture(TakeStartWindow(windowId = 900 + n, packageName = "com.home.take"))
        }

        override fun clear() {
            TakeStartAnchor.clear()
        }
    }

    /** A Home wiring over an injected port, so a Home tap reaches that port's own begin(). */
    private fun homeWiring(take: () -> TakePort): HomeTakeWiring {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, MemoryOffStore())
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        return HomeTakeWiring(
            take = take,
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
    fun `a cancel between finish and send leaves the anchor for the commit`() {
        val rig = TileRig()
        rig.startRecording()
        TakeStartAnchor.capture(stale)
        // The finish and its one send are queued on the worker and have NOT run: transcription
        // takes seconds here, and the commit has not read the anchor yet.
        rig.coordinator.onSend()
        rig.coordinator.onCancel()
        assertNotNull("app: a cancel while the send is pending must NOT clear the anchor", TakeStartAnchor.current())
        rig.background.runAll()
        assertNotNull("app: the send must still see the window check", TakeStartAnchor.current())
        rig.settle()
        // The tile's settle no longer clears: the clear is the runner's own send(), pinned with a
        // REAL runner in TakeStartFunnelTest.`the anchor survives finish and goes only after send`.
        assertNotNull("app: the tile's settle must leave the clear to the runner's own send", TakeStartAnchor.current())
    }

    @Test
    fun `a switch-off between finish and send leaves the anchor for the commit`() {
        val rig = TileRig()
        rig.startRecording()
        TakeStartAnchor.capture(stale)
        rig.coordinator.onSend()
        rig.coordinator.onArmedChanged(false)
        assertNotNull("app: a switch-off while the send is pending must NOT clear the anchor", TakeStartAnchor.current())
        rig.background.runAll()
        assertEquals("app: the send must have read the window check", stale, rig.take.anchorAtSend)
        rig.settle()
        // disarm() bumped generation, so the settle posted by onSend is skipped by `if (epoch ==
        // generation)` and the TILE never clears. The clear that ends a real take is the runner's own
        // send(), pinned with a REAL runner in TakeStartRaceTest. FakeTake.send() does not clear, so
        // asserting null here would assert the fake, not the app.
        assertTrue("app: the send must have run", rig.take.sendCalls == 1)
        assertNotNull("app: the skipped settle leaves the clear to the runner's own send", TakeStartAnchor.current())
    }

    @Test
    fun `a taken begin leaves the clear to the runner's rollback`() {
        val rig = TileRig()
        rig.take.beginResult = BeginResult.Taken
        TakeStartAnchor.capture(stale)
        rig.coordinator.onBegin()
        rig.settle()
        // The tile no longer clears here: it is redundant with the runner's begin rollback and unsafe
        // in principle, because HomeTakeAdapter begins without beginPending, so a Home take begun
        // after a refused tile begin could be cleared on main. FakeTake does not roll back, so the
        // clear itself is pinned with a REAL runner in TakeStartFunnelTest.`a taken microphone clears
        // the anchor`.
        assertNotNull("app: the tile's Taken path must not clear another take's window", TakeStartAnchor.current())
    }

    @Test
    fun `a sent take leaves the clear to the runner's own send`() {
        val rig = TileRig()
        rig.startRecording()
        TakeStartAnchor.capture(stale)
        rig.coordinator.onSend()
        rig.settle()
        // FakeTake has no funnel. The real clear is DictationRunner.send()'s finally, pinned with a
        // REAL runner in TakeStartFunnelTest.`the anchor survives finish and goes only after send`.
        assertNotNull("app: the tile's settle must not clear a sent take's anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a failed take leaves the clear to the runner's own finish`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.Failed("the engine failed")
        rig.startRecording()
        TakeStartAnchor.capture(stale)
        rig.coordinator.onSend()
        rig.settle()
        // The real clear is DictationRunner.finish()'s Failure branch, pinned with a REAL runner in
        // TakeStartFunnelTest.`a finish that throws clears the anchor`.
        assertNotNull("app: the tile's settle must not clear a failed take's anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a back take leaves the clear to the runner's own finish`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.Failed(EMPTY_TAKE_SENTENCE)
        rig.startRecording()
        TakeStartAnchor.capture(stale)
        rig.coordinator.onSend()
        rig.settle()
        // Back is finish()'s DictationResult.Failure branch, which clears on the worker that ends
        // the take. Pinned with a REAL runner in TakeStartFunnelTest.`a nothing-heard finish clears
        // the anchor`.
        assertNotNull("app: the tile's settle must not clear a back take's anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a nothing-heard take leaves the clear to the runner's own cancel`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription(""))
        rig.startRecording()
        TakeStartAnchor.capture(stale)
        rig.coordinator.onSend()
        rig.settle()
        // TakeFinisher.sendText cancels a blank take, and cancel() clears on the worker. Pinned with
        // a REAL runner in TakeStartFunnelTest.`a nothing-heard finish clears the anchor`.
        assertNotNull("app: the tile's settle must not clear a nothing-heard take's anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a begin refused as busy leaves the live take's anchor in place`() {
        // A BUSY refusal means ANOTHER take is live and owns this window. fail() must not drop it:
        // that take's own finish/send still has to be checked against it.
        val rig = TileRig()
        rig.take.beginResult = BeginResult.Refused("busy")
        TakeStartAnchor.capture(stale)
        rig.coordinator.onBegin()
        rig.settle()
        assertNotNull("app: a BUSY refusal must not drop the live take's window", TakeStartAnchor.current())
    }

    @Test
    fun `a failed begin leaves the live take's anchor in place`() {
        // Same reasoning: the runner rolls its own take back and clears its own anchor; the tile's
        // fail() has no business dropping a window that may belong to a different, live take.
        val rig = TileRig()
        rig.take.beginResult = BeginResult.Failed("boom")
        TakeStartAnchor.capture(stale)
        rig.coordinator.onBegin()
        rig.settle()
        assertNotNull("app: a failed begin must not drop the live take's window", TakeStartAnchor.current())
    }

    @Test
    fun `a refused send queue leaves the live take's anchor in place`() {
        // TileCoordinator:143 — the send submit was refused, so the take is STILL RECORDING and its
        // later finish/send must still be checked. This is the site the retracted ruling missed.
        val rig = TileRig()
        rig.background.submitError = RuntimeException("the queue refused")
        TakeStartAnchor.capture(stale)
        rig.coordinator.onSend()
        rig.background.submitError = null
        rig.settle()
        assertNotNull("app: a refused send submit must not drop the live take's window", TakeStartAnchor.current())
    }

    @Test
    fun `a home finish and its reset clear the take-start anchor`() {
        val rig = Rig(takeStart = homeRecorder)
        val wiring = homeWiring({ TakePortAdapter(rig.runner) })
        wiring.onTap()
        TakeStartAnchor.capture(stale)
        wiring.onTap()
        assertNull("app: a Home finish must clear the anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a home cancel clears the take-start anchor`() {
        val rig = Rig(takeStart = homeRecorder)
        val wiring = homeWiring({ TakePortAdapter(rig.runner) })
        wiring.onTap()
        TakeStartAnchor.capture(stale)
        // HomeTakeAdapter.onCancel and LazyTakePort are private; TakePortAdapter is the real
        // reachable cancel the Home X drives, and it delegates to DictationRunner.cancel().
        TakePortAdapter(rig.runner).cancel()
        assertNull("app: a Home cancel must clear the anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a home cancel clears the anchor through a port that does not clear`() {
        val wiring = homeWiring({ FakeHomeTake(CountingCommitter(), ArrayList<String>()) })
        wiring.onTap()
        TakeStartAnchor.capture(stale)
        val field = HomeTakeWiring::class.java.getDeclaredField("adapter")
        val adapter = field.apply { setAccessible(true) }.get(wiring) as HomeTakeAdapter
        adapter.onCancel()
        assertNull("app: the Home cancel must clear the anchor itself", TakeStartAnchor.current())
    }

    @Test
    fun `a home finish's reset clears the anchor through a port that does not clear`() {
        val wiring = homeWiring({ FakeHomeTake(CountingCommitter(), ArrayList<String>()) })
        wiring.onTap()
        TakeStartAnchor.capture(stale)
        wiring.onTap()
        assertNull("app: the Home reset must clear the anchor itself", TakeStartAnchor.current())
    }

    @Test
    fun `a later take captures its own window, not the earlier take's`() {
        val tile = TileRig()
        tile.startRecording()
        TakeStartAnchor.capture(stale)
        assertNotNull("app: the take must have captured a window", TakeStartAnchor.current())
        tile.coordinator.onSend()
        tile.settle()
        // The tile leaves the anchor alone; the runner's own send() clears it (real-runner pin in
        // TakeStartFunnelTest). What matters for the commit is that a LATER take writes its OWN
        // window, so it can never be checked against the earlier take's.
        // A real Home take with its own recorder: the next begin writes its OWN window, so the
        // commit can never be checked against the earlier take's. This half was vacuous before:
        // Rig() has no recorder, so nothing ever wrote an anchor for it to compare.
        val rig = Rig(takeStart = homeRecorder)
        val wiring = homeWiring({ TakePortAdapter(rig.runner) })
        wiring.onTap()
        val now = TakeStartAnchor.current()
        assertNotNull("app: the new take must capture its own window", now)
        assertTrue("app: a later take must not see the earlier take's window", now != stale)
    }

    @Test
    fun `a late settle cannot clear the next take's anchor`() {
        // ITEM 1 (fail-open): settle is posted to main AFTER the finish job, so it runs late. Take
        // N+1 can begin on the worker and capture its own window before N's settle runs. If settle
        // cleared the global anchor, N+1's commit would read null = INACTIVE and SKIP the window
        // check — an insert with no check, exactly what T4 exists to prevent.
        val rig = TileRig()
        rig.startRecording()
        TakeStartAnchor.capture(stale)
        rig.coordinator.onSend()
        rig.background.runAll()  // the finish job ran; the settle is queued on main, NOT yet run
        val next = TakeStartWindow(windowId = 901, packageName = "com.next.take")
        TakeStartAnchor.capture(next)  // take N+1 began on the worker and captured its own window
        rig.main.drain()  // take N's late settle runs now
        assertEquals("app: a late settle must not wipe the next take's window", next, TakeStartAnchor.current())
    }
}
