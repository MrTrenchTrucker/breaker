package dev.breaker.dictation.commit

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The live view [readings]: it carries the current reading at attach, updates on each change to a
 * reading, and hands null again after a clear or a disable. It is a read-only flow of the holder's
 * latest reading, so observing it reads exactly what the production write stored. This is plain
 * JUnit 4 over the public API only. A read starts its coroutine with the standard library's start
 * function and no dispatcher, so it runs on the calling thread and nothing waits. The holder is one
 * process-wide object, so every test turns it off and clears it before and after, and no test
 * depends on what another left behind.
 */
internal class KeyboardProbeReadingsLiveViewTest {

    /** A collector attached to the live view when it is made; it is never detached. */
    private class Collector {
        val values: MutableList<ProbeReading?> = mutableListOf()

        init {
            val body: suspend () -> Unit = { KeyboardProbeReadings.readings().collect { values.add(it) } }
            body.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
        }
    }

    /**
     * The first value of the live view, read now. The holder's flow answers at once and does not
     * suspend, so the read is complete when this returns; a read that would suspend fails here.
     */
    private fun liveNow(): ProbeReading? {
        var outcome: Result<ProbeReading?>? = null
        val body: suspend () -> ProbeReading? = { KeyboardProbeReadings.readings().first() }
        body.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return checkNotNull(outcome) { "commit: the live view did not answer at once" }.getOrThrow()
    }

    @Before
    fun startOff() {
        KeyboardProbeReadings.setEnabled(false)
        KeyboardProbeReadings.reset()
    }

    @After
    fun endOff() {
        KeyboardProbeReadings.setEnabled(false)
        KeyboardProbeReadings.reset()
    }

    private fun reading(field: Boolean, event: String, time: Long): ProbeReading =
        KeyboardProbeReadings.publishLatest(field, false, event, time)

    @Test
    fun `a fresh live view reads null`() {
        assertNull("commit: a fresh live view must read null before anything is published", liveNow())
    }

    @Test
    fun `a published reading is carried by the live view`() {
        KeyboardProbeReadings.setEnabled(true)
        val expected = reading(true, "typed in", 1L)
        assertEquals("commit: a published reading must be carried by the live view", expected, liveNow())
    }

    @Test
    fun `a late reader sees the current value at attach`() {
        KeyboardProbeReadings.publishLatest(false, false, "quiet", 5L)
        assertEquals(
            "commit: a late collector must see the just-published reading as its first value",
            ProbeReading(false, false, "quiet", 5L),
            liveNow(),
        )
    }

    @Test
    fun `a later publish reaches the live view after attach`() {
        val collector = Collector()
        val first = reading(true, "typed in", 1L)
        assertEquals("commit: the live view must carry the first published reading", first, collector.values.last())
        val second = ProbeReading(true, false, "typed in", 2L)
        KeyboardProbeReadings.publishLatest(second.fieldFocus, false, second.lastEvent, second.time)
        assertEquals(
            "commit: the live view must carry the later published reading, not a snapshot of the first",
            second,
            collector.values.last(),
        )
    }

    @Test
    fun `a disable hands null to the live view`() {
        val expected = reading(true, "typed in", 1L)
        assertEquals("commit: a published reading must be carried by the live view", expected, liveNow())
        KeyboardProbeReadings.setEnabled(false)
        assertNull("commit: disabling after a live reading must hand null to the live view", liveNow())
    }

    @Test
    fun `a publish while the probe is off still reaches the live view`() {
        val expected = ProbeReading(false, false, "quiet", 5L)
        KeyboardProbeReadings.publishLatest(expected.fieldFocus, expected.imeWindow, expected.lastEvent, expected.time)
        assertEquals("commit: an off probe's production write must still reach the live view", expected, liveNow())
    }
}
