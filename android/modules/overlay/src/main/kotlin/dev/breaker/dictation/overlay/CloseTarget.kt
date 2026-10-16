package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingPalette
import dev.breaker.shared.tokens.TruckingTokens

/**
 * The close target: a see-through dark box shown while a drag of the collapsed tile is in progress.
 *
 * The target is centred horizontally and sits near the bottom of the usable area. Its size is set by
 * [CloseTargetShape]: a window of [CloseTargetShape.windowWidthPx] by [CloseTargetShape.windowHeightPx]
 * pixels. The drop area is the drawn box, and the corners that its arcs cut off are outside it. The
 * target is a separate overlay window of the same type as the tile itself; it is not focusable and
 * not touchable. It is drawn from the dark palette in both themes.
 *
 * This object holds the geometry rules only: where the target sits, whether a tile centre is inside
 * it, and the highlight state. It has no Android types, no state and no window.
 */
internal object CloseTarget {

    /**
     * The centre of the target as fractions of the usable bounds.
     *
     * The target is centred horizontally (0.5) and sits near the bottom (0.87). The fractions are of
     * the usable bounds, not of the movable range.
     */
    fun targetCentreFractions(): Pair<Float, Float> = 0.5f to 0.87f

    /**
     * The centre of the target in pixels for the given [bounds].
     *
     * Returns the pixel coordinates of the target centre, computed from [targetCentreFractions]
     * and the bounds. This is the single source of truth for where the target sits in the
     * usable bounds.
     */
    fun targetCentreInPixels(bounds: PixelBounds): Pair<Int, Int> {
        val (xFraction, yFraction) = targetCentreFractions()
        return (bounds.left + xFraction * bounds.width).toInt() to
               (bounds.top + yFraction * bounds.height).toInt()
    }

    /**
     * The art scale of the drawn box for the given [bounds].
     *
     * This is the scale [contains] uses for the hit test, so the drawn box and the drop area are
     * always the same size.
     */
    fun boxScale(bounds: PixelBounds): Float =
        CloseTargetShape.scale(CloseTargetShape.windowWidthPx(bounds), CloseTargetShape.windowHeightPx(bounds))

    /**
     * Whether the tile centre at ([tileCenterX], [tileCenterY]) pixels is inside the target.
     *
     * The tile centre is the centre of the tile in pixels, relative to the usable bounds. The
     * target centre is computed from [targetCentreFractions] and the bounds. The test is the hit
     * test of the drawn box, see [CloseTargetShape.contains]: the four corners that the arcs cut
     * off are outside.
     *
     * A tile centre exactly on the edge of the box is inside.
     */
    fun contains(tileCenterX: Int, tileCenterY: Int, bounds: PixelBounds): Boolean {
        val (targetCenterX, targetCenterY) = targetCentreInPixels(bounds)
        return CloseTargetShape.contains(
            (tileCenterX - targetCenterX).toFloat(),
            (tileCenterY - targetCenterY).toFloat(),
            boxScale(bounds),
        )
    }

    /**
     * The highlight state for the tile centre at ([tileCenterX], [tileCenterY]) pixels.
     *
     * [CloseHighlight.INSIDE] when the tile centre is inside the target, [CloseHighlight.OUTSIDE]
     * otherwise.
     */
    fun highlight(tileCenterX: Int, tileCenterY: Int, bounds: PixelBounds): CloseHighlight =
        if (contains(tileCenterX, tileCenterY, bounds)) CloseHighlight.INSIDE else CloseHighlight.OUTSIDE

    /**
     * Show the close target at the drop zone.
     *
     * The target is shown once, at the target centre computed from the usable bounds, with the window
     * size of [CloseTargetShape]. The theme parameter is not used: the box is drawn from the dark
     * palette in both modes. The highlight is set later by [TileController.dragBy] after the tile moves.
     */
    internal fun onDragStep(window: TileWindow, theme: ThemeMode) {
        val bounds = window.usableBounds()
        val (cx, cy) = targetCentreInPixels(bounds)
        window.showCloseTarget(
            cx,
            cy,
            CloseTargetShape.windowWidthPx(bounds),
            CloseTargetShape.windowHeightPx(bounds),
            TruckingTokens.DARK,
        )
    }

    /**
     * Decide a drop of the collapsed tile: close when the centre is on the target, save otherwise.
     *
     * @param tileX the tile x position in pixels (top-left)
     * @param tileY the tile y position in pixels (top-left)
     * @param window the window seam (for bounds, tile size, and hide calls)
     * @param collapsed whether the tile is collapsed (the only shape that may be dragged to the target)
     * @param onCloseRequested called when the centre is on the target; null for none
     * @param onHide called to hide the tile (sets isShown = false)
     * @param onSave called to save the position when the centre is not on the target
     */
    internal fun onDragEnd(
        window: TileWindow,
        collapsed: Boolean,
        tileX: Int,
        tileY: Int,
        onCloseRequested: (() -> Unit)?,
        onHide: () -> Unit,
        onSave: () -> Unit,
    ) {
        if (!collapsed) {
            window.hideCloseTarget()
            return
        }
        val cx = tileX + window.tileSizePx() / 2
        val cy = tileY + window.tileSizePx() / 2
        if (contains(cx, cy, window.usableBounds())) {
            onCloseRequested?.invoke()
            window.setBusyPulse(false)
            window.setPulse(false)
            window.remove()
            onHide()
        } else {
            onSave()
            window.hideCloseTarget()
        }
    }

    /**
     * Cancel a drag: hide the close target.
     */
    internal fun onDragCancel(window: TileWindow) {
        window.hideCloseTarget()
    }

    /**
     * Move the highlight as the tile moves.
     *
     * @param window the window seam
     * @param tileX the tile x position in pixels (top-left)
     * @param tileY the tile y position in pixels (top-left)
     */
    internal fun onDragMove(window: TileWindow, tileX: Int, tileY: Int) {
        val cx = tileX + window.tileSizePx() / 2
        val cy = tileY + window.tileSizePx() / 2
        window.setCloseTargetHighlight(highlight(cx, cy, window.usableBounds()))
    }
}

/** Whether the tile centre is inside the close target. */
internal enum class CloseHighlight { INSIDE, OUTSIDE }
