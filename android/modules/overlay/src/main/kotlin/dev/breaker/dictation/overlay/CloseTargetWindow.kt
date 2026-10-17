package dev.breaker.dictation.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import dev.breaker.shared.tokens.TruckingPalette

/** The top-left corner and the size of the close target window, in pixels. */
internal data class CloseFrame(val left: Int, val top: Int, val widthPx: Int, val heightPx: Int)

/**
 * The close target as a separate overlay window: a see-through rounded box with the truck and its
 * strapped load, drawn with palette colours only, shown only while a drag of the collapsed tile is in
 * progress.
 *
 * The window is the same type as the tile window ([TYPE_APPLICATION_OVERLAY]), not focusable and
 * not touchable. It never steals focus from the app underneath and never intercepts a touch.
 *
 * No text is drawn. The target is shown when a drag starts and hidden when the drag ends.
 */
internal class CloseTargetWindow(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var layoutParams: WindowManager.LayoutParams? = null
    private var targetView: CloseTargetView? = null

    /** True when the target window is on screen. */
    val isShown: Boolean
        get() = targetView != null

    /**
     * Show the close target box centred at ([x], [y]) pixels, [widthPx] by [heightPx] pixels, drawn with
     * [palette]. The window is placed by [frameFor]. Returns true when the window was added, false when
     * the system refused it.
     */
    fun show(x: Int, y: Int, widthPx: Int, heightPx: Int, palette: TruckingPalette): Boolean {
        if (isShown) return true

        val (left, top, width, height) = frameFor(x, y, widthPx, heightPx)
        val face = CloseTargetFace.from(palette)

        val view = CloseTargetView(context, face)
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = left
        params.y = top

        try {
            windowManager.addView(view, params)
        } catch (e: SecurityException) {
            return false
        } catch (e: RuntimeException) {
            return false
        }

        targetView = view
        layoutParams = params
        return true
    }

    /** Hide the close target. Quietly does nothing when it is not there. */
    fun hide() {
        val view = targetView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // The system no longer knows this view: it is already gone.
        }
        targetView = null
        layoutParams = null
    }

    /** Update the highlight state of the target. */
    fun setHighlight(highlight: CloseHighlight) {
        targetView?.setHighlight(highlight)
    }

    /** True when the system currently lets this app draw over other apps. */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    companion object {
        /**
         * The top-left corner and the size of the window for a box centred at ([x], [y]) pixels, of
         * [widthPx] by [heightPx] pixels. The left edge is x - widthPx / 2 and the top edge is
         * y - heightPx / 2, so an odd size rounds toward the top-left. The size is returned as given.
         */
        internal fun frameFor(x: Int, y: Int, widthPx: Int, heightPx: Int): CloseFrame =
            CloseFrame(x - widthPx / 2, y - heightPx / 2, widthPx, heightPx)
    }
}
