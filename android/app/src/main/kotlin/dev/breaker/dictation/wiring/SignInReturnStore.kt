package dev.breaker.dictation.wiring

import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the latest return state between the return activity, which records it, and the start
 * screen, which takes it when it comes back to the front. It holds one state at a time, in
 * memory only. One atomic slot keeps record and take safe across threads without a lock.
 *
 * One more atomic slot holds the start screen's listener. The listener runs on the thread that
 * records, which may be a background thread, so it must only hand the work to the main thread.
 */
class SignInReturnStore {
    private val latest = AtomicReference<SignInReturnState?>(null)
    private val listener = AtomicReference<(() -> Unit)?>(null)

    /**
     * Keep [state] as the latest one, replacing any state that was not taken yet, and then tell the
     * listener, if one is set. The state is readable before the listener runs. A listener that
     * throws never undoes the record and never reaches the caller.
     */
    fun record(state: SignInReturnState) {
        latest.set(state)
        try {
            listener.get()?.invoke()
        } catch (ignored: Exception) {
            return
        }
    }

    /** Return the held state and clear it, or null when none is held. */
    fun take(): SignInReturnState? = latest.getAndSet(null)

    /** Set the one listener that [record] calls after it keeps a state, or clear it with null. */
    fun listen(onRecorded: (() -> Unit)?) {
        listener.set(onRecorded)
    }
}
