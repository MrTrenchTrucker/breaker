package dev.breaker.dictation.audio

/** The kinds of input the microphone choice tells apart, best first is decided by [MicRoutePolicy]. */
internal enum class MicDeviceKind { BLUETOOTH, WIRED, BUILT_IN }

/** One input that can record: its [kind] and the platform's [id] for it. */
internal data class MicDevice(val kind: MicDeviceKind, val id: Int)

/**
 * Lists the inputs that can record right now.
 *
 * Any order, possibly empty. It is asked again every time the route has to be
 * chosen, so the answer always reflects the devices present at that moment.
 */
internal fun interface MicDeviceSupplier {
    fun inputs(): List<MicDevice>
}
