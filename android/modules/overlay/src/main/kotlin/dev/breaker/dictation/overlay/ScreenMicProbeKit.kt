package dev.breaker.dictation.overlay

import kotlin.math.floor

/**
 * The pure decision and the probe plumbing of the app-screen mic, moved out of [ScreenMicView]
 * as one sibling so the view stays under the module line cap. Nothing here touches a platform
 * class: the view calls in with its own state and callbacks, and the probe lines carry only
 * ordinals and integers, which the probe's log sink decodes.
 *
 * Probe line formats (ordinals of the module's enums, colon-separated):
 * a touch-down line starts with 1 and holds shape, state, action, zone, then the pixel point;
 * a route line starts with 0 and holds the action alone.
 */
internal object ScreenMicProbeKit {

    /** The probe's log sink, or null while no probe is attached. */
    private var log: ((String) -> Unit)? = null

    /** Store the probe's log sink: every touch-down and route line goes to it while it is set; passing null clears the sink and stops the probe lines (further lines are dropped, never crash). */
    fun logSink(log: ((String) -> Unit)?) {
        this.log = log
    }

    /** The host decision for a click: state alone, no stored gesture. */
    fun hostClick(state: TileState): TileAction = MicTapZone.hostClick(state)

    /** The host click's decision and its callback, run from state alone. */
    fun hostAction(view: ScreenMicView) {
        dispatch(hostClick(view.model.state), view.onSend, view.onCancel, view.onBegin) { view.onTap() }
    }

    /** Run what [action] means, with the view's callbacks: no platform view in sight. */
    internal fun dispatch(
        action: TileAction,
        onSend: (() -> Unit)?,
        onCancel: (() -> Unit)?,
        onBegin: (() -> Unit)?,
        onTap: () -> Unit,
    ) {
        when (action) {
            TileAction.SEND -> onSend?.invoke()
            TileAction.CANCEL -> onCancel?.invoke()
            TileAction.BEGIN -> onBegin?.invoke()
            TileAction.TAP -> onTap()
            else -> Unit
        }
    }

    /** The touch-down probe line: shape, state, action, zone ordinals, then the raw pixel point. */
    fun downLog(shape: TileShape, state: TileState, x: Float, y: Float, s: Int) {
        line("1:${shape.ordinal}:${state.ordinal}:${MicTapZone.resolve(state, shape, x, y, s).ordinal}:${TileLayout.zoneAt(floor(x).toInt(), floor(y).toInt(), shape, s).ordinal}:${floor(x)}:${floor(y)}")
    }

    /** Send one probe line, or drop it when no sink is set. */
    private fun line(text: String) {
        log?.invoke(text)
    }

    /** Run what a tap means for the state and for the part of the view the finger went down on. */
    fun route(view: ScreenMicView) {
        val side = view.height
        val zone = view.gesture.zone(PixelPoint(0, 0), side)
        when (val action = TileRouting.action(view.model.state, zone)) {
            TileAction.TAP -> {
                line("0:${action.ordinal}")
                view.onTap()
            }
            TileAction.BEGIN -> {
                line("0:${action.ordinal}")
                view.onBegin?.invoke()
            }
            TileAction.CANCEL -> {
                line("0:${action.ordinal}")
                view.onCancel?.invoke()
            }
            TileAction.SEND -> {
                line("0:${action.ordinal}")
                view.onSend?.invoke()
            }
            else -> Unit
        }
    }
}
