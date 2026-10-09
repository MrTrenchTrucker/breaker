package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingPalette

/**
 * A [TileWindow] that records every call and lets a test play the finger.
 *
 * It holds no clock, timer or file: a test sets the public fields to describe
 * the screen, then reads the recorded lists to see what the controller asked the
 * window to do. [down], [move], [up] and [cancel] forward to the sink the
 * controller registered, and fail loudly when it never registered one.
 *
 * [calls] is one combined log of the calls that change the window, by name and in the order they came:
 * "add", "moveTo", "setFrame", "applyPalette", "applyFace" and "remove". It lets a test pin the order of
 * calls that the separate lists cannot show.
 */
internal class FakeTileWindow(
    var permission: Boolean = true,
    var bounds: PixelBounds = TestData.SCREEN,
    var sizePx: Int = TestData.TILE_PX,
    var addOutcome: AddOutcome = AddOutcome.ADDED,
) : TileWindow {

    /** One recorded call of [add]. */
    class Add(val x: Int, val y: Int, val palette: TruckingPalette)

    /** One recorded call of [setFrame]. */
    data class Frame(val x: Int, val y: Int, val width: Int, val height: Int)

    val adds = ArrayList<Add>()
    val moves = ArrayList<PixelPoint>()
    val frames = ArrayList<Frame>()
    val appliedPalettes = ArrayList<TruckingPalette>()
    val appliedFaces = ArrayList<TileFace>()
    val calls = ArrayList<String>()
    /** Every [setPulse] call, in order: true for a request to pulse and false for a stop. */
    val pulses = mutableListOf<Boolean>()
    /** The order of [setPulse] and [applyFace] calls: "pulse:true", "pulse:false" and "face". */
    val order = mutableListOf<String>()
    var removeCount = 0
    var canDrawCalls = 0
    var boundsReads = 0
    var sink: TouchSink? = null

    /** Press the finger down at a raw point. */
    fun down(x: Float, y: Float) = registeredSink().onTouchDown(x, y)

    /** Move the finger to a raw point. */
    fun move(x: Float, y: Float) = registeredSink().onTouchMove(x, y)

    /** Lift the finger at a raw point. */
    fun up(x: Float, y: Float) = registeredSink().onTouchUp(x, y)

    /** Cancel the gesture, as the system does when it takes the touch away. */
    fun cancel() = registeredSink().onTouchCancel()

    override fun canDrawOverlays(): Boolean {
        canDrawCalls++
        return permission
    }

    override fun usableBounds(): PixelBounds {
        boundsReads++
        return bounds
    }

    override fun tileSizePx(): Int = sizePx

    override fun setTouchSink(sink: TouchSink) {
        this.sink = sink
    }

    override fun add(x: Int, y: Int, palette: TruckingPalette): AddOutcome {
        calls.add("add")
        adds.add(Add(x, y, palette))
        return addOutcome
    }

    override fun moveTo(x: Int, y: Int) {
        calls.add("moveTo")
        moves.add(PixelPoint(x, y))
    }

    override fun setFrame(x: Int, y: Int, width: Int, height: Int) {
        calls.add("setFrame")
        frames.add(Frame(x, y, width, height))
    }

    override fun applyPalette(palette: TruckingPalette) {
        calls.add("applyPalette")
        appliedPalettes.add(palette)
    }

    override fun applyFace(face: TileFace) {
        calls.add("applyFace")
        appliedFaces.add(face)
        order.add("face")
    }

    override fun setPulse(on: Boolean) {
        pulses.add(on)
        order.add(if (on) "pulse:true" else "pulse:false")
    }

    /** Every [setBusyPulse] call, in order: true for a request to pulse and false for a stop. */
    val busyPulseCalls = mutableListOf<Boolean>()
    /** The last [setBusyPulse] call, or null when none has been made. */
    var busyPulseOn: Boolean? = null
    /** How many times [setBusyPulse] has been called. */
    val busyPulseCallCount: Int get() = busyPulseCalls.size

    /** Record a [setBusyPulse] call. */
    override fun setBusyPulse(on: Boolean) {
        busyPulseCalls.add(on)
        busyPulseOn = on
    }

    override fun remove() {
        calls.add("remove")
        removeCount++
    }

    private fun registeredSink(): TouchSink =
        sink ?: throw AssertionError("android_overlay: the controller never registered a touch sink with the window")
}
