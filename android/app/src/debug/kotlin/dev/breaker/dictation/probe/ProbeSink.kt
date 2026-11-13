package dev.breaker.dictation.probe

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import dev.breaker.dictation.overlay.ScreenMicProbe

/** The one probe ring the page reads; installed on every resume. */
internal val probeRing = ProbeRingBuffer(500)

private val TAG = "BreakerMicProbe"

/** The tee sink: every line goes to the ring and to logcat. */
internal fun probeTee(line: String) {
    probeRing.add(line)
    Log.d(TAG, line)
}

/**
 * Installs the tee sink on the Home mic of [activity], walking its decor view for ScreenMicProbe.
 * The walk is shared with MicProbeReceiver (not copied). Called on every activity resume.
 */
internal fun installProbeSink(activity: Activity) {
    val mic = findScreenMicProbe(activity.window.decorView)
    mic?.probeTouchLog { line -> probeTee(line) }
}

/** Finds the ScreenMicProbe in [root]'s view tree, using the shared walk. */
internal fun findScreenMicProbe(root: View): ScreenMicProbe? {
    var found: ScreenMicProbe? = null
    walk(root) { if (found == null && it is ScreenMicProbe) found = it }
    return found
}

/** Runs [block] over [root]'s view tree, parent before children. Shared by installProbeSink and MicProbeReceiver. */
internal fun walk(root: View, block: (View) -> Unit) {
    block(root)
    val group = root as? ViewGroup ?: return
    for (index in 0 until group.childCount) {
        walk(group.getChildAt(index), block)
    }
}
