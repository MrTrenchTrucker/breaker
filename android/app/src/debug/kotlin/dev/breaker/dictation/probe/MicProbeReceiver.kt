package dev.breaker.dictation.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import dev.breaker.dictation.host.MainLooperPost
import dev.breaker.dictation.ui.HomeControl
import dev.breaker.dictation.overlay.ScreenMicProbe
import dev.breaker.dictation.overlay.TileState

/**
 * The debug mic probe receiver: an exported broadcast entry point, action [ACTION], extras `state`,
 * `redraw` and `shape`. On the main thread it walks the last resumed activity's view tree for the Home
 * mic ([ScreenMicProbe]) and its redraw handler ([HomeControl]), pushes a state through the probe or
 * triggers a redraw, and logs one line per event. A `shape` extra selects the halo shape before the
 * post, so the switch works with no activity resumed. It never starts a take: no onTap/onBegin is
 * called by this receiver.
 */
class MicProbeReceiver : BroadcastReceiver() {

    companion object {
        /** The broadcast action that drives this probe. */
        const val ACTION = "dev.breaker.debug.MIC"

        /** Extra: the TileState to push (RECORDING, IDLE, MIC_BUSY, SENDING or any name). */
        const val EXTRA_STATE = "state"

        /** Extra: a redraw request — `showLine` or `refresh`. */
        const val EXTRA_REDRAW = "redraw"

        /** Extra: the halo shape to select (V1, V2 or V3); an unknown or missing word selects nothing. */
        const val EXTRA_SHAPE = "shape"

        /** The log tag for the debug mic probe. */
        private val TAG = "BreakerMicProbe"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION) return

        val stateName = intent.getStringExtra(EXTRA_STATE)
        val redraw = intent.getStringExtra(EXTRA_REDRAW)
        val shapeName = intent.getStringExtra(EXTRA_SHAPE)

        val shape = glowShapeFromWord(shapeName)
        if (shape != null) {
            applyGlowShape(shape)
            Log.i(TAG, "probe shape=${shape.name}")
        }

        MainLooperPost().post {
            // (a) Take the last resumed activity, or report that none is running.
            val activity = MicProbeState.lastResumed() ?: run {
                Log.e(TAG, "no resumed activity")
                return@post
            }

            var mic: ScreenMicProbe? = null
            var control: HomeControl? = null
            // (b) Walk its window decor view tree for the single Home mic and its redraw handler.
            walk(activity.window.decorView) { view ->
                if (mic == null && view is ScreenMicProbe) mic = view as ScreenMicProbe
                else if (control == null && view is HomeControl) control = view as HomeControl
            }

            // (c) If the home mic was not found, report and stop.
            if (mic == null) {
                Log.e(TAG, "no home mic found in activity ${activity.javaClass.name}")
                return@post
            }

            // Route the mic's touch-path lines to the probe's log, so the down/route/onCancel/onSend lines reach logcat.
            mic?.probeTouchLog { line -> Log.d(TAG, line) }

            // (d) Push the requested state through the probe's own state path when a state is given.
            if (stateName != null) mic?.probeSetState(TileState.valueOf(stateName))

            // (e) Trigger a redraw through the handler, either showLine(null) or refresh().
            when (redraw) {
                "showLine" -> control?.showLine(null)
                "refresh" -> control?.refresh()
            }

            // (f) Report after the main post is made.
            Log.i(TAG, "probe state=$stateName redraw=$redraw")
        }
    }

    /**
     * Runs [block] over the view tree rooted at [root], calling it on every view before its descendants, so the
     * first ScreenMicProbe and the first HomeControl in depth order are found. A view's parent is visited before
     * each of its children.
     */
    private fun walk(root: View, block: (View) -> Unit) {
        block(root)
        val group = root as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            walk(group.getChildAt(index), block)
        }
    }
}
