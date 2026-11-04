package dev.breaker.server.syncapi.reset

import dev.breaker.server.syncapi.accounts.KdfParams
import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.accounts.StoredKeys
import dev.breaker.server.syncapi.accounts.StoredVerifier

// The client salt is the schema's 16-byte column.
private const val RESET_SALT_BYTES = 16

internal sealed class ResetAccountResult {
    /** The account is reset; [code] is the new reset code, shown once and stored nowhere. */
    class Reset(val code: String) : ResetAccountResult()

    object NoSuchAccount : ResetAccountResult()
}

internal sealed class CompleteResetResult {
    /** The reset is complete and a new session was made; [sessionText] is that session's text. */
    class Completed(val role: Role, val sessionText: String, val keys: StoredKeys) : CompleteResetResult()

    /** Unknown name, wrong, malformed, expired or used code, or an account not waiting. Nothing was written. */
    object Refused : CompleteResetResult()
}

/**
 * Everything completing a reset writes, already checked for size. The constructor refuses a wrong
 * salt size and the message names the field and the size only, never a value. The salt is copied in
 * and copied out, so a caller cannot change what the request holds. The verifier is already hashed
 * (AccountStore.hashVerifier), because the slow hash must run before the transaction opens.
 */
internal class CompleteResetRequest(
    newSalt: ByteArray,
    val newKdf: KdfParams,
    val newKdfVersion: Int,
    val newVerifier: StoredVerifier,
) {
    init {
        require(newSalt.size == RESET_SALT_BYTES) { "new_salt must be $RESET_SALT_BYTES bytes" }
    }

    private val saltBytes: ByteArray = newSalt.copyOf()

    val newSalt: ByteArray
        get() = saltBytes.copyOf()

    // A fixed text, so no value can reach a message by string conversion.
    override fun toString(): String = "CompleteResetRequest"
}
