package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.KeyboardProbeReadings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests the KeyboardTileWiring seam end-to-end through its constructor parameters: a readEntry
 * callback and a ManualMain post queue. Each test owns its own collector list and its own manual
 * main, drives the wiring via the public probe write (publishLatest), waits bounded, drains exactly
 * once, and stops its wiring at the end so no real coroutine is left running. All assertions are
 * relative (assert growth and the last entry) because the shared singleton replays its latest value.
 *
 * Waiting for a block to arrive is done by polling [pending] alone: draining would run the readEntry
 * lambda, defeating the "not delivered before drain" assertion. The collector thread runs only when
 * the manual queue is drained (the fake's post() never auto-runs), so reaching pending >= 1 requires
 * repeatedly pumping the queue; each pump yields the collector thread a chance to publish the reading.
 *
 * The shared singleton replays its latest value, so this class clears it before and after each test:
 * a @Before turns the probe off so the collection-start replay is null (which the production collector
 * ignores), and an @After leaves the holder off for the next class or run.
 */
internal class KeyboardTileWiringSeamTest {

    /** Clears the shared holder before each test, so the collection-start replay is a null reading. */
    @Before
    fun clearHolder() {
        KeyboardProbeReadings.setEnabled(false)
    }

    /** Leaves the shared holder off after each test, matching its own lifecycle. */
    @After
    fun leaveOff() {
        KeyboardProbeReadings.setEnabled(false)
    }

    /** Waits up to [timeoutMs] for [predicate]; does NOT drain, so nothing reaches readEntry before the explicit after-wait drain. */
    private fun waitPending(main: ManualMain, predicate: () -> Boolean, timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }

    @Test
    fun `the published reading is delivered only after the main queue drains`() {
        val entries = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val main = ManualMain()
        val wiring = KeyboardTileWiring({ ime, focus, time -> entries.add(Triple(ime, focus, time)) }, main)
        wiring.start()
        try {
            KeyboardProbeReadings.publishLatest(fieldFocus = false, imeWindow = true, lastEvent = "typed", time = 1000L)
            // Without the post the entry is read synchronously on the collector thread,
            // so entries would already grow before any drain. Assert nothing lands until the drain happens.
            waitPending(main, { main.pending >= 1 })
            val before = entries.size
            main.drain()
            assertEquals("wiring: a published reading must be delivered once after the drain", before + 1, entries.size)
            assertEquals(
                "wiring: the delivered entry must carry ime, focus then time in publishLatest order",
                Triple(true, false, 1000L),
                entries.last(),
            )
        } finally {
            wiring.stop()
        }
    }

    @Test
    fun `a second start does not start a second collection`() {
        val entries = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val main = ManualMain()
        val wiring = KeyboardTileWiring({ ime, focus, time -> entries.add(Triple(ime, focus, time)) }, main)
        try {
            wiring.start()
            Thread.sleep(300)
            wiring.start()
            Thread.sleep(300)
            KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = false, lastEvent = "focus", time = 2000L)
            waitPending(main, { main.pending >= 1 })
            // Settle: give any spurious second block time to land, bounded to 5 s, then stop waiting when stable.
            var unchanged = 0
            val settleDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < settleDeadline) {
                val snapshot = main.pending
                Thread.sleep(100)
                if (main.pending == snapshot) {
                    unchanged += 1
                    if (unchanged >= 2) break
                } else {
                    unchanged = 0
                }
            }
            val before = entries.size
            main.drain()
            assertEquals(
                "wiring: one published reading must produce exactly one delivery even after a second start",
                before + 1,
                entries.size,
            )
        } finally {
            wiring.stop()
        }
    }

    @Test
    fun `the delivered entry carries ime first then focus`() {
        val entries = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val main = ManualMain()
        val wiring = KeyboardTileWiring({ ime, focus, time -> entries.add(Triple(ime, focus, time)) }, main)
        wiring.start()
        try {
            KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = false, lastEvent = "focus", time = 3000L)
            waitPending(main, { main.pending >= 1 })
            val before = entries.size
            main.drain()
            assertEquals(
                "wiring: the delivered entry must carry ime first then focus exactly as publishLatest receives them",
                Triple(false, true, 3000L),
                entries.last(),
            )
        } finally {
            wiring.stop()
        }
    }
}
