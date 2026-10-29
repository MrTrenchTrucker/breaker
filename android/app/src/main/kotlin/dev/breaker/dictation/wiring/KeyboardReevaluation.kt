package dev.breaker.dictation.wiring

/** A keyboard reading kept for the window it arrived in: its flags and the time that window ends. */
private class HeldReading(val ime: Boolean, val focus: Boolean, val dueMs: Long)

/**
 * Keeps the newest keyboard reading that arrives inside the debounce window, and re-evaluates it once, when
 * the window ends, through [DelayedPost]. At most one post is pending. [cancel] drops the held reading and its
 * post; [flush] runs the held re-evaluation at once, for a reading that arrives after its window. Main thread
 * only. Plain Kotlin: it names no Android type and reads no clock.
 */
internal class KeyboardReevaluation(
    private val delayed: DelayedPost,
    private val evaluate: (Boolean, Boolean, Long) -> Unit,
) {
    private var held: HeldReading? = null
    private var post: PostHandle? = null

    /** Holds this reading in place of the one held before it, and posts the one re-evaluation when none is pending. */
    fun hold(ime: Boolean, focus: Boolean, timeMs: Long, dueMs: Long) {
        held = HeldReading(ime, focus, dueMs)
        if (post != null) return
        post = try {
            delayed.postDelayed((dueMs - timeMs).coerceAtLeast(0L)) { fire() }
        } catch (e: Exception) {
            null
        }
    }

    /** Drops the held reading and its post, so nothing fires for it. */
    fun cancel() {
        held = null
        dropPost()
    }

    /** Runs the held re-evaluation now, before a reading that arrives after its window is looked at. */
    fun flush() {
        if (held == null) return
        dropPost()
        fire()
    }

    private fun dropPost() {
        val handle: PostHandle? = post
        post = null
        guarded { handle?.cancel() }
    }

    private fun fire() {
        post = null
        val due: HeldReading = held ?: return
        held = null
        evaluate(due.ime, due.focus, due.dueMs)
    }
}

/** The delayed post of a coordinator built without one; the tile host always passes its own. Its posts never run. */
internal object NoDelayedPost : DelayedPost {
    override fun postDelayed(delayMs: Long, block: () -> Unit): PostHandle = object : PostHandle {
        override fun cancel() {}
    }
}
