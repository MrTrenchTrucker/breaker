package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import kotlin.math.PI
import kotlin.math.sin

/**
 * Signal builders shared by the audio tests.
 *
 * Every signal is built from a stated amplitude so a test can assert on energy
 * rather than on identity: "the retained audio is the loud part" is a claim a
 * broken trim fails, whereas "the retained audio equals the input" is a claim a
 * broken trim can pass by accident when the input happens to be uniform.
 */
object AudioSignals {
    const val SILENCE_AMPLITUDE = 0.001f
    const val SPEECH_AMPLITUDE = 0.4f
    const val NOISE_AMPLITUDE = 0.05f

    /** Digital silence, exactly zero. */
    fun silence(samples: Int): FloatArray = FloatArray(samples)

    /**
     * A low hum, standing in for the room: not zero, because a real cab is
     * never zero, but well under speech.
     */
    fun roomTone(samples: Int, amplitude: Float = NOISE_AMPLITUDE): FloatArray {
        val out = FloatArray(samples)
        for (i in out.indices) out[i] = amplitude * sin(2.0 * PI * 50.0 * i / 16_000.0).toFloat()
        return out
    }

    /**
     * A vowel-ish buzz: a fundamental with two harmonics, so it has spectral
     * content and a recognisable pitch rather than being a pure tone.
     */
    fun speech(samples: Int, amplitude: Float = SPEECH_AMPLITUDE, startSample: Int = 0): FloatArray {
        val out = FloatArray(samples)
        for (i in out.indices) {
            val t = (i + startSample).toDouble()
            val phase = 2.0 * PI * t / 16_000.0
            out[i] = amplitude * (
                sin(phase * 220.0) +
                    0.5 * sin(phase * 440.0) +
                    0.25 * sin(phase * 660.0)
                ).toFloat() / 1.75f
        }
        return out
    }

    /**
     * A pure tone, for measuring level and frequency.
     *
     * [sampleRateHz] is the rate [samples] is recorded at, and it is not always
     * 16 kHz: a tone generated for a 48 kHz stream at 16 kHz spacing is really
     * three times the frequency asked for, which a resampler then faithfully
     * reproduces.
     */
    fun tone(
        samples: Int,
        hz: Double,
        amplitude: Float,
        startSample: Int = 0,
        sampleRateHz: Double = 16_000.0,
    ): FloatArray {
        val out = FloatArray(samples)
        for (i in out.indices) {
            out[i] = amplitude * sin(2.0 * PI * hz * (i + startSample) / sampleRateHz).toFloat()
        }
        return out
    }

    /**
     * Deterministic noise in `[-amplitude, amplitude]`, from a fixed seed.
     *
     * A fixed seed rather than [kotlin.random.Random] so a failing test fails
     * the same way twice.
     */
    fun noise(samples: Int, amplitude: Float = NOISE_AMPLITUDE, seed: Long = 20_260_101L): FloatArray {
        val out = FloatArray(samples)
        var state = seed
        for (i in out.indices) {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            out[i] = amplitude * (((state ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0)
                .toFloat()
        }
        return out
    }

    /** Room tone, then speech, then room tone: the shape of a real take. */
    fun takeWithSurroundingSilence(
        leadingMs: Long,
        speechMs: Long,
        trailingMs: Long,
    ): FloatArray {
        val leading = (leadingMs * AudioFormat.SAMPLE_RATE_HZ / 1000L).toInt()
        val speechSamples = (speechMs * AudioFormat.SAMPLE_RATE_HZ / 1000L).toInt()
        val trailing = (trailingMs * AudioFormat.SAMPLE_RATE_HZ / 1000L).toInt()
        val out = FloatArray(leading + speechSamples + trailing)
        roomTone(leading).copyInto(out, 0)
        speech(speechSamples).copyInto(out, leading)
        roomTone(trailing).copyInto(out, leading + speechSamples)
        return out
    }

    /** Root-mean-square level of [samples]. */
    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        samples.forEach { sum += (it * it).toDouble() }
        return Math.sqrt(sum / samples.size).toFloat()
    }

    /** The loudest absolute value in [samples]. */
    fun peak(samples: FloatArray): Float {
        var peak = 0f
        samples.forEach { peak = maxOf(peak, kotlin.math.abs(it)) }
        return peak
    }

    /** Samples of [signal] equal to [window] starting at [from], for comparison. */
    fun slice(signal: FloatArray, from: Int, window: Int): FloatArray =
        signal.copyOfRange(from.coerceIn(0, signal.size), (from + window).coerceIn(0, signal.size))

    fun ms(samples: Int): Int = (samples * 1000L / AudioFormat.SAMPLE_RATE_HZ).toInt()
}
