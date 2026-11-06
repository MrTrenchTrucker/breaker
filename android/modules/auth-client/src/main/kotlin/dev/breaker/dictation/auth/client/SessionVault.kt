package dev.breaker.dictation.auth.client

/**
 * Keeps one opaque string for the signed-in session. The auth client writes the session
 * record here after a successful sign-in and reads it back at start-up.
 *
 * This module gives no protection at rest by itself. It hands the string to the implementation as it
 * is and reads it back unchanged. The app backs this port with a file sealed under an Android
 * Keystore key, so the stored string is sealed at rest there.
 *
 * [save] may throw when the implementation cannot store the string. [load] returns null when
 * nothing is stored. [clear] removes whatever is stored.
 */
interface SessionVault {
    fun save(record: String)
    fun load(): String?
    fun clear()
}
