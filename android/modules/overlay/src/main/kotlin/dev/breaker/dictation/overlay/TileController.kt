package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.TilePosition
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens

/**
 * The state machine of the floating tile: shown or hidden, where it sits, what the app has told it to
 * show, and what a touch means.
 *
 * It holds every rule and no platform class. The window it drives is the [TileWindow] seam, the
 * settings come through the core [SettingsStore] port, the meaning of a raw touch is decided by a
 * [TouchInterpreter], and what the app pushed (state, level, notice, description) is kept by a
 * [TileModel]. Everything runs on the app's main (UI) looper: no locks, no background work, no clock.
 *
 * **Position.** The position of the collapsed tile is kept twice: as pixels and as a fraction of the
 * movable range (what is saved). The usable bounds and the tile size are read from the window every
 * time they matter, never cached, because a rotation changes both. A wider window (a notice, or
 * recording) is placed around the collapsed tile and kept on screen; it never changes the saved position.
 *
 * **Drawing.** After every push from the app, and only while shown, the window is told what changed:
 * a new shape resizes it first, a change that keeps the shape redraws it, and a push that changes
 * nothing the tile shows calls nothing. While hidden, a push is only stored.
 *
 * **Saving.** A position is saved exactly once per drag end, by loading the current settings,
 * replacing only the tile position and saving the result. Only a drag that began on the collapsed tile
 * moves it or saves. A save that fails is swallowed and [onSaveFailed] is told once.
 *
 * **Taps.** A tap is routed by the state and by the part of the window the finger went down on: [onTap]
 * for the microphone while idle or failed, [onBegin] for the microphone while armed, [onSend] for the
 * microphone or the send button while recording, [onCancel] for the cancel button. A null callback does
 * nothing. Each runs once per tap, never on hide, show or a push from the app, and an exception it
 * throws is not caught here.
 *
 * @param window the window seam; this controller registers itself as its touch sink.
 * @param settings where the tile position is read from and saved to.
 * @param onTap what the app does when the microphone is tapped while idle or failed.
 * @param theme the palette mode used by the next [show].
 * @param slopPx how far a finger may wander from where it went down and still count as a tap.
 * @param onSaveFailed called when a drag end could not be saved; null for none.
 * @param onBegin called when the microphone is tapped while armed; null for none.
 * @param onCancel called when the cancel button is tapped while recording; null for none.
 * @param onSend called when the send button or the microphone is tapped while recording; null for none.
 */
internal class TileController(
    private val window: TileWindow,
    private val settings: SettingsStore,
    private val onTap: () -> Unit,
    theme: ThemeMode,
    slopPx: Int,
    private val onSaveFailed: (() -> Unit)? = null,
    private val onBegin: (() -> Unit)? = null,
    private val onCancel: (() -> Unit)? = null,
    private val onSend: (() -> Unit)? = null,
) : TouchSink {

    private val interpreter = TouchInterpreter(slopPx)
    private val gesture = TileGesture()
    private val model = TileModel()
    private var currentTheme: ThemeMode = theme
    private var pixels: PixelPoint = PixelPoint(0, 0)
    private var fraction: TilePosition = CENTRE

    /** The face the window was last told to draw; every show draws again and replaces it. */
    private var applied: TileFace? = null

    /** True from a successful [show] until [hide]. */
    var isShown: Boolean = false
        private set

    /** The state the app last pushed; idle until it pushes another. */
    val state: TileState
        get() = model.state

    init {
        window.setTouchSink(this)
    }

    /**
     * Put the tile on screen at the saved position.
     *
     * The checks run in a fixed order. Already shown answers [ShowResult.ALREADY_SHOWN] with no window
     * call and no read of the settings. A missing overlay permission answers
     * [ShowResult.PERMISSION_MISSING] with no window added and no setting touched. Only then is the
     * start position read, and a refusal by the window answers [ShowResult.FAILED]. In every case other
     * than [ShowResult.SHOWN] the tile stays hidden, so a later call can try again. Once the window is
     * added, the face for the last state the app pushed is drawn, and a wider shape then gets its frame.
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
                val face = model.face(currentTheme)
                applied = face
                window.applyFace(face)
                if (face.shape != TileShape.COLLAPSED) placeWindow(face.shape)
                ShowResult.SHOWN
            }
            AddOutcome.REFUSED -> ShowResult.FAILED
        }
    }

    /**
     * Take the tile off screen. Does nothing when it is already hidden.
     *
     * A drag of the collapsed tile in progress is ended first exactly like a
     * release, so the position the user dragged to is saved before the window
     * goes. Any gesture state is cleared and no callback runs. The window is
     * removed once. The state, level, notice and description the app pushed are
     * kept, and the next [show] draws them.
     */
    fun hide() {
        if (!isShown) return
        endGesture()
        window.remove()
        isShown = false
    }

    /**
     * Remember [mode] for the next [show]; while shown, repaint the tile with
     * it once and draw the face again in the new colours.
     */
    fun setTheme(mode: ThemeMode) {
        currentTheme = mode
        if (!isShown) return
        window.applyPalette(TruckingTokens.palette(mode))
        val face = model.face(mode)
        applied = face
        window.applyFace(face)
    }

    /**
     * The screen size, density or insets changed: place the tile again from the saved fraction on the
     * fresh bounds and size. Ignored while hidden and in the middle of a drag of the collapsed tile; a
     * wider window is placed again around the collapsed tile and kept on the new screen.
     */
    fun onDisplayChanged() {
        if (!isShown) return
        if (interpreter.isDragging && gesture.shape == TileShape.COLLAPSED) return
        val point = TilePlacement.toPixels(fraction, window.usableBounds(), window.tileSizePx())
        pixels = point
        val shape = model.shape
        if (shape == TileShape.COLLAPSED) window.moveTo(point.x, point.y) else placeWindow(shape)
    }

    /** The app pushes [next]. A different state removes the notice; see [TileModel.setState]. */
    fun setState(next: TileState) = push { model.setState(next) }

    /** The app pushes the sound [level], kept from 0.0 to 1.0; only a recording tile shows it. */
    fun setLevel(level: Float) = push { model.setLevel(level) }

    /** The app pushes a sentence to show; see [TileModel.showNotice]. */
    fun showNotice(text: String) = push { model.showNotice(text) }

    /** The app takes the notice away. */
    fun clearNotice() = push { model.clearNotice() }

    /** The app pushes the description of the tile for accessibility; null for none. */
    fun setDescription(text: String?) = push { model.setDescription(text) }

    override fun onTouchDown(x: Float, y: Float) {
        if (!isShown) return
        interpreter.down(x, y)
        gesture.begin(x, y, model.shape)
    }

    override fun onTouchMove(x: Float, y: Float) {
        if (!isShown) return
        val event = interpreter.move(x, y)
        if (event is TouchEvent.DragBy && gesture.shape == TileShape.COLLAPSED) dragBy(event.dx, event.dy)
    }

    override fun onTouchUp(x: Float, y: Float) {
        if (!isShown) return
        when (interpreter.up(x, y)) {
            is TouchEvent.Tap -> route()
            is TouchEvent.DragEnd -> if (gesture.shape == TileShape.COLLAPSED) persist()
            else -> Unit
        }
    }

    override fun onTouchCancel() {
        if (!isShown) return
        endGesture()
    }

    /**
     * Store a push from the app, then tell the window what changed since the last face it drew.
     * Nothing while hidden. When the shape changed, the gesture in progress is ended first and the
     * window gets its new frame before the face; when only the face changed, the face alone; when
     * nothing changed, no call at all.
     */
    private fun push(change: () -> Unit) {
        change()
        if (!isShown) return
        val face = model.face(currentTheme)
        val last = applied
        if (face == last) return
        applied = face
        if (last?.shape != face.shape) {
            endGesture()
            placeWindow(face.shape)
        }
        window.applyFace(face)
    }

    /** Give the window the size of [shape] at its place around the collapsed tile, kept inside the usable bounds. */
    private fun placeWindow(shape: TileShape) {
        val side = window.tileSizePx()
        val origin = TileLayout.windowOrigin(shape, pixels, side, window.usableBounds())
        val size = TileLayout.windowSize(shape, side)
        window.setFrame(origin.x, origin.y, size.width, size.height)
    }

    /** End the gesture in progress like a release; a drag that began on the collapsed tile is saved, no tap is made. */
    private fun endGesture() {
        if (interpreter.cancel() is TouchEvent.DragEnd && gesture.shape == TileShape.COLLAPSED) persist()
    }

    /** Run what a tap means for the state and for the part of the window the finger went down on. */
    private fun route() {
        val shape = gesture.shape
        val side = window.tileSizePx()
        val origin = if (shape == TileShape.COLLAPSED) pixels else TileLayout.windowOrigin(shape, pixels, side, window.usableBounds())
        when (TileRouting.action(model.state, gesture.zone(origin, side))) {
            TileAction.TAP -> onTap()
            TileAction.BEGIN -> onBegin?.invoke()
            TileAction.CANCEL -> onCancel?.invoke()
            TileAction.SEND -> onSend?.invoke()
            TileAction.NONE -> Unit
        }
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
     * Save where the tile ended up: one load and one save, the position replaced and every other
     * setting kept. A failure of either is swallowed and reported once through [onSaveFailed]; only an
     * [Exception] is swallowed, never an [Error].
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
