package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.SessionVault
import dev.breaker.dictation.core.port.CredentialVault

/**
 * The one session vault the app hands to the auth service and to the hand-off finish. It keeps the
 * session where [sessions] keeps it and adds one duty: clearing the session also wipes the stored
 * account keys. A password login only saves, so it does not remove keys a hand-off stored; only
 * [clear] (a logout, or a failed store) does. [save] and [load] never touch the keys.
 *
 * [clear] wipes the keys first, then clears the session, each in its own try, so one failure never
 * stops the other. When either threw, the first failure is rethrown with the second added to it as
 * suppressed, as the session vault contract lets clear throw.
 */
internal class KeysClearingSessionVault(
    private val sessions: SessionVault,
    private val credentials: CredentialVault,
) : SessionVault {

    override fun save(record: String) {
        sessions.save(record)
    }

    override fun load(): String? = sessions.load()

    override fun clear() {
        var first: Exception? = null
        try {
            credentials.wipeAll()
        } catch (keysFailure: Exception) {
            first = keysFailure
        }
        try {
            sessions.clear()
        } catch (sessionFailure: Exception) {
            val earlier: Exception? = first
            if (earlier == null) {
                first = sessionFailure
            } else {
                earlier.addSuppressed(sessionFailure)
            }
        }
        val failure: Exception? = first
        if (failure != null) throw failure
    }
}
