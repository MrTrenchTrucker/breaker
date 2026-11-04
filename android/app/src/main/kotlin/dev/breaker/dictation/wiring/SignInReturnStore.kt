package dev.breaker.dictation.wiring

import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the latest return state between the return activity, which records it, and the start
 * screen, which takes it when it comes back to the front. It holds one state at a time, in
 * memory only. One atomic slot keeps record and take safe across threads without a lock.
 */
class SignInReturnStore {
    private val latest = AtomicReference<SignInReturnState?>(null)

    /** Keep [state] as the latest one, replacing any state that was not taken yet. */
    fun record(state: SignInReturnState) {
        latest.set(state)
    }

    /** Return the held state and clear it, or null when none is held. */
    fun take(): SignInReturnState? = latest.getAndSet(null)
}
