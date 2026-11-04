package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.SignInAttempt

/**
 * Takes the website's return: the code it sent back and the attempt that code belongs to.
 *
 * The sign-in finish implements it later. Until then the app uses [DefaultHandoffReceiver].
 */
fun interface HandoffReceiver {
    /**
     * Finish the sign-in with [code] and [attempt]. The receiver owns the wipe of
     * [attempt] from this call on. The code is not a secret by itself, but it is never
     * logged.
     */
    fun onHandoff(code: String, attempt: SignInAttempt): HandoffReceipt
}

/** What the receiver made of the hand over: the sign-in was taken, or it was not finished. */
enum class HandoffReceipt { TAKEN, NOT_FINISHED }

/**
 * The receiver the app uses until the real one is wired in. It wipes the attempt, does
 * nothing else, and reports that the sign-in is not finished.
 */
object DefaultHandoffReceiver : HandoffReceiver {
    override fun onHandoff(code: String, attempt: SignInAttempt): HandoffReceipt {
        attempt.wipe()
        return HandoffReceipt.NOT_FINISHED
    }
}
