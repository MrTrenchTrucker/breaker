package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingPalette

/**
 * A [TileWindow] that records every call and lets a test play the finger.
 *
 * It holds no clock, timer or file: a test sets the public fields to describe
 * the screen, then reads the recorded lists to see what the controller asked the
 * window to do. [down], [move], [up] and [cancel] forward to the sink the
 * controller registered, and fail loudly when it never registered one.
 */
internal class FakeTileWindow(
    var permission: Boolean = true,
    var bounds: PixelBounds = TestData.SCREEN,
    var sizePx: Int = TestData.TILE_PX,
    var addOutcome: AddOutcome = AddOutcome.ADDED,
) : TileWindow {

    /** One recorded call of [add]. */
    class Add(val x: Int, val y: Int, val palette: TruckingPalette)

    val adds = ArrayList<Add>()
    val moves = ArrayList<PixelPoint>()
    val appliedPalettes = ArrayList<TruckingPalette>()
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
        adds.add(Add(x, y, palette))
        return addOutcome
    }

    override fun moveTo(x: Int, y: Int) {
        moves.add(PixelPoint(x, y))
    }

    override fun applyPalette(palette: TruckingPalette) {
        appliedPalettes.add(palette)
    }

    override fun remove() {
        removeCount++
    }

    private fun registeredSink(): TouchSink =
        sink ?: throw AssertionError("android_overlay: the controller never registered a touch sink with the window")
}
