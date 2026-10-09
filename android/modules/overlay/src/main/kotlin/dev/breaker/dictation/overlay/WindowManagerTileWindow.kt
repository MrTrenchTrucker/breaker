package dev.breaker.dictation.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import dev.breaker.shared.tokens.TruckingPalette
import kotlin.math.roundToInt

/**
 * The real overlay window: one [TileView] added to the system [WindowManager]
 * above other apps.
 *
 * The window type is `TYPE_APPLICATION_OVERLAY`, which needs the
 * `SYSTEM_ALERT_WINDOW` permission declared in this module's manifest and
 * granted by the user. The window is not focusable, so the app underneath
 * keeps focus and the keyboard while the tile is shown, and a touch outside the
 * tile goes to whatever is below it. The code asks for the screen corner as the
 * origin of the window position: the window is laid out in the whole screen
 * (`FLAG_LAYOUT_IN_SCREEN`) with left and top gravity, so its x and y are meant
 * to be counted from the top-left corner. The gravity is `LEFT`, not `START`, so
 * x counts from the left edge whatever the layout direction.
 *
 * The permission answer comes from the system ([canDrawOverlays]). When the
 * system refuses to add the window (no permission, a window token it does not
 * accept, or any other runtime failure of the add) [add] answers
 * [AddOutcome.REFUSED] instead of throwing, and [remove] is safe on a view the
 * system no longer knows. The window changes shape (the square tile, the wide
 * window) through [setFrame], which keeps the same flags.
 *
 * Call it from the UI looper only. Pass the application context: the window outlives any one
 * screen.
 *
 * **What this class cannot prove.** Nothing here runs without a device, so none
 * of this is verified: the window really appearing above other apps, other
 * apps keeping focus and the keyboard, how a drag feels, the system-bar and
 * display-cutout insets (and so whether a real device counts the window
 * position from the screen corner, including with a cutout or system bars, as
 * the code asks), what a rotation does to the layout,
 * density scaling, the permission grant flow and its revocation while the tile
 * is shown, and vendor differences. Only the text of this file is checked on a
 * plain JVM.
 */
internal class WindowManagerTileWindow(private val context: Context) : TileWindow {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var sink: TouchSink? = null
    private var tileView: TileView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /**
     * The screen area the tile may use: the current window bounds minus the
     * system bars and the display cutout. Read fresh on every call, so a
     * rotation is seen at the next move.
     */
    override fun usableBounds(): PixelBounds {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val left = bounds.left + insets.left
        val top = bounds.top + insets.top
        val right = maxOf(left, bounds.right - insets.right)
        val bottom = maxOf(top, bounds.bottom - insets.bottom)
        return PixelBounds(left, top, right, bottom)
    }

    override fun tileSizePx(): Int =
        (TileMetrics.TILE_SIZE_DP * context.resources.displayMetrics.density).roundToInt()

    override fun setTouchSink(sink: TouchSink) {
        this.sink = sink
    }

    /**
     * Add the tile with its top-left corner at ([x], [y]) in pixels.
     *
     * Answers [AddOutcome.REFUSED] when no touch sink was set first, or when
     * the system refuses the window.
     */
    override fun add(x: Int, y: Int, palette: TruckingPalette): AddOutcome {
        val activeSink = sink ?: return AddOutcome.REFUSED
        removeQuietly()

        val size = tileSizePx()
        val face = TileFace(
            state = TileState.IDLE,
            shape = TileShape.COLLAPSED,
            litSegments = 0,
            segments = LedMeter.SEGMENTS,
            look = TileStyle.look(TileState.IDLE, palette),
            notice = null,
            description = null,
        )
        val view = TileView(context, activeSink, face)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = x
        params.y = y

        try {
            windowManager.addView(view, params)
        } catch (e: WindowManager.BadTokenException) {
            return AddOutcome.REFUSED
        } catch (e: SecurityException) {
            return AddOutcome.REFUSED
        } catch (e: RuntimeException) {
            // Any other runtime failure of the add is a refusal too, never a crash.
            return AddOutcome.REFUSED
        }
        tileView = view
        layoutParams = params
        return AddOutcome.ADDED
    }

    /**
     * Move the added tile to ([x], [y]) in pixels; nothing happens when no tile is added.
     *
     * When the system has already removed the view, `updateViewLayout` throws
     * [IllegalArgumentException]; that is swallowed, because the tile is gone and
     * there is nothing left to move. The caller is told nothing, and [remove]
     * stays safe to call afterwards.
     */
    override fun moveTo(x: Int, y: Int) {
        val params = layoutParams ?: return
        setFrame(x, y, params.width, params.height)
    }

    /**
     * Move the added window to ([x], [y]) and give it [width] by [height] pixels; nothing happens
     * when no tile is added. The flags stay as [add] set them. An [IllegalArgumentException] from
     * the system is swallowed for the same reason as in [moveTo].
     */
    override fun setFrame(x: Int, y: Int, width: Int, height: Int) {
        val view = tileView ?: return
        val params = layoutParams ?: return
        params.x = x
        params.y = y
        params.width = width
        params.height = height
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // The system no longer knows this view: it is already gone.
        }
    }

    /** Draw [face] on the added tile; nothing happens when no tile is added. */
    override fun applyFace(face: TileFace) {
        tileView?.applyFace(face)
    }

    /** Turn the armed ring's pulse on or off on the added tile; nothing happens when no tile is added. */
    override fun setPulse(on: Boolean) {
        tileView?.setPulse(on)
    }

    /** Turn the busy ring's pulse on or off on the added tile; nothing happens when no tile is added. */
    override fun setBusyPulse(on: Boolean) {
        tileView?.setBusyPulse(on)
    }

    /** Redraw the added tile in [palette], keeping its state, shape and meter; nothing happens when no tile is added. */
    override fun applyPalette(palette: TruckingPalette) {
        val view = tileView ?: return
        val face = view.face
        view.applyFace(face.copy(look = TileStyle.look(face.state, palette)))
    }

    override fun remove() {
        removeQuietly()
    }

    private fun removeQuietly() {
        val view = tileView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // The system no longer knows this view: it is already gone.
        }
        tileView = null
        layoutParams = null
    }
}
