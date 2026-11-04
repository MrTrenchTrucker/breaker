package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The platform mode with no call or chat in progress: the plain int the platform hands back. */
private const val MODE_NORMAL = 0

/** The platform mode for a VoIP chat: it never keeps the busy face, whatever the list says. */
private const val MODE_IN_COMMUNICATION = 3

/**
 * The one reading of the platform's list of active recordings, driven on the JVM:
 * free when the phone is not in a call and no client other than the module's own
 * recording holds the microphone. The value type and the reading are plain Kotlin,
 * so no platform class is needed here and nothing is named in the test tree.
 */
internal class MicReleaseFreeTest {

    /** The session id of the module's own recording for these tests. */
    private val own: Int = 7

    /** One foreign recording, not silenced. */
    private val other: MicReleaseRecording = MicReleaseRecording(9, false)

    /** One foreign recording the platform silences. */
    private val otherSilenced: MicReleaseRecording = MicReleaseRecording(9, true)

    /** The module's own recording. */
    private val ownRecording: MicReleaseRecording = MicReleaseRecording(own, false)

    /** No recordings and no call: the microphone is free. */
    @Test
    fun `no recordings and no call is free`() {
        assertEquals("audio: an empty list in normal mode must be free", true, micIsFree(emptyList(), own, MODE_NORMAL))
    }

    /** One other recording: the microphone is busy. */
    @Test
    fun `one other recording is busy`() {
        assertEquals("audio: one other recording must keep it busy", false, micIsFree(listOf(other), own, MODE_NORMAL))
    }

    /** Only our own recording: the microphone is free (ours does not count). */
    @Test
    fun `only our own recording is free`() {
        assertEquals("audio: our own recording alone must be free", true, micIsFree(listOf(ownRecording), own, MODE_NORMAL))
    }

    /** Our own recording plus another: the microphone is busy. */
    @Test
    fun `our own recording plus another is busy`() {
        assertEquals("audio: own plus another must keep it busy", false, micIsFree(listOf(ownRecording, other), own, MODE_NORMAL))
    }

    /** The in-call mode keeps the face even with an empty list. */
    @Test
    fun `an in call mode with an empty list is busy`() {
        assertEquals("audio: an in call mode must keep it busy", false, micIsFree(emptyList(), own, AUDIO_MODE_IN_CALL))
    }

    /** A stuck in-communication mode does not keep the face. */
    @Test
    fun `an in communication mode with an empty list is free`() {
        assertEquals("audio: an in communication mode must not keep it busy", true, micIsFree(emptyList(), own, MODE_IN_COMMUNICATION))
    }

    /** A silenced other recording is still an other recording: busy. */
    @Test
    fun `a silenced other recording is busy`() {
        assertEquals("audio: a silenced other must keep it busy", false, micIsFree(listOf(otherSilenced), own, MODE_NORMAL))
    }

    /** With no own recording open, a foreign one keeps the face. */
    @Test
    fun `no own recording and one other is busy`() {
        assertEquals("audio: with no own session a foreign recording must keep it busy", false, micIsFree(listOf(other), NO_OWN_SESSION, MODE_NORMAL))
    }

    /** With no own recording open, an empty list is free. */
    @Test
    fun `no own recording and an empty list is free`() {
        assertEquals("audio: with no own session an empty list must be free", true, micIsFree(emptyList(), NO_OWN_SESSION, MODE_NORMAL))
    }

    /** A list the platform refuses to hand over reports nothing, so the face stays. */
    @Test
    fun `an unreadable list reports nothing`() {
        assertNull(
            "audio: an unreadable list must report nothing, not busy",
            freeReading({ throw RuntimeException("audio: the list cannot be read") }, { MODE_NORMAL }, { own }),
        )
    }

    /** In a call mode the reading is busy, whatever the list is: the face stays. */
    @Test
    fun `an in call mode reading of an empty list is busy`() {
        assertEquals(
            "audio: an in call mode reading of an empty list must be busy",
            false,
            freeReading({ emptyList() }, { AUDIO_MODE_IN_CALL }, { own }),
        )
    }

    /** A mode the platform refuses to hand over reports nothing, so the face stays. */
    @Test
    fun `a mode reading that throws reports nothing`() {
        assertNull(
            "audio: a throwing mode reading must report nothing, not busy",
            freeReading({ emptyList() }, { throw RuntimeException("audio: the mode cannot be read") }, { own }),
        )
    }

    /** An own session id the platform refuses to hand over reports nothing, so the face stays. */
    @Test
    fun `an own session reading that throws reports nothing`() {
        assertNull(
            "audio: a throwing own session reading must report nothing, not busy",
            freeReading({ emptyList() }, { MODE_NORMAL }, { throw RuntimeException("audio: the own session cannot be read") }),
        )
    }

    /**
     * The own session id and the phone mode go to the right slots in the reading: a foreign
     * recording whose id is the in-call mode number stays busy in a normal mode, and the
     * module's own recording alone is free.
     */
    @Test
    fun `the own session id and the mode go to the right slots in the reading`() {
        assertEquals(
            "audio: a foreign recording with the in call id must stay busy in a normal mode",
            false,
            freeReading({ listOf(MicReleaseRecording(AUDIO_MODE_IN_CALL, false)) }, { MODE_NORMAL }, { own }),
        )
        assertEquals(
            "audio: the module's own recording alone must be free in a normal mode",
            true,
            freeReading({ listOf(ownRecording) }, { MODE_NORMAL }, { own }),
        )
    }

    /** In a normal mode an empty list is free, read through the adapter's own reading. */
    @Test
    fun `a normal mode reading of an empty list is free`() {
        assertEquals(
            "audio: a normal mode reading of an empty list must be free",
            true,
            freeReading({ emptyList() }, { MODE_NORMAL }, { own }),
        )
    }
}
