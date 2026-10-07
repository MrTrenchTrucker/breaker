package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.TilePosition
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens

/**
 * The state machine of the floating tile: shown or hidden, where it sits, and
 * what a touch means.
 *
 * It holds every rule and no platform class. The window it drives is the
 * [TileWindow] seam, the settings come through the core [SettingsStore] port,
 * and the meaning of a raw touch is decided by a [TouchInterpreter]. Placement
 * arithmetic is [TilePlacement]. Everything runs on the app's main (UI)
 * looper, so there are no locks, no background work and no clock here.
 *
 * **Position.** The controller keeps the current position twice: as pixels
 * (where the window is) and as a fraction of the movable range (what is saved).
 * The usable bounds and the tile size are read from the window every time they
 * matter, never cached, because a rotation changes both.
 *
 * **Saving.** A position is saved exactly once per drag end, by loading the
 * current settings, replacing only the tile position and saving the result, so
 * every other setting is kept. Nothing is saved while the finger moves, on a
 * tap, or on show. A save that fails is swallowed: the tile stays where it is
 * and [onSaveFailed] is told once, with no arguments.
 *
 * **Taps.** [onTap] runs once per tap. An exception thrown by [onTap] is not
 * caught here; it reaches whoever delivered the touch.
 *
 * @param window the window seam; this controller registers itself as its touch sink.
 * @param settings where the tile position is read from and saved to.
 * @param onTap what the app does when the tile is tapped.
 * @param theme the palette mode used by the next [show].
 * @param slopPx how far a finger may wander from where it went down and still
 *   count as a tap.
 * @param onSaveFailed called when a drag end could not be saved; null for none.
 */
internal class TileController(
    private val window: TileWindow,
    private val settings: SettingsStore,
    private val onTap: () -> Unit,
    theme: ThemeMode,
    slopPx: Int,
    private val onSaveFailed: (() -> Unit)? = null,
) : TouchSink {

    private val interpreter = TouchInterpreter(slopPx)
    private var currentTheme: ThemeMode = theme
    private var pixels: PixelPoint = PixelPoint(0, 0)
    private var fraction: TilePosition = CENTRE

    /** True from a successful [show] until [hide]. */
    var isShown: Boolean = false
        private set

    init {
        window.setTouchSink(this)
    }

    /**
     * Put the tile on screen at the saved position.
     *
     * The checks run in a fixed order. Already shown answers
     * [ShowResult.ALREADY_SHOWN] without any window call and without reading
     * the settings. A missing overlay permission answers
     * [ShowResult.PERMISSION_MISSING] without adding a window and without
     * touching the settings. Only then is the start position read, and a
     * refusal by the window answers [ShowResult.FAILED]. In every case other
     * than [ShowResult.SHOWN] the tile stays hidden, so a later call can try
     * again, for example after the user has granted the permission.
     */
    fun show(): ShowResult {
        if (isShown) return ShowResult.ALREADY_SHOWN
        if (!window.canDrawOverlays()) return ShowResult.PERMISSION_MISSING

        val start = loadStartPosition()
        val point = TilePlacement.toPixels(start, window.usableBounds(), window.tileSizePx())
        return when (window.add(point.x, point.y, TruckingTokens.palette(currentTheme))) {
            AddOutcome.ADDED -> {
                pixels = point
                fraction = start
                isShown = true
                ShowResult.SHOWN
            }
            AddOutcome.REFUSED -> ShowResult.FAILED
        }
    }

    /**
     * Take the tile off screen. Does nothing when it is already hidden.
     *
     * A drag in progress is ended first exactly like a release, so the position
     * the user dragged to is saved before the window goes. Any gesture state is
     * cleared. The window is removed once.
     */
    fun hide() {
        if (!isShown) return
        if (interpreter.cancel() is TouchEvent.DragEnd) persist()
        window.remove()
        isShown = false
    }

    /**
     * Remember [mode] for the next [show]; while shown, repaint the tile with
     * it once.
     */
    fun setTheme(mode: ThemeMode) {
        currentTheme = mode
        if (isShown) window.applyPalette(TruckingTokens.palette(mode))
    }

    /**
     * The screen size, density or insets changed: place the tile again from the
     * current fraction on the fresh bounds and size.
     *
     * Ignored while hidden, and ignored in the middle of a drag, where the
     * finger decides the position.
     */
    fun onDisplayChanged() {
        if (!isShown || interpreter.isDragging) return
        val point = TilePlacement.toPixels(fraction, window.usableBounds(), window.tileSizePx())
        window.moveTo(point.x, point.y)
        pixels = point
    }

    override fun onTouchDown(x: Float, y: Float) {
        if (!isShown) return
        interpreter.down(x, y)
    }

    override fun onTouchMove(x: Float, y: Float) {
        if (!isShown) return
        val event = interpreter.move(x, y)
        if (event is TouchEvent.DragBy) dragBy(event.dx, event.dy)
    }

    override fun onTouchUp(x: Float, y: Float) {
        if (!isShown) return
        when (interpreter.up(x, y)) {
            is TouchEvent.Tap -> onTap()
            is TouchEvent.DragEnd -> persist()
            else -> Unit
        }
    }

    override fun onTouchCancel() {
        if (!isShown) return
        if (interpreter.cancel() is TouchEvent.DragEnd) persist()
    }

    /** The position saved in the settings, or the centre when they cannot be read. */
    private fun loadStartPosition(): TilePosition =
        try {
            settings.load().tilePosition
        } catch (e: Exception) {
            CENTRE
        }

    /** Move the tile by ([dx], [dy]) pixels, kept whole inside the fresh usable bounds. */
    private fun dragBy(dx: Int, dy: Int) {
        val target = TilePlacement.clamp(
            pixels.x + dx,
            pixels.y + dy,
            window.usableBounds(),
            window.tileSizePx(),
        )
        window.moveTo(target.x, target.y)
        pixels = target
    }

    /**
     * Save where the tile ended up: one load and one save, the position
     * replaced and every other setting kept. A failure of either is swallowed
     * and reported once through [onSaveFailed]; the tile stays where it is.
     * Only an [Exception] is swallowed, never an [Error].
     */
    private fun persist() {
        val position = TilePlacement.toPosition(
            pixels.x,
            pixels.y,
            window.usableBounds(),
            window.tileSizePx(),
        )
        fraction = position
        try {
            settings.save(settings.load().copy(tilePosition = position))
        } catch (e: Exception) {
            onSaveFailed?.invoke()
        }
    }

    private companion object {
        /** Where the tile starts when no position can be read: the middle of the screen. */
        val CENTRE = TilePosition(0.5f, 0.5f)
    }
}
