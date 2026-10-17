package dev.breaker.dictation.audio

/**
 * The seam below [RoutedMicSource]: one recording device at a time, opened by
 * choice, read in blocks, and able to say that the device it was opened on has
 * gone away.
 *
 * Everything that names the platform lives behind this interface, so the
 * choice of device and the switch between devices are ordinary Kotlin that a
 * JVM test can drive.
 */
internal interface MicInputPort {
    /** Rate the port records at, in hertz. */
    val sampleRateHz: Int

    /**
     * Open [device], or the system default input when [device] is null.
     *
     * @throws MicSourceException if it cannot be opened: no permission, in use,
     *   no such device, or the rate refused.
     */
    fun open(device: MicDevice?)

    /**
     * Read 16-bit PCM exactly as [MicSource.read] does: blocks, returns more
     * than 0 shorts read, 0 for nothing yet, or a negative driver error code.
     */
    fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int

    /**
     * True from the moment the device chosen at [open] went away or the route
     * changed, until the next [open]. Reading the answer does not clear it, so
     * a loss reported while the caller is looking cannot be missed; the caller
     * closes and opens again after a true answer, and that open clears it.
     */
    fun routeLost(): Boolean

    /**
     * True while the platform is silencing THIS recorder's audio because another
     * client (another app, or a call) holds the microphone.
     *
     * Read on demand — there is no callback to wait for. False when the recorder
     * reports no configuration of its own, so "unknown" is not mistaken for "taken".
     */
    fun silenced(): Boolean

    /** Release the device. Safe when not open and safe to call twice. */
    fun close()
}
