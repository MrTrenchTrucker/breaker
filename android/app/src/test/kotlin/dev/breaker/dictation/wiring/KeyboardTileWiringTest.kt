package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.KeyboardProbeReadings
import dev.breaker.dictation.commit.ProbeReading
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stands in for the main thread of one wiring. It counts the blocks the wiring posts and hands the first one
 * to the test thread through a completable value that any thread may fill; the test thread runs the block
 * itself, so no block runs on the collector's thread.
 */
private class CountingMain : MainPost {
    private val count = AtomicInteger()

    /** The first block posted; the value is completed by the first post only. */
    val first = CompletableDeferred<() -> Unit>()

    /** How many blocks have been posted so far. */
    val posts: Int
        get() = count.get()

    override fun post(block: () -> Unit) {
        if (count.incrementAndGet() == 1) first.complete(block)
    }
}

/** How many fresh starts the stale-start test runs; each start checks its own first delivery. */
private const val STALE_CYCLES: Int = 20

/**
 * Coordinator-level tests for the keyboard show/hide seam and its pair gate, reusing the existing
 * [TileRig] fake (recording tile + main queue + take port) so no Android type is named. Each test pins one
 * behaviour of the auto-pop seam: an auto-pop shows the tile through the guard without ever beginning or
 * cancelling a take; a tile closed before a session comes back when the session shows and its close clears
 * only on success; a tile closed during a session stays closed for that session but returns afresh after the
 * flag resets at session end; a tile showing before the keyboard goes is restored as a NoOp, never hidden.
 */
class KeyboardTileWiringTest {

    private fun rig(arm: Boolean = true): TileRig = TileRig(arm = arm)

    /** Clears the shared holder before each test, so no test depends on what an earlier test left in it. */
    @Before
    fun clearHolder() {
        KeyboardProbeReadings.setEnabled(false)
    }

    /** Leaves the shared holder off after each test, matching its own lifecycle. */
    @After
    fun leaveOff() {
        KeyboardProbeReadings.setEnabled(false)
    }

    @Test
    fun `auto pop through the seam shows and leaves the take untouched`() {
        val r = rig()
        val coordinator = r.coordinator
        coordinator.onKeyboardReading(true, true, 5000L)
        r.settle()
        assertTrue("app: an auto-pop show should have called the tile show path", r.tile.shown)
        assertTrue(
            "app: a keyboard-show must not begin or cancel a take",
            r.take.beginCalls == 0 && r.take.cancelCalls == 0,
        )
    }

    @Test
    fun `closed before the session comes back when it shows`() {
        val r = rig()
        val coordinator = r.coordinator
        // Closed before any session: no session active, so the close is remembered but not as session-while.
        coordinator.onCloseRequested()
        r.settle()
        assertFalse("app: the tile stays closed without a session", r.tile.shown)

        // A new session starts; the auto-pop show comes back and clears the user's close on success.
        coordinator.onKeyboardReading(true, true, 6000L)
        r.settle()
        assertTrue("app: the tile should be back once the session shows", r.tile.shown)
    }

    @Test
    fun `closed during the session returns only after the flag resets at the next session start`() {
        val r = rig()
        val coordinator = r.coordinator
        // First session: show, so the tile is up and the session is active.
        coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertTrue("app: the tile should be shown by the first session", r.tile.shown)

        // Close during the session keeps it closed; a hide on ime gone does not un-hide it.
        coordinator.onCloseRequested()
        r.settle()
        coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertFalse("app: the tile stays closed for the rest of this session", r.tile.shown)

        // The next session starts afresh; the flag reset lets it come back.
        coordinator.onKeyboardReading(true, true, 3000L)
        r.settle()
        assertTrue("app: a new session brings the tile back after the flag reset", r.tile.shown)
    }

    @Test
    fun `a tile showing before the keyboard stays shown and restores as no-op when it goes`() {
        val r = rig()
        val coordinator = r.coordinator
        // The rig is armed at construction, so arm() already called show(); the tile shows before any reading.
        coordinator.onKeyboardReading(true, false, 1000L)
        r.settle()
        assertTrue("app: the tile should be shown", r.tile.shown)

        // ime gone here is a NoOp restore (this session never raised a tile), so it must not hide.
        coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertTrue("app: the tile should still be shown after a NoOp restore", r.tile.shown)
        assertEquals("app: the tile's hide path is never called for this session", 0, r.tile.calls.count { it == "hide" })
    }

    @Test
    fun `a keyboard show brings up a fresh not-yet-shown tile`() {
        val r = rig(arm = false)
        val coordinator = r.coordinator
        // A refused show arms the switch and leaves the tile down; the log is cleared so only the auto-pop show is counted.
        r.tile.showResult = TileShow.NO_PERMISSION
        coordinator.onArmedChanged(true)
        r.tile.showResult = TileShow.SHOWN
        r.tile.calls.clear()
        coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertTrue("app: the keyboard auto-pop shows a fresh tile", r.tile.shown)
        assertEquals("app: exactly one show call for the auto-pop", 1, r.tile.calls.count { it == "show" })
    }

    @Test
    fun `a keyboard show with the switch off leaves the tile hidden`() {
        val r = rig(arm = false)
        val coordinator = r.coordinator
        coordinator.keyboardAutoPop = false
        coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertFalse("app: with auto-pop off the keyboard does not show the tile", r.tile.shown)
        assertEquals("app: zero show calls when the switch is off", 0, r.tile.calls.count { it == "show" })
    }

    /**
     * A reading that was current before a start must not be posted when the collector attaches. The
     * collector attaches on another thread, and it may attach after a later publish, so the start is
     * repeated [STALE_CYCLES] times and the first delivery is checked in every cycle. Each start is
     * followed by one round trip through the collector's dispatcher, which raises the chance that the
     * collector has attached before the live reading is published; it is not a guarantee. The first
     * delivery is waited for with a bound, so a collector that never delivers fails by name.
     */
    @Test
    fun `a stale first reading before start must not post an entry`() {
        repeat(STALE_CYCLES) {
            KeyboardProbeReadings.setEnabled(false)
            KeyboardProbeReadings.setEnabled(true)
            // The stale reading is the current value when the wiring starts, so it is the baseline.
            KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = true, lastEvent = "seed", time = 4000L)
            val entries = mutableListOf<Triple<Boolean, Boolean, Long>>()
            val posted = CountingMain()
            val wiring = KeyboardTileWiring({ ime, focus, time -> entries.add(Triple(ime, focus, time)) }, posted)
            try {
                wiring.start()
                runBlocking { withContext(Dispatchers.IO) { } }
                KeyboardProbeReadings.publishLatest(fieldFocus = false, imeWindow = true, lastEvent = "live", time = 5000L)
                val first = awaitBounded("the live reading after a stale start", posted.first)
                first()
                assertFalse(
                    "app: the stale start replay must not post any entry",
                    entries.contains(Triple(true, true, 4000L)),
                )
                assertEquals(
                    "app: the first delivery after a stale start must be the live reading",
                    listOf(Triple(true, false, 5000L)),
                    entries,
                )
            } finally {
                wiring.stop()
            }
        }
    }

    @Test
    fun `a second start after stop delivers a later reading exactly once`() {
        val entries = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val posted = CountingMain()
        val wiring = KeyboardTileWiring({ ime, focus, time -> entries.add(Triple(ime, focus, time)) }, posted)
        try {
            KeyboardProbeReadings.setEnabled(true)
            // The reset before this test leaves the holder empty, so both starts see a null baseline.
            // First start; then the wiring stops, so only readings after the restart must be collected.
            wiring.start()
            wiring.stop()

            // Stop (cancel + scope cancel), then start again on a fresh scope; the later reading must reach readEntry once.
            wiring.start()

            KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = false, lastEvent = "after", time = 2000L)
            val first = awaitBounded("the reading published after the restart", posted.first)
            first()
            assertEquals("app: a second start after stop must deliver exactly one entry for the new reading", 1, entries.size)
            assertEquals("app: the second start must deliver the new reading as its entry", listOf(Triple(false, true, 2000L)), entries)
            assertEquals("app: a second start after stop must post exactly one block", 1, posted.posts)
        } finally {
            wiring.stop()
        }
    }

    @Test
    fun `the live readings flow re-emits each distinct change and carries current at attach`() {
        KeyboardProbeReadings.setEnabled(true)

        // The derived view replays the current value at attach (null here), then hands out each
        // distinct change once. Drive it with a small manual collector: this mirrors what the wiring's
        // `readings().collect {}` observes without ever blocking JUnit, so we cancel it after capture.
        val seen = mutableListOf<ProbeReading?>()
        runBlocking {
            val collector = launch(Dispatchers.Unconfined) {
                KeyboardProbeReadings.readings().collect { value -> seen.add(value) }
            }
            // The replay is already in seen: Dispatchers.Unconfined ran the collector up to its first suspension.
            collector.cancel()
            collector.join()
        }

        assertEquals("app: a freshly attached live flow must carry null", 1, seen.size)
        assertNull("app: the newly-attached live view must be null", seen.last())

        KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = false, lastEvent = "typed", time = 7L)
        runBlocking {
            val collector = launch(Dispatchers.Unconfined) {
                KeyboardProbeReadings.readings().collect { value -> seen.add(value) }
            }
            // Same as above: this collector's replay, the changed value, is already in seen.
            collector.cancel()
            collector.join()
        }

        assertEquals(
            "app: each distinct published change must be delivered once on the live flow",
            listOf(null, ProbeReading(true, false, "typed", 7L)),
            seen,
        )
    }
}
