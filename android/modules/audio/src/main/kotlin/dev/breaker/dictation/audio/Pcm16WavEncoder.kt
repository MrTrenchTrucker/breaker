package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.port.WavEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Wraps 16 kHz mono float PCM in a 16-bit WAV container.
 *
 * The domain needs the same dictation audio in two shapes — raw samples for the
 * on-device engine, a WAV file to upload to the Local Server — so this class
 * implements the domain's `WavEncoder` port and owns the container format.
 *
 * ### The container
 *
 * A canonical 44-byte RIFF/WAVE header followed by little-endian signed 16-bit
 * samples. 16-bit is what every consumer of the upload path expects, and it is
 * the same shape the on-device engine reads after its own conversion, so the
 * two paths do not disagree about what was said.
 *
 * A sample is clamped into `[-1, 1]` before scaling, so an over-range float
 * (a hot mic, an already-loud frame) becomes full scale rather than wrapping
 * around to the opposite polarity — a wrapped sample is an audible crack, and
 * full scale is merely loud.
 *
 * An empty input is not an error: it encodes to a valid header with a
 * zero-length data chunk, which is what a recorder that captured nothing should
 * hand on rather than throwing at the end of a take.
 *
 * Thread-safe: it holds no state.
 */
class Pcm16WavEncoder(
    private val sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ,
    private val channelCount: Int = AudioFormat.CHANNEL_COUNT,
) : WavEncoder {

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(channelCount == AudioFormat.CHANNEL_COUNT) {
            "the dictation audio contract is mono (${AudioFormat.CHANNEL_COUNT} channel), " +
                "not $channelCount — resample or downmix at the capture boundary"
        }
    }

    /** Bytes of RIFF header before the first sample. */
    val headerSizeBytes: Int = HEADER_BYTES

    override fun encode(pcm: FloatArray): ByteArray {
        val dataSizeBytes = pcm.size * BYTES_PER_SAMPLE
        val bytes = ByteArray(HEADER_BYTES + dataSizeBytes)
        val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk: everything after the first 8 bytes.
        out.put(RIFF)
        out.putInt(4 + (8 + FORMAT_CHUNK_BYTES) + (8 + dataSizeBytes))
        out.put(WAVE)
        out.put(FMT)
        out.putInt(FORMAT_CHUNK_BYTES)          // PCM fmt chunk is 16 bytes
        out.putShort(FORMAT_PCM.toShort())      // 1 = uncompressed integer PCM
        out.putShort(channelCount.toShort())
        out.putInt(sampleRateHz)
        out.putInt(sampleRateHz * channelCount * BYTES_PER_SAMPLE) // byte rate
        out.putShort((channelCount * BYTES_PER_SAMPLE).toShort())  // block align
        out.putShort((BYTES_PER_SAMPLE * 8).toShort())             // bits per sample
        out.put(DATA)
        out.putInt(dataSizeBytes)

        pcm.forEach { sample ->
            val clamped = sample.coerceIn(-1f, 1f)
            // Asymmetric full scale: the negative rail is one code wider than
            // the positive one, so a full-scale negative sample uses all 16 bits
            // instead of losing the last one to rounding.
            val scaled = if (clamped < 0f) {
                (clamped * -MIN_SAMPLE).roundToInt()
            } else {
                (clamped * MAX_SAMPLE).roundToInt()
            }
            out.putShort(scaled.coerceIn(MIN_SAMPLE, MAX_SAMPLE).toShort())
        }
        return bytes
    }

    companion object {
        /** Bytes of RIFF header before the first sample. */
        const val HEADER_BYTES: Int = 44

        /** Bytes per sample in the 16-bit container. */
        const val BYTES_PER_SAMPLE: Int = 2

        private const val FORMAT_CHUNK_BYTES = 16
        private const val FORMAT_PCM = 1
        private const val FULL_SCALE = 32767f
        private const val MAX_SAMPLE = 32767
        private const val MIN_SAMPLE = -32768

        private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
        private val FMT = "fmt ".toByteArray(Charsets.US_ASCII)
        private val DATA = "data".toByteArray(Charsets.US_ASCII)
    }
}
