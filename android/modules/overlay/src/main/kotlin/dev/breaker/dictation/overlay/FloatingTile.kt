package dev.breaker.dictation.overlay

import android.content.Context
import android.view.ViewConfiguration
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.shared.tokens.ThemeMode

/**
 * The floating tile: a small draggable mic tile that appears over other apps.
 *
 * The tile never takes focus and has no text input, so the app you were in keeps its keyboard. It only
 * shows what the app tells it and reports taps; it never records and never inserts text. The app pushes
 * one of five states ([TileState]), the sound level while recording, and a sentence to show; what a tap
 * means is up to the app, which passes its callbacks in when it calls [create]. A drag of the small tile
 * moves it, and where it was dropped is saved in the settings as a fraction of the range the tile can
 * move over (the usable screen area minus the tile size), so it comes back at the same place on any
 * display size.
 *
 * **What a tap does**
 * - Idle or failed: a tap on the microphone calls `onTap`.
 * - Armed: a tap on the microphone calls `onBegin`.
 * - Recording: the tile widens to a cancel button, the level meter above the microphone, and a send
 *   button. A tap on the send button or on the microphone calls `onSend`; a tap on the cancel button
 *   calls `onCancel`. The widened tile cannot be dragged.
 * - Sending: taps do nothing.
 * - A callback that is null does nothing. A tap never changes the state: the app does.
 *
 * **How to use it**
 * - Call everything from the main (UI) looper. The tile takes no locks and starts nothing in the
 *   background.
 * - Call [setTheme] before [show], and again whenever the theme the tile shows changes.
 * - Call [onDisplayChanged] on a configuration change (rotation, density, window size), so the tile is
 *   placed again on the new screen.
 * - [show] returns [ShowResult.PERMISSION_MISSING] when the user has not allowed drawing over other
 *   apps. Send the user to the system page for that permission and call [show] again once they are
 *   back. Nothing was added and no setting was touched, so asking again is safe.
 * - A tile that could not be shown ([ShowResult.FAILED]) stays hidden and [show] can be tried again.
 * - [hide] while the tile is widened or recording removes it without calling any callback; the next
 *   [show] draws the last state, level and sentence that were pushed.
 * - Keeping the process alive while the tile is up is the app's job: the foreground service that does
 *   it belongs to the app, not to this class.
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

    /** The state the app last pushed with [setState]; [TileState.IDLE] until then. */
    val state: TileState
        get() = controller.state

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

    /**
     * Show [state]. The tile never changes it by itself, not even on a tap; only the app does. A
     * different state removes any sentence the tile is showing, and entering or leaving
     * [TileState.RECORDING] sets the meter back to empty, so a level pushed before the state becomes
     * [TileState.RECORDING] is dropped when recording starts: push the state first, then the levels.
     * Pushing the state the tile already has changes nothing. While the tile is hidden the state is
     * kept and drawn by the next [show].
     */
    fun setState(state: TileState) = controller.setState(state)

    /**
     * Show the sound level from 0.0 (silence) to 1.0 (full) on the meter. A value outside that range
     * is held to it, and a value that is not a number counts as 0. The meter is lit only while the
     * state is [TileState.RECORDING], and a level pushed before the state becomes
     * [TileState.RECORDING] is dropped when recording starts. The tile redraws only when the number
     * of lit segments changes. Nothing is smoothed: the app sends the level it wants shown.
     */
    fun setLevel(level: Float) = controller.setLevel(level)

    /**
     * Show a sentence from the app above the microphone, for example why a tap did nothing. Line
     * breaks and tabs become spaces, the text is trimmed, and it is cut to 80 characters; a blank text
     * shows nothing. It is ignored while the state is [TileState.RECORDING]. The sentence stays until
     * the app calls [clearNotice] or pushes a different state: the tile has no timer. The words are
     * the app's; the tile holds none of its own.
     */
    fun showNotice(text: String) = controller.showNotice(text)

    /** Take away the sentence shown by [showNotice]. Does nothing when there is none. */
    fun clearNotice() = controller.clearNotice()

    /**
     * Set the words that describe the tile to a screen reader, for example what state it is in. The
     * words are the app's. Null removes the description.
     */
    fun setDescription(text: String?) = controller.setDescription(text)

    companion object {
        /**
         * Make a tile for the app.
         *
         * @param context any context; the tile keeps only the application context.
         * @param settings where the tile position is read from and saved to.
         * @param onTap what the app does when the microphone is tapped while the tile is idle or
         *   failed; called once per tap, on the main looper. An exception it throws is not caught.
         * @param theme the theme the tile is first drawn in.
         * @param onSaveFailed called, with no arguments, when a dropped position
         *   could not be saved; the tile stays where it was dropped. Null for none.
         * @param onBegin called when the microphone is tapped while the tile is armed. Null for none.
         * @param onCancel called when the cancel button is tapped while recording. Null for none.
         * @param onSend called when the send button or the microphone is tapped while recording.
         *   Null for none.
         */
        fun create(
            context: Context,
            settings: SettingsStore,
            onTap: () -> Unit,
            theme: ThemeMode,
            onSaveFailed: (() -> Unit)? = null,
            onBegin: (() -> Unit)? = null,
            onCancel: (() -> Unit)? = null,
            onSend: (() -> Unit)? = null,
        ): FloatingTile {
            val window = WindowManagerTileWindow(context.applicationContext)
            val slopPx = ViewConfiguration.get(context).scaledTouchSlop
            val controller = TileController(window, settings, onTap, theme, slopPx, onSaveFailed, onBegin, onCancel, onSend)
            return FloatingTile(controller)
        }
    }
}
