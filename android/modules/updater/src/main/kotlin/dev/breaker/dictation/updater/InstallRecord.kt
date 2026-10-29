package dev.breaker.dictation.updater

/**
 * The install record: the one install session that is in flight, kept in the ledger file. A failed write throws
 * IOException, and each call returns only after its write is complete. A caller that does not catch the IOException
 * from [sessionOpened] must not commit that session.
 */
internal interface InstallRecord {
    /** Records [sessionId] as the open session, with the time it was opened taken from the injected clock. */
    fun sessionOpened(sessionId: Int)

    /** The id of the recorded session, or null when no session is recorded or the ledger file cannot be read. */
    fun recordedSessionId(): Int?

    /** Removes the record and keeps the times, the last seen code and the last outcome. Writes nothing when no record exists. */
    fun clearRecord()
}
