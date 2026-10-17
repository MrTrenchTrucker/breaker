package dev.breaker.dictation.audio

/**
 * Chooses which input to record from.
 *
 * A Bluetooth microphone is preferred, a wired one comes next, and the
 * phone's own microphone is the last choice. Within one kind the device that
 * comes first in the list wins. Pure: the same list always gives the same
 * answer.
 */
internal object MicRoutePolicy {

    /** The best device in [devices], or null when the list is empty. */
    fun pick(devices: List<MicDevice>): MicDevice? =
        devices.firstOrNull { it.kind == MicDeviceKind.BLUETOOTH }
            ?: devices.firstOrNull { it.kind == MicDeviceKind.WIRED }
            ?: devices.firstOrNull { it.kind == MicDeviceKind.BUILT_IN }
}
