package dev.breaker.dictation.updater

/** The platform's view of the install sessions that the module recorded. */
internal interface SessionQuery {
    /** True when the platform still holds a session with [sessionId]. */
    fun exists(sessionId: Int): Boolean

    /** True when a commit was made for the session [sessionId]. */
    fun isCommitted(sessionId: Int): Boolean
}

/**
 * Abandons an install session that the platform holds. An IOException or a SecurityException means the session was
 * not abandoned; any other failure of the platform must be reported as an IOException.
 */
internal fun interface SessionAbandon {
    /** Abandons the session [sessionId]. */
    fun abandon(sessionId: Int)
}

/**
 * Receives one call for each dropped final status. It carries only the two session ids: the id that the platform sent
 * and the recorded id, which is null when no session is recorded.
 */
internal fun interface DropLog {
    /** Reports one dropped final status. */
    fun dropped(platformSessionId: Int, recordedSessionId: Int?)
}
