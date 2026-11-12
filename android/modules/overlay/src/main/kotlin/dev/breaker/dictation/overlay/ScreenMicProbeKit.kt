package dev.breaker.dictation.overlay

import kotlin.math.floor

/**
 * The pure decision and the probe plumbing of the app-screen mic, moved out of [ScreenMicView]
 * as one sibling so the view stays under the module line cap. Nothing here touches a platform
 * class: the view calls in with its own state and callbacks, and the probe lines carry only
 * ordinals and integers, which the probe's log sink decodes.
 *
 * Probe line formats (ordinals of the module's enums, integers, colon-separated), the touch time [ts] in the
 * same second field of every line (the MotionEvent's own event time, so the module reads no clock):
 * 0:ts:action (a tap routed); 1:ts:shape:state:action:zone:x:y (touch down); 5:ts:shape:state:action:zone:x:y
 * (touch up); 6:ts:shape:state (touch cancel); 7:ts:screenX:screenY:x:y:zone then four rectangles as
 * left:top:right:bottom in view pixels, in the order drawn cancel, drawn send, touch cell cancel, touch cell send
 * (a touch down while recording: which drawn button the finger hit and which touch cell counted it).
 * DIAGNOSTIC kit for the Home mic tap blocker: on the release checklist, removed before a release APK.
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

    /** The touch-down probe line: time, shape, state, action, zone ordinals, then the raw pixel point. */
    fun downLog(ts: Long, shape: TileShape, state: TileState, x: Float, y: Float, s: Int) {
        line("1:$ts:${touchFields(shape, state, x, y, s)}")
    }

    /** The touch-up probe line: the same fields as the touch-down line, with the finger's lift point. */
    fun upLog(ts: Long, shape: TileShape, state: TileState, x: Float, y: Float, s: Int) {
        line("5:$ts:${touchFields(shape, state, x, y, s)}")
    }

    /** The touch-cancel probe line: time, shape and state ordinals. */
    fun cancelLog(ts: Long, shape: TileShape, state: TileState) {
        line("6:$ts:${shape.ordinal}:${state.ordinal}")
    }

    /** One line per touch down while recording: the finger against the drawn X and check and against the touch cells. */
    fun touchMapLog(ts: Long, screen: IntArray, x: Float, y: Float, s: Int) {
        val zone = TileLayout.zoneAt(floor(x).toInt(), floor(y).toInt(), TileShape.RECORDING, s).ordinal
        line("7:$ts:${screen[0]}:${screen[1]}:${floor(x).toInt()}:${floor(y).toInt()}:$zone:${placed(ControlLayout.cancel(s))}:${placed(ControlLayout.send(s))}:${cell(TileLayout.cancelCell(s))}:${cell(TileLayout.sendCell(s))}")
    }

    private fun touchFields(shape: TileShape, state: TileState, x: Float, y: Float, s: Int): String =
        "${shape.ordinal}:${state.ordinal}:${MicTapZone.resolve(state, shape, x, y, s).ordinal}:" +
            "${TileLayout.zoneAt(floor(x).toInt(), floor(y).toInt(), shape, s).ordinal}:${floor(x)}:${floor(y)}"

    private fun placed(p: ControlPlacement): String = "${floor(p.left).toInt()}:${floor(p.top).toInt()}:${floor(p.right).toInt()}:${floor(p.bottom).toInt()}"

    private fun cell(r: TileRect): String = "${r.left}:${r.top}:${r.right}:${r.bottom}"

    /** Send one probe line, or drop it when no sink is set. */
    private fun line(text: String) {
        log?.invoke(text)
    }

    /** Run what a tap means for the state and for the part of the view the finger went down on. */
    fun route(view: ScreenMicView, ts: Long) {
        val side = view.height
        val zone = view.gesture.zone(PixelPoint(0, 0), side)
        when (val action = TileRouting.action(view.model.state, zone)) {
            TileAction.TAP -> {
                line("0:$ts:${action.ordinal}")
                view.onTap()
            }
            TileAction.BEGIN -> {
                line("0:$ts:${action.ordinal}")
                view.onBegin?.invoke()
            }
            TileAction.CANCEL -> {
                line("0:$ts:${action.ordinal}")
                view.onCancel?.invoke()
            }
            TileAction.SEND -> {
                line("0:$ts:${action.ordinal}")
                view.onSend?.invoke()
            }
            else -> Unit
        }
    }
}
