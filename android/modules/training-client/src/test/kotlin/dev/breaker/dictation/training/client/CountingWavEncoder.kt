package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.port.WavEncoder

/**
 * A scripted WAV encoder for tests: the bytes depend only on the sample count, so a test can tell which
 * samples a file was made from. It counts its calls.
 */
class CountingWavEncoder : WavEncoder {
    var calls = 0
        private set

    override fun encode(pcm: FloatArray): ByteArray {
        calls += 1
        return expected(pcm)
    }

    /** The bytes this encoder returns for [pcm]. */
    fun expected(pcm: FloatArray): ByteArray =
        "FAKEWAV".toByteArray(Charsets.US_ASCII) + ByteArray(pcm.size) { (it % 100).toByte() }
}
