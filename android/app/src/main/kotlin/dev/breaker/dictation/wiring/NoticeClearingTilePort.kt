package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState

/** How long a notice stays on the tile before the app clears it. */
internal const val NOTICE_CLEAR_MS: Long = 4000L

/**
 * The [TilePort] that clears a notice from the tile [NOTICE_CLEAR_MS] after it is shown.
 *
 * Every member goes to [inner] unchanged. A notice queues one post on [delayed]. A new notice, a state
 * change, an explicit clear, a hide and a show each cancel that post before they act, so a late post
 * never clears a newer notice. A level is a redraw and leaves the post alone. A tap on a notice reports
 * through [onNoticeDismissed]: the post is cancelled, [onDismissed] is called once, and [inner] is not
 * asked to clear, because the tile has already cleared its own line. The calls to [delayed] and to
 * [onDismissed] are guarded, so a throwing one does not escape. Main thread only.
 */
internal class NoticeClearingTilePort(
    private val inner: TilePort,
    private val delayed: DelayedPost,
    private val onDismissed: () -> Unit = {},
) : TilePort {

    private var pending: PostHandle? = null

    override fun show(): TileShow {
        cancelPending()
        return inner.show()
    }

    override fun hide() {
        cancelPending()
        inner.hide()
    }

    override fun setState(state: TileState) {
        cancelPending()
        inner.setState(state)
    }

    override fun level(level: Float) {
        inner.level(level)
    }

    override fun showNotice(text: String) {
        cancelPending()
        inner.showNotice(text)
        schedule()
    }

    override fun clearNotice() {
        cancelPending()
        inner.clearNotice()
    }

    /** The tile reports that the user tapped a notice away: drop the timer and tell the app. */
    fun onNoticeDismissed() {
        cancelPending()
        try {
            onDismissed()
        } catch (e: Exception) {
        }
    }

    private fun schedule() {
        try {
            pending = delayed.postDelayed(NOTICE_CLEAR_MS) { clearAfterDelay() }
        } catch (e: Exception) {
            pending = null
        }
    }

    private fun clearAfterDelay() {
        pending = null
        try {
            inner.clearNotice()
        } catch (e: Exception) {
        }
    }

    private fun cancelPending() {
        val handle: PostHandle = pending ?: return
        pending = null
        try {
            handle.cancel()
        } catch (e: Exception) {
        }
    }
}
