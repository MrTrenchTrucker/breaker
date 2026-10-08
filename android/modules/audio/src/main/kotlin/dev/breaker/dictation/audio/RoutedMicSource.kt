package dev.breaker.dictation.audio

/**
 * A [MicSource] that records from the best microphone present and follows it
 * when it changes.
 *
 * The choice is [MicRoutePolicy]'s: Bluetooth, then wired, then the phone's
 * own microphone. Falling back to a lesser device is silent, whether it
 * happens at [open] or in the middle of a take: nothing is shown, reported or
 * logged.
 *
 * A device that goes away mid-take is absorbed inside [read]. The next read
 * lists the devices again, picks again, closes the port and opens the new
 * choice, then reads from it in the same call, so the capture sees an
 * unbroken stream rather than the end of the take.
 *
 * An unplug can also show first as a failed read, before the platform reports
 * the loss. When a read fails and the device in use is no longer listed (or the
 * port reports the route lost), the source switches in the same way and reads
 * once more. It never switches more than once per call: a second failure, or a
 * failure with the device still present, or a failure on the system default
 * input, ends the take with a [MicSourceException].
 *
 * Not safe for concurrent use: the capture calls it one call at a time.
 */
internal class RoutedMicSource(
    private val devices: MicDeviceSupplier,
    private val port: MicInputPort,
) : MicSource {

    override val sampleRateHz: Int get() = port.sampleRateHz

    override val channelCount: Int get() = 1

    private var opened = false

    /** The device the port was last opened on; null for the system default or when closed. */
    private var openDevice: MicDevice? = null

    override fun open() {
        // Never two opens without a close between them.
        if (opened) {
            port.close()
            opened = false
        }
        openBest()
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
        if (!opened) throw MicSourceException("audio: the microphone is not open")
        var switched = false
        if (port.routeLost()) {
            switchRoute()
            switched = true
        }
        var count = port.read(buffer, offset, lengthInShorts)
        if (count < 0 && !switched && deviceVanished()) {
            switchRoute()
            count = port.read(buffer, offset, lengthInShorts)
        }
        if (count < 0) {
            throw MicSourceException("audio: the microphone failed while recording (driver code $count)")
        }
        return count
    }

    override fun close() {
        if (!opened) return
        opened = false
        openDevice = null
        port.close()
    }

    /** True when the device in use is gone from the list or the port reports the route lost. */
    private fun deviceVanished(): Boolean {
        val inUse = openDevice ?: return false
        return port.routeLost() || inUse !in devices.inputs()
    }

    private fun switchRoute() {
        port.close()
        opened = false
        openDevice = null
        openBest()
    }

    /**
     * Open the best device now present. If that fails and it was not the phone's
     * own microphone, try that one (or the system default when the list has no
     * built-in input) once, silently.
     */
    private fun openBest() {
        val present = devices.inputs()
        val picked = MicRoutePolicy.pick(present)
        try {
            port.open(picked)
            opened = true
            openDevice = picked
            return
        } catch (first: MicSourceException) {
            port.close()
            if (picked == null || picked.kind == MicDeviceKind.BUILT_IN) throw couldNotOpen(first)
            val builtIn = present.firstOrNull { it.kind == MicDeviceKind.BUILT_IN }
            try {
                port.open(builtIn)
                opened = true
                openDevice = builtIn
            } catch (second: MicSourceException) {
                port.close()
                throw couldNotOpen(second)
            }
        }
    }

    private fun couldNotOpen(cause: MicSourceException) =
        MicSourceException("audio: the microphone could not be opened", cause)
}
