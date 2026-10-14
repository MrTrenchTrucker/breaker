package dev.breaker.dictation.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import dev.breaker.shared.tokens.TruckingPalette

/**
 * The close target as a separate overlay window: a circle with an X, drawn with palette colours
 * only, shown only while a drag of the collapsed tile is in progress.
 *
 * The window is the same type as the tile window ([TYPE_APPLICATION_OVERLAY]), not focusable and
 * not touchable. It never steals focus from the app underneath and never intercepts a touch.
 *
 * The art is a placeholder: a surface circle with a danger-colour X and a trim ring. No text is
 * drawn. The target is shown when a drag starts and hidden when the drag ends.
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
     * Show the close target at ([x], [y]) pixels with radius [radiusPx], drawn with [palette].
     *
     * The target is a circle of [radiusPx] pixel radius. The window is centred at
     * ([x], [y]) by placing its top-left corner at ([x] - radius, [y] - radius). Returns true
     * when the window was added, false when the system refused it.
     */
    fun show(x: Int, y: Int, radiusPx: Float, palette: TruckingPalette): Boolean {
        if (isShown) return true

        val (left, top, diameterPx) = frameFor(x, y, radiusPx)
        val face = CloseTargetFace.from(palette)

        val view = CloseTargetView(context, face)
        val params = WindowManager.LayoutParams(
            diameterPx,
            diameterPx,
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
         * Compute the top-left corner and diameter of the window for a circle centred at
         * ([x], [y]) with radius [radiusPx]. The frame is computed from the radius and
         * truncated to whole pixels: the diameter is the radius times two, taken as a whole
         * number of pixels, so the drawn circle is not necessarily exactly [radiusPx] in
         * radius. Returns (left, top, diameter) where left = x - diameter / 2 and
         * top = y - diameter / 2.
         */
        internal fun frameFor(x: Int, y: Int, radiusPx: Float): Triple<Int, Int, Int> {
            val diameter = (radiusPx * 2).toInt()
            return Triple(x - diameter / 2, y - diameter / 2, diameter)
        }
    }
}
