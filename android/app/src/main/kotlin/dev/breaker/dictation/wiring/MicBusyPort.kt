package dev.breaker.dictation.wiring

/**
 * The one narrow port over the tile's shared MIC_BUSY flag. Home reads the flag, registers the one
 * change listener, and forwards the three begin answers to the tile's take-yield controller; nothing
 * else. The tile host builds the one [MicBusyPortForwarder] over its coordinator's controller, and the
 * Home take wiring takes this port so it never reaches into the tile by name.
 */
internal interface MicBusyPort {
    /** The current MIC_BUSY face flag, read from the tile's shared controller. */
    fun micBusy(): Boolean

    /** Registers the one listener fired when the busy flag flips (null clears it). */
    fun setBusyListener(listener: ((Boolean) -> Unit)?)

    /** A Home begin was accepted: the busy face clears on the controller's next state change. */
    fun beginAccepted()

    /** A Home begin was refused as taken: the busy face stays on the controller. */
    fun beginRefusedAsTaken()

    /** A Home begin failed on the device (a FAILED face is not a busy one): the flag clears. */
    fun beginFailed()
}

/** Forwards every [MicBusyPort] call one-to-one to the tile's shared take-yield controller. */
internal class MicBusyPortForwarder(private val yield: TakeYieldController) : MicBusyPort {
    override fun micBusy(): Boolean = yield.micBusy.get()
    override fun setBusyListener(listener: ((Boolean) -> Unit)?) { yield.onBusyChanged = listener }
    override fun beginAccepted() { yield.onBeginAccepted() }
    override fun beginRefusedAsTaken() { yield.beginRefusedAsTaken() }
    override fun beginFailed() { yield.onBeginFailed() }
}

/**
 * A [MicBusyPort] that never busy's and ignores its listener: the default a wiring or adapter is given
 * when no tile host wires the real one, so a test that builds either without a busy port keeps running.
 */
internal object NoopMicBusyPort : MicBusyPort {
    override fun micBusy(): Boolean = false
    override fun setBusyListener(listener: ((Boolean) -> Unit)?) {}
    override fun beginAccepted() {}
    override fun beginRefusedAsTaken() {}
    override fun beginFailed() {}
}
