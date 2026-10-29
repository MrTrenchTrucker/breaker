package dev.breaker.dictation.commit

import java.lang.reflect.Modifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.flow.first

/**
 * The probe holder: a disabled probe stores nothing, turning it off clears it, the
 * recent lines are bounded, and what the holder hands out is a copy.
 * The answer of isEnabled, on or off, is checked on its own and not only through what is stored.
 *
 * The holder is one process-wide object, so every test turns it off and clears it
 * before and after, and no test depends on what another test left behind.
 */
internal class KeyboardProbeReadingsTest {

    @Before
    fun startFromOff() {
        KeyboardProbeReadings.setEnabled(false)
        KeyboardProbeReadings.reset()
    }

    @After
    fun endOff() {
        KeyboardProbeReadings.setEnabled(false)
        KeyboardProbeReadings.reset()
    }

    private fun publishAt(time: Long) {
        KeyboardProbeReadings.publish(fieldFocus = true, imeWindow = false, lastEvent = "view focused", time = time)
    }

    /**
     * The sorted names of the public functions declared by [type]. A name with a dollar
     * sign is the compiler's name for an internal function, which is not public.
     */
    private fun publicMethodNames(type: Class<*>): List<String> =
        type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.name.contains('$') }
            .map { it.name }
            .sorted()

    @Test
    fun `a disabled probe stores nothing`() {
        publishAt(1L)

        assertNull("commit: a disabled probe must not store a latest reading", KeyboardProbeReadings.current())
        assertTrue("commit: a disabled probe must not append a recent line", KeyboardProbeReadings.recent().isEmpty())
    }

    @Test
    fun `turning the probe off clears the latest reading and the recent lines`() {
        KeyboardProbeReadings.setEnabled(true)
        publishAt(1L)
        publishAt(2L)

        KeyboardProbeReadings.setEnabled(false)

        assertNull("commit: turning the probe off must clear the latest reading", KeyboardProbeReadings.current())
        assertTrue("commit: turning the probe off must clear the recent lines", KeyboardProbeReadings.recent().isEmpty())
    }

    @Test
    fun `an enabled probe stores the latest reading and appends it`() {
        KeyboardProbeReadings.setEnabled(true)
        publishAt(1L)
        publishAt(2L)

        assertEquals("commit: the latest reading must be the last one published", 2L, KeyboardProbeReadings.current()?.time)
        assertEquals(
            "commit: the recent lines must hold both readings, oldest first",
            listOf(1L, 2L),
            KeyboardProbeReadings.recent().map { it.time },
        )
    }

    @Test
    fun `the recent list drops the oldest line at the two hundred first`() {
        KeyboardProbeReadings.setEnabled(true)
        for (time in 1L..201L) {
            publishAt(time)
        }

        val times: List<Long> = KeyboardProbeReadings.recent().map { it.time }
        assertEquals("commit: the recent list must hold at most 200 lines", 200, times.size)
        assertEquals(
            "commit: the recent list must drop the oldest line, so the first line kept is the second one published",
            (2L..201L).toList(),
            times,
        )
    }

    @Test
    fun `shouldProcessEvent returns false for Breaker's own package`() {
        assertFalse(
            "commit: an event from Breaker's own package must be skipped",
            KeyboardProbeReadings.shouldProcessEvent("dev.breaker.dictation", "dev.breaker.dictation"),
        )
    }

    @Test
    fun `shouldProcessEvent returns true for a foreign package`() {
        assertTrue(
            "commit: an event from a foreign package must be processed",
            KeyboardProbeReadings.shouldProcessEvent("com.other.app", "dev.breaker.dictation"),
        )
    }

    @Test
    fun `shouldProcessEvent returns true for a null package`() {
        assertTrue(
            "commit: an event with no package info must be processed",
            KeyboardProbeReadings.shouldProcessEvent(null, "dev.breaker.dictation"),
        )
    }

    @Test
    fun `the recent list is a copy`() {
        KeyboardProbeReadings.setEnabled(true)
        val earlier: List<ProbeReading> = KeyboardProbeReadings.recent()

        publishAt(1L)

        assertEquals("commit: a list handed out earlier must not change when a reading is published", 0, earlier.size)
        assertEquals("commit: the holder must hold the reading published after the copy", 1, KeyboardProbeReadings.recent().size)
    }

    @Test
    fun `the public members are exactly the documented ones`() {
        val documented: List<String> = listOf("current", "isEnabled", "publish", "publishLatest", "readings", "recent", "setEnabled", "shouldProcessEvent")
        assertEquals(
            "commit: the public functions of KeyboardProbeReadings must be exactly the documented ones",
            documented,
            publicMethodNames(KeyboardProbeReadings::class.java),
        )
    }

    @Test
    fun `the probe is off at the start`() {
        assertFalse("commit: the probe must be off before anything turns it on", KeyboardProbeReadings.isEnabled())
    }

    @Test
    fun `the probe is on after turning it on`() {
        KeyboardProbeReadings.setEnabled(true)

        assertTrue("commit: turning the probe on must make isEnabled true", KeyboardProbeReadings.isEnabled())
    }

    @Test
    fun `the probe is off after turning it off`() {
        KeyboardProbeReadings.setEnabled(true)
        assertTrue("commit: the probe must be on before it is turned off", KeyboardProbeReadings.isEnabled())

        KeyboardProbeReadings.setEnabled(false)

        assertFalse("commit: turning the probe off must make isEnabled false", KeyboardProbeReadings.isEnabled())
    }

    @Test
    fun `the probe is off after a reset`() {
        KeyboardProbeReadings.setEnabled(true)
        assertTrue("commit: the probe must be on before the reset", KeyboardProbeReadings.isEnabled())

        KeyboardProbeReadings.reset()

        assertFalse("commit: a reset must leave the probe off", KeyboardProbeReadings.isEnabled())
    }

    @Test
    fun `the limit is two hundred`() {
        assertEquals("commit: the recent limit must be 200", 200, KeyboardProbeReadings.RECENT_LIMIT)
    }

    /** The latest reading the live view hands out now, read through the public flow. */
    private fun liveViewLatest(): ProbeReading? {
        var outcome: Result<ProbeReading?>? = null
        val body: suspend () -> ProbeReading? = { KeyboardProbeReadings.readings().first() }
        body.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return checkNotNull(outcome) { "commit: the live view did not answer at once" }.getOrThrow()
    }

    @Test
    fun `a reset hands null to the reader of the live view`() {
        KeyboardProbeReadings.setEnabled(true)
        KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = false, lastEvent = "typed", time = 9L)
        assertEquals(
            "commit: the reader of the live view must see the published reading",
            9L,
            liveViewLatest()?.time,
        )
        KeyboardProbeReadings.reset()
        assertNull(
            "commit: a reset must hand null to the reader of the live view",
            liveViewLatest(),
        )
    }
}
