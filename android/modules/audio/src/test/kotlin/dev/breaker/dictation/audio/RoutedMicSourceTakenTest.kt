package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A microphone that another app or a call has taken over: the platform
 * silences this recorder's audio, and the source must notice and report
 * the take with the right reason.
 *
 * A failure means the source does not check for silence, checks too often
 * or too rarely, mistakes a silent recorder for a taken one, or reports
 * the take as an ordinary device failure.
 */
class RoutedMicSourceTakenTest {

    private val buffer = ShortArray(64)

    private class Rig(initial: List<MicDevice>, sampleRateHz: Int = 16_000) {
        val port = FakeMicInputPort(sampleRateHz = sampleRateHz)
        val devices = FixedDevices(initial)
        val source = RoutedMicSource(devices, port)
    }

    @Test
    fun `a silenced recorder reports the taken reason`() {
        val rig = Rig(listOf(builtIn(1)))
        rig.port.silencedNow = true
        rig.source.open()
        val error = expectMicFailure("a silenced recorder") { rig.source.read(buffer, 0, buffer.size) }
        assertEquals(
            "audio: a silenced recorder must report the taken reason",
            MicSourceException.Reason.MICROPHONE_TAKEN,
            error.reason,
        )
    }

    @Test
    fun `a recorder with no configuration of its own is not taken`() {
        val rig = Rig(listOf(builtIn(1)))
        rig.port.silencedNow = false
        rig.source.open()
        rig.port.reads += 10
        assertEquals(
            "audio: a recorder that is not silenced must read normally",
            10,
            rig.source.read(buffer, 0, buffer.size),
        )
    }

    @Test
    fun `the silence check runs at a bounded rate, not every read`() {
        val rig = Rig(listOf(builtIn(1)), sampleRateHz = 1000)
        rig.port.silencedNow = false
        rig.source.open()
        // 11 reads of 10 shorts each: the first read checks (samplesSinceCheck
        // starts at the interval), then 10 more reads advance the counter to
        // 100, and the 11th read checks again.
        for (i in 1..11) {
            rig.port.reads += 10
            rig.source.read(buffer, 0, buffer.size)
        }
        assertEquals(
            "audio: the silence check must run at a bounded rate, not every read",
            2,
            rig.port.silencedChecks,
        )
    }

    @Test
    fun `a silence that starts between checks is seen at the next check`() {
        val rig = Rig(listOf(builtIn(1)), sampleRateHz = 1000)
        rig.port.silencedNow = false
        rig.source.open()
        // First read: checks (silencedNow = false), reads 10 shorts.
        rig.port.reads += 10
        rig.source.read(buffer, 0, buffer.size)
        assertEquals("audio: the first read must have checked", 1, rig.port.silencedChecks)
        // Silence starts between checks.
        rig.port.silencedNow = true
        // Read until the next check (10 more reads of 10 shorts = 100 samples).
        var thrown: MicSourceException? = null
        for (i in 1..10) {
            rig.port.reads += 10
            try {
                rig.source.read(buffer, 0, buffer.size)
            } catch (e: MicSourceException) {
                thrown = e
                break
            }
        }
        assertTrue(
            "audio: the silence must be seen at the next check",
            thrown != null,
        )
        assertEquals(
            "audio: the silence must report the taken reason",
            MicSourceException.Reason.MICROPHONE_TAKEN,
            thrown!!.reason,
        )
        assertEquals(
            "audio: the silence must be seen on the check that follows the change",
            2,
            rig.port.silencedChecks,
        )
    }

    @Test
    fun `the first read of a take checks at once`() {
        val rig = Rig(listOf(builtIn(1)))
        rig.port.silencedNow = true
        rig.source.open()
        val error = expectMicFailure("a take that opens into a silenced microphone") {
            rig.source.read(buffer, 0, buffer.size)
        }
        assertEquals(
            "audio: the first read must report the taken reason",
            MicSourceException.Reason.MICROPHONE_TAKEN,
            error.reason,
        )
        assertEquals(
            "audio: the first read must check at once",
            1,
            rig.port.silencedChecks,
        )
    }
}
