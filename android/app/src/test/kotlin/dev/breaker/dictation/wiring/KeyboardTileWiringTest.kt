package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.KeyboardProbeReadings
import dev.breaker.dictation.commit.ProbeReading
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun `a stale first reading before start must not post an entry`() {
        val entries = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val main = ManualMain()
        // Seed a stale ime=true, focus=true reading while nothing has started; the replayed
        // collection-start value must NOT act - no readEntry call.
        KeyboardProbeReadings.setEnabled(true)
        KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = true, lastEvent = "seed", time = 4000L)

        val wiring = KeyboardTileWiring({ ime, focus, time -> entries.add(Triple(ime, focus, time)) }, main)
        try {
            wiring.start()
            Thread.sleep(150) // give the collector a chance to attach; it must not deliver the replayed value
            main.drain()
            assertEquals("app: the stale start replay must not post any entry", 0, entries.size)
        } finally {
            wiring.stop()
        }
    }

    @Test
    fun `a second start after stop delivers a later reading exactly once`() {
        val entries = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val main = ManualMain()
        val wiring = KeyboardTileWiring({ ime, focus, time -> entries.add(Triple(ime, focus, time)) }, main)
        try {
            KeyboardProbeReadings.setEnabled(true)
            // First start; then the wiring stops, so only readings after the restart must be collected.
            wiring.start()
            Thread.sleep(150)

            // Stop (cancel + scope cancel), then start again on a fresh scope; the later reading must reach readEntry once.
            wiring.stop()
            wiring.start()

            KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = false, lastEvent = "after", time = 2000L)
            Thread.sleep(150)
            main.drain()
            assertEquals("app: a second start after stop must deliver exactly one entry for the new reading", 1, entries.size)
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
            Thread.sleep(50) // let the collector attach and replay the current (null) value
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
            Thread.sleep(50) // let the collector observe the change
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
