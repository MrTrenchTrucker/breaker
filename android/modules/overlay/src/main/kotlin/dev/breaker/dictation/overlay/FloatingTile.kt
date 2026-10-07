package dev.breaker.dictation.overlay

import android.content.Context
import android.view.ViewConfiguration
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.shared.tokens.ThemeMode

/**
 * The floating tile: a small draggable mic tile that appears over other apps.
 *
 * The tile is tap-only. It never takes focus and has no text input, so the app
 * you were in keeps its keyboard. What a tap means is up to the app, which
 * passes it in as `onTap` when it calls [create]. A drag moves the tile, and
 * where it was dropped is saved in the settings as a fraction of the range the
 * tile can move over (the usable screen area minus the tile size), so it comes
 * back at the same place on any display size.
 *
 * **How to use it**
 * - Call everything from the main (UI) looper. The tile takes no locks and
 *   starts nothing in the background.
 * - Call [setTheme] before [show], and again whenever the theme the tile
 *   shows changes.
 * - Call [onDisplayChanged] on a configuration change (rotation, density, window
 *   size), so the tile is placed again on the new screen.
 * - [show] returns [ShowResult.PERMISSION_MISSING] when the user has not
 *   allowed drawing over other apps. Send the user to the system page for that
 *   permission and call [show] again once they are back. Nothing was added and
 *   no setting was touched, so asking again is safe.
 * - A tile that could not be shown ([ShowResult.FAILED]) stays hidden and
 *   [show] can be tried again.
 * - Keeping the process alive while the tile is up is the app's job: the
 *   foreground service that does it belongs to the app, not to this class.
 *
 * Instances are made with [create].
 */
class FloatingTile internal constructor(private val controller: TileController) {

    /** True from a successful [show] until [hide]. */
    val isShown: Boolean
        get() = controller.isShown

    /**
     * Put the tile on screen at its saved position.
     *
     * Returns [ShowResult.SHOWN] when the tile is now up,
     * [ShowResult.ALREADY_SHOWN] when it already was (nothing changes),
     * [ShowResult.PERMISSION_MISSING] when the user has not allowed drawing
     * over other apps, and [ShowResult.FAILED] when the system refused the
     * window. Only [ShowResult.SHOWN] leaves the tile on screen.
     */
    fun show(): ShowResult = controller.show()

    /**
     * Take the tile off screen. Does nothing when it is not shown. A drag in
     * progress is ended first and its position saved.
     */
    fun hide() = controller.hide()

    /**
     * Set the theme the tile is drawn in. While the tile is shown it is
     * repainted at once; while hidden the choice is kept for the next [show].
     */
    fun setTheme(mode: ThemeMode) = controller.setTheme(mode)

    /**
     * Tell the tile the display changed. While shown and not being dragged, it
     * moves to where its saved fraction falls on the new screen; otherwise
     * nothing happens.
     */
    fun onDisplayChanged() = controller.onDisplayChanged()

    companion object {
        /**
         * Make a tile for the app.
         *
         * @param context any context; the tile keeps only the application context.
         * @param settings where the tile position is read from and saved to.
         * @param onTap what the app does when the tile is tapped; called once per
         *   tap, on the main looper. An exception it throws is not caught.
         * @param theme the theme the tile is first drawn in.
         * @param onSaveFailed called, with no arguments, when a dropped position
         *   could not be saved; the tile stays where it was dropped. Null for none.
         */
        fun create(
            context: Context,
            settings: SettingsStore,
            onTap: () -> Unit,
            theme: ThemeMode,
            onSaveFailed: (() -> Unit)? = null,
        ): FloatingTile {
            val window = WindowManagerTileWindow(context.applicationContext)
            val slopPx = ViewConfiguration.get(context).scaledTouchSlop
            return FloatingTile(TileController(window, settings, onTap, theme, slopPx, onSaveFailed))
        }
    }
}
