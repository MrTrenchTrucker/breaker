package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The WAV container the server upload path depends on.
 *
 * A malformed header does not fail loudly on the phone — it fails on the
 * server, or worse, is accepted and decoded as noise — so the header is
 * checked field by field rather than by "the bytes look like a file".
 */
class Pcm16WavEncoderTest {

    private val encoder = Pcm16WavEncoder()

    @Test
    fun `a wav file carries the four RIFF chunks in order`() {
        val bytes = encoder.encode(FloatArray(160) { 0.25f })
        val text = String(bytes, Charsets.ISO_8859_1)
        assertEquals("RIFF", text.substring(0, 4))
        assertEquals("WAVE", text.substring(8, 12))
        assertEquals("fmt ", text.substring(12, 16))
        assertEquals("data", text.substring(36, 40))
    }

    @Test
    fun `the header declares 16 kHz mono 16-bit PCM`() {
        val bytes = encoder.encode(FloatArray(160))
        assertEquals(1, readShort(bytes, 20))       // audioFormat: 1 = PCM
        assertEquals(1, readShort(bytes, 22))       // channels
        assertEquals(16_000, readInt(bytes, 24))    // sample rate
        assertEquals(32_000, readInt(bytes, 28))   // byte rate = 16000 * 1 * 2
        assertEquals(2, readShort(bytes, 32))       // block align
        assertEquals(16, readShort(bytes, 34))      // bits per sample
    }

    @Test
    fun `the header follows the dictation audio contract rather than a default`() {
        // The contract lives in core. If that changes, this fails rather than
        // quietly encoding at a rate the engines do not expect.
        assertEquals(AudioFormat.SAMPLE_RATE_HZ, readInt(encoder.encode(FloatArray(8)), 24))
        assertEquals(AudioFormat.CHANNEL_COUNT, readShort(encoder.encode(FloatArray(8)), 22))
    }

    @Test
    fun `the chunk sizes describe the actual payload`() {
        val samples = 800
        val bytes = encoder.encode(FloatArray(samples) { 0.1f })
        assertEquals(Pcm16WavEncoder.HEADER_BYTES + samples * 2, bytes.size)
        assertEquals(samples * 2, readInt(bytes, 40))                    // data chunk size
        assertEquals(bytes.size - 8, readInt(bytes, 4))                 // RIFF chunk size
    }

    @Test
    fun `samples survive the trip through 16 bits`() {
        val pcm = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 0.25f, -0.25f)
        val bytes = encoder.encode(pcm)
        val decoded = pcm.indices.map { readShort(bytes, Pcm16WavEncoder.HEADER_BYTES + it * 2) / 32768f }
        // One 16-bit step is 1/32768, so a sample must land within a step or two.
        pcm.indices.forEach { index ->
            assertTrue(
                "sample $index was ${pcm[index]} and came back ${decoded[index]}, " +
                    "further than 16-bit quantisation allows",
                kotlin.math.abs(pcm[index] - decoded[index]) <= 2f / 32768f,
            )
        }
    }

    @Test
    fun `an empty capture still encodes to a valid file`() {
        // A take that captured nothing must hand on a parseable file, not throw
        // at the end of the recording.
        val bytes = encoder.encode(FloatArray(0))
        assertEquals(Pcm16WavEncoder.HEADER_BYTES, bytes.size)
        assertEquals(0, readInt(bytes, 40))
        assertEquals(bytes.size - 8, readInt(bytes, 4))
    }

    @Test
    fun `a sample past full scale is clamped rather than wrapped`() {
        // Wrapping 2.0 to -1.0 is an audible crack; full scale is merely loud.
        val bytes = encoder.encode(floatArrayOf(4f, -4f))
        assertEquals(32_767, readShort(bytes, Pcm16WavEncoder.HEADER_BYTES))
        assertEquals(-32_768, readShort(bytes, Pcm16WavEncoder.HEADER_BYTES + 2))
    }

    @Test
    fun `a stereo encoder is refused at construction`() {
        try {
            Pcm16WavEncoder(channelCount = 2)
            fail("expected a stereo encoder to be refused: the contract is mono")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "the message should say the contract is mono, was: ${e.message}",
                e.message!!.contains("mono"),
            )
        }
    }

    @Test
    fun `a non-positive rate is refused at construction`() {
        try {
            Pcm16WavEncoder(sampleRateHz = 0)
            fail("expected a zero sample rate to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sampleRateHz"))
        }
    }

    @Test
    fun `encoding the same audio twice gives the same bytes`() {
        // The take is handed to a retry path; an encoder that leaked state would
        // make a retry upload something different from the first attempt.
        val pcm = AudioSignals.speech(400)
        assertArrayEquals(encoder.encode(pcm), encoder.encode(pcm))
    }

    @Test
    fun `the payload is the audio, not a fixed block`() {
        val quiet = encoder.encode(AudioSignals.roomTone(400))
        val loud = encoder.encode(AudioSignals.speech(400))
        assertTrue(
            "two different captures produced identical audio bytes, so the " +
                "samples are not being written",
            !quiet.contentEquals(loud),
        )
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /** A signed 16-bit little-endian read, which is how a WAV stores a sample. */
    private fun readShort(bytes: ByteArray, offset: Int): Int {
        val raw = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return if (raw and 0x8000 != 0) raw - 0x10000 else raw
    }
}
