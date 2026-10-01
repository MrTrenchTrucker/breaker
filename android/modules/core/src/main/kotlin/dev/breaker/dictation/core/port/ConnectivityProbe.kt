package dev.breaker.dictation.core.port

/**
 * Answers one question: is the Local Server there right now?
 *
 * Implemented by the transport module, which owns the short timeout and the
 * cache so a dictation never waits on a probe. The domain asks; it does not
 * know how the answer is found.
 */
interface ConnectivityProbe {
    /**
     * True when the Local Server answered. Implementations cache the answer for
     * a short window and can be asked to re-probe on demand.
     */
    fun isServerReachable(): Boolean
}
