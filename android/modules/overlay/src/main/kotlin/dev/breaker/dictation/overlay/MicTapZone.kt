package dev.breaker.dictation.overlay

import kotlin.math.floor

/**
 * The pure decision behind a tap on the app-screen mic: which [TileAction] a touch means.
 *
 * This is an object of two helpers that turn the mic's state and the finger's raw position into a
 * tile action with no platform types, no clock and no stored gesture. The host click (the accessibility
 * service) decides this way; the touch path still routes through [TileGesture]. Kept `internal`: the only
 * public effect of it is that its file carries the debug-probe interface [ScreenMicProbe].
 */
internal object MicTapZone {

    /**
     * The action a tap at the raw screen point ([x], [y]) means for a tile in [state] with window [shape] on
     * a square of side [s] pixels. A collapsed window is only the microphone, so any point resolves to it; a
     * wider window moves the point into window pixels and takes it down to the whole pixel it lies in.
     */
    fun resolve(state: TileState, shape: TileShape, x: Float, y: Float, s: Int): TileAction {
        if (shape == TileShape.COLLAPSED) return TileRouting.action(state, TileZone.MIC)
        val zone = TileLayout.zoneAt(floor(x).toInt(), floor(y).toInt(), shape, s)
        return TileRouting.action(state, zone)
    }

    /**
     * The action a host click (the accessibility service) means while the tile is in [state]: always the
     * microphone's zone. The host does not know where on the view it was clicked, so it decides by state alone;
     * that decision is [TileAction.SEND] while recording and nothing else when sending.
     */
    fun hostClick(state: TileState): TileAction = TileRouting.action(state, TileZone.MIC)
}
