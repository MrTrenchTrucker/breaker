package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingPalette

/**
 * What the system answered when the tile window was added.
 *
 * [ADDED] means the window is on screen. [REFUSED] means the system would not
 * take it (the permission was revoked between the check and the add, or the
 * window type was rejected); nothing is on screen and nothing needs removing.
 */
internal enum class AddOutcome { ADDED, REFUSED }

/**
 * Where the window sends the raw touches it receives.
 *
 * The points are raw screen coordinates in pixels, the same coordinate space
 * as the usable bounds, so the controller can compare a move with the point
 * where the finger went down. The window forwards every event it gets and
 * never decides what a gesture means: that is the controller's job.
 */
internal interface TouchSink {
    /** A finger went down at ([x], [y]). */
    fun onTouchDown(x: Float, y: Float)

    /** The finger moved to ([x], [y]). */
    fun onTouchMove(x: Float, y: Float)

    /** The finger lifted at ([x], [y]). */
    fun onTouchUp(x: Float, y: Float)

    /** The system took the gesture away; no further events follow for it. */
    fun onTouchCancel()
}

/**
 * The one floating window the tile lives in, and nothing else.
 *
 * This is deliberately a small seam. The controller owns every rule (where the
 * tile starts, how a drag is clamped, when a position is saved), and an
 * adapter over the platform window service carries the calls out. The rules
 * are therefore testable on a plain JVM against a recording fake, and the
 * calls that need a device are kept as few and as dumb as possible.
 *
 * **Contract for every implementation:**
 * - All calls arrive on the app's main (UI) looper. An implementation takes no
 *   locks and starts nothing in the background.
 * - [add] and [remove] never throw for a refused window or for a window that
 *   is not attached. A refusal is reported as [AddOutcome.REFUSED]; removing a
 *   window that is already gone is a quiet no-op.
 * - [canDrawOverlays] only asks the system; it changes nothing.
 * - [moveTo], [setFrame], [applyPalette] and [applyFace] are only called while a
 *   window has been added and not yet removed.
 */
internal interface TileWindow {
    /** True when the system currently lets this app draw over other apps. */
    fun canDrawOverlays(): Boolean

    /**
     * The area the tile may occupy right now, in pixels: the screen without
     * the system bars and cutouts. Read again whenever the answer matters,
     * because a rotation changes it.
     */
    fun usableBounds(): PixelBounds

    /** The side of the square tile in pixels at the current density. */
    fun tileSizePx(): Int

    /** Register where the window sends its touches. Called once, at construction. */
    fun setTouchSink(sink: TouchSink)

    /**
     * Put the tile window on screen with its top-left corner at ([x], [y])
     * pixels, drawn with [palette]. Returns [AddOutcome.REFUSED] instead of
     * throwing when the system will not take it.
     */
    fun add(x: Int, y: Int, palette: TruckingPalette): AddOutcome

    /** Move the added window so its top-left corner is at ([x], [y]) pixels. */
    fun moveTo(x: Int, y: Int)

    /**
     * Move and resize the added window: its top-left corner goes to ([x], [y]) pixels and it becomes
     * [width] by [height] pixels. The window keeps the flags it was added with.
     */
    fun setFrame(x: Int, y: Int, width: Int, height: Int)

    /** Redraw the added window with [palette]. */
    fun applyPalette(palette: TruckingPalette)

    /** Redraw the added window to show [face]: the state, the meter, the colours and the notice. */
    fun applyFace(face: TileFace)

    /**
     * Ask the window to pulse its armed ring while [on] is true. The window pulses only while the other
     * conditions of the view hold (attached, visible, screen on, system animations on); false stops it.
     */
    fun setPulse(on: Boolean)

    /** Take the window off screen. Quietly does nothing when it is not there. */
    fun remove()
}
