package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.SignInAttempt

/**
 * Makes the one-time sign-in attempt: a fresh secret text, its challenge and a fresh
 * key pair, all held in memory only.
 *
 * Implemented by the crypto module with a secure random source. Each call makes a new
 * attempt; the port keeps no copy of it.
 */
interface SignInStarter {
    /** Make a new attempt. The caller owns it and wipes it when it is no longer needed. */
    fun begin(): SignInAttempt
}
