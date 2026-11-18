package dev.breaker.dictation.wiring

import dev.breaker.dictation.updater.InstallResult

/**
 * Carries the answers to an install request from the updater to the About wiring that is listening.
 *
 * Two listener slots are held: [listen] sets the install answer's listener, and [listenFinished] sets the
 * finish listener, which takes the status code of a session that ended. [release] clears both. All of them
 * are called on the main thread. [report] and [reportFinished] may be called from any thread. Each one posts
 * through [main], so the listener takes it on the main thread, and only if a listener is still set at that
 * moment. An answer or a finish with no listener set is dropped.
 */
internal class InstallAnswerRelay(private val main: MainPost) {
    private var listener: ((InstallResult) -> Unit)? = null
    private var finishListener: ((Int) -> Unit)? = null

    /** Sets the one listener; a listener set before is replaced. */
    fun listen(listener: (InstallResult) -> Unit) {
        this.listener = listener
    }

    /** Sets the one finish listener, which takes the installer's status code; a listener set before is replaced. */
    fun listenFinished(listener: (Int) -> Unit) {
        this.finishListener = listener
    }

    /** Clears both listeners, so an answer or a finish that arrives after this is dropped. */
    fun release() {
        listener = null
        finishListener = null
    }

    /** Hands [result] to the listener on the main thread. */
    fun report(result: InstallResult) {
        main.post { listener?.invoke(result) }
    }

    /** Hands the installer's status code for a finished session to the finish listener on the main thread. */
    fun reportFinished(status: Int) {
        main.post { finishListener?.invoke(status) }
    }
}
