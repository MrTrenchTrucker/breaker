package dev.breaker.dictation.core.port

/**
 * Wraps raw PCM in a WAV container.
 *
 * The domain needs the same audio in two shapes — raw samples for the on-device
 * engine, a WAV file for upload to the Local Server — and encoding is the audio
 * module's job, not the domain's.
 */
interface WavEncoder {
    /**
     * Encode 16 kHz mono float [pcm] as WAV bytes. The result is a complete,
     * playable file, header included.
     */
    fun encode(pcm: FloatArray): ByteArray
}
