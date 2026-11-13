package dev.breaker.dictation.overlay

/**
 * The mic keeps its touch for the whole gesture. The Home page holds the mic in a scroll container; a long
 * press that drifts a little is read by that container as a scroll, which takes the touch away from the mic
 * (a cancel) and the hold is lost. While a finger is down the mic asks every parent to leave the touch alone,
 * and gives the touch back when the finger is up or the touch is cancelled.
 *
 * [request] is the one call the view makes on its parent (requestDisallowInterceptTouchEvent): true asks the
 * parents not to intercept, false lets them again. A release without a press asks for nothing, so a stray
 * cancel or a second lift never tells a parent anything. Pure: no platform class, no clock, no state but the one flag.
 */
internal class TouchHold(private val request: (Boolean) -> Unit) {

    private var held = false

    /** The finger came down on the mic: the parents are asked to leave the touch alone. */
    fun down() {
        held = true
        request(true)
    }

    /** The finger came up, or the touch was cancelled: the parents may intercept again, once. */
    fun release() {
        if (!held) return
        held = false
        request(false)
    }
}
