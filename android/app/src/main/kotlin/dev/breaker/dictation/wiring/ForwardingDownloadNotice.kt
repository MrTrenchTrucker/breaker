package dev.breaker.dictation.wiring

/**
 * Forwards download notices to the system notifications AND an optional in-app listener.
 *
 * The in-app listener is set by the activity in onResume and cleared in onPause,
 * so a closed screen is never held. The system notifications always fire.
 */
class ForwardingDownloadNotice(
    private val notifications: ModelDownloadNotice,
) : ModelDownloadNotice {
    private var listener: ModelDownloadNotice? = null

    fun setListener(newListener: ModelDownloadNotice?) {
        listener = newListener
    }

    override fun downloading() {
        notifications.downloading()
        listener?.downloading()
    }

    override fun done() {
        notifications.done()
        listener?.done()
    }

    override fun failed(sentence: String) {
        notifications.failed(sentence)
        listener?.failed(sentence)
    }
}
