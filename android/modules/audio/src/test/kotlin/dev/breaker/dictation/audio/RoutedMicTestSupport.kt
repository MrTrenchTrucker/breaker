package dev.breaker.dictation.audio

import org.junit.Assert.fail

/**
 * Fixtures for the routed microphone tests: a fake port that records what it
 * was asked in order, a supplier that returns a list the test can change, and
 * the short helpers that name devices and events.
 *
 * Everything is driven synchronously from the test: no clock and no second line of execution.
 */

internal fun bt(id: Int) = MicDevice(MicDeviceKind.BLUETOOTH, id)

internal fun wired(id: Int) = MicDevice(MicDeviceKind.WIRED, id)

internal fun builtIn(id: Int) = MicDevice(MicDeviceKind.BUILT_IN, id)

/** The event the fake port records when it is opened on [device]. */
internal fun openEvent(device: MicDevice?): String =
    if (device == null) "open:default" else "open:${device.kind}:${device.id}"

internal const val CLOSE_EVENT = "close"
internal const val READ_EVENT = "read"

/** A supplier that returns [list] as it stands at the moment of each call. */
internal class FixedDevices(var list: List<MicDevice>) : MicDeviceSupplier {
    /** How many times the devices were listed. */
    var listCalls = 0
        private set

    override fun inputs(): List<MicDevice> {
        listCalls++
        return list
    }
}

/**
 * A port the test drives.
 *
 * Every call is appended to [events] in order. [read] hands out [reads] one
 * result at a time and fills the buffer with a marker that names the device
 * the port is open on, so a test can tell whose data it got. A script that
 * runs dry, a read on a closed port, or an open on an open port fails the
 * test by name rather than returning something plausible.
 */
internal class FakeMicInputPort(
    override val sampleRateHz: Int = 16_000,
) : MicInputPort {

    /** Every call in order: [openEvent] strings, [READ_EVENT], [CLOSE_EVENT]. */
    val events = mutableListOf<String>()

    /** Results the next reads return, front first. */
    val reads = ArrayDeque<Int>()

    /** Device ids whose open throws [MicSourceException]. */
    val failingIds = mutableSetOf<Int>()

    /** Whether opening the system default input throws. */
    var failDefault = false

    /** Set to make [routeLost] answer true; it stays true until the next [open], as the real port does. */
    var lostPending = false

    /** Set to make the next [read] report the route lost once it has returned, as a callback can. */
    var lostDuringRead = false

    /** The device the port is open on, valid while [isOpen]. */
    var current: MicDevice? = null
        private set

    var isOpen = false
        private set

    /** Set to make [silenced] answer true, as the platform does when another client takes the mic. */
    var silencedNow = false

    /** How many times [silenced] has been asked, so a test can pin the check rate. */
    var silencedChecks = 0
        private set

    override fun open(device: MicDevice?) {
        events += openEvent(device)
        lostPending = false
        if (isOpen) fail("audio: the fake port was opened while already open (open without a close)")
        val refused = if (device == null) failDefault else device.id in failingIds
        if (refused) throw MicSourceException("fake: cannot open ${openEvent(device)}")
        current = device
        isOpen = true
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
        events += READ_EVENT
        if (!isOpen) fail("audio: the fake port was read while not open")
        if (reads.isEmpty()) fail("audio: the fake port has no scripted read left")
        val result = reads.removeFirst()
        if (lostDuringRead) {
            lostDuringRead = false
            lostPending = true
        }
        if (result > 0) {
            val marker = markerOf(current)
            for (i in 0 until result) buffer[offset + i] = marker
        }
        return result
    }

    override fun routeLost(): Boolean {
        return lostPending
    }

    override fun silenced(): Boolean {
        silencedChecks++
        return silencedNow
    }

    override fun close() {
        events += CLOSE_EVENT
        isOpen = false
        current = null
    }

    /** The marker value [read] writes while open on [device]. */
    fun markerOf(device: MicDevice?): Short = (device?.id ?: DEFAULT_MARKER).toShort()

    companion object {
        /** Marker written while the system default input is open. */
        const val DEFAULT_MARKER = 99
    }
}

/** Runs [block] and returns the [MicSourceException] it threw; fails by name if none. */
internal fun expectMicFailure(what: String, block: () -> Unit): MicSourceException {
    try {
        block()
    } catch (e: MicSourceException) {
        return e
    }
    fail("audio: $what: expected a MicSourceException but nothing was thrown")
    throw IllegalStateException("unreachable")
}

/** True when the first [count] shorts of [buffer] all equal [marker]. */
internal fun filledWith(buffer: ShortArray, count: Int, marker: Short): Boolean =
    (0 until count).all { buffer[it] == marker }
