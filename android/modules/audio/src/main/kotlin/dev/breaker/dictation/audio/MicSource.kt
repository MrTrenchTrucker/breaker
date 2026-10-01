package dev.breaker.dictation.audio

/**
 * The device seam under [MicCapture].
 *
 * Everything above this interface — resampling, suppression, framing, threading
 * — is ordinary Kotlin and is exercised by unit tests. Everything below it is
 * the microphone driver, which can only be exercised on a device. Keeping the
 * driver behind an interface is what lets the pipeline be tested at all without
 * pretending a JVM test proved anything about the hardware.
 */
interface MicSource {
    /** Rate the hardware is capturing at. */
    val sampleRateHz: Int

    /** Interleaved channel count the hardware is capturing. */
    val channelCount: Int

    /**
     * Open the microphone.
     *
     * @throws MicSourceException if the microphone could not be opened — no
     *   permission, another app holds it, no such device.
     */
    fun open()

    /**
     * Read interleaved 16-bit PCM into [buffer] starting at [offset].
     *
     * Blocks until there is something to return. Returns the number of shorts
     * read, `0` if nothing arrived before the driver gave up waiting, or a
     * negative driver error code.
     */
    fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int

    /** Release the microphone. Safe to call when not open. */
    fun close()
}

/** Why a [MicSource] could not be opened, or stopped working. */
class MicSourceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
