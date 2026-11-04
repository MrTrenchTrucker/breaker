package dev.breaker.server.syncapi.passwordchange

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.KdfParams
import dev.breaker.server.syncapi.accounts.KeySizes
import dev.breaker.server.syncapi.accounts.PrevKeyRule
import dev.breaker.server.syncapi.accounts.StoredVerifier
import dev.breaker.server.syncapi.tokens.TokenStore
import java.sql.Connection

// The client salt is the schema's 16-byte column.
private const val SALT_BYTES = 16

/**
 * Everything a password change writes, already checked for size. The constructor refuses a wrong
 * size and the message names the field and the size only, never a value. Byte values are copied in
 * and copied out, so a caller cannot change what the request holds. The verifier is already hashed
 * (AccountStore.hashVerifier), because the slow hash must run before the transaction opens.
 */
internal class PasswordChangeRequest(
    newSalt: ByteArray,
    val newKdf: KdfParams,
    val newKdfVersion: Int,
    val newVerifier: StoredVerifier,
    newWrappedDek: ByteArray,
    newWrappedBoxPrivkey: ByteArray,
    newBoxPubkey: ByteArray,
    prevWrappedDek: ByteArray,
    val newKeyVersion: Int,
) {
    init {
        require(newSalt.size == SALT_BYTES) { "new_salt must be $SALT_BYTES bytes" }
        require(newWrappedDek.size == KeySizes.WRAPPED_DEK) { "new_wrapped_dek must be ${KeySizes.WRAPPED_DEK} bytes" }
        require(newWrappedBoxPrivkey.size == KeySizes.WRAPPED_BOX_PRIVKEY) {
            "new_wrapped_box_privkey must be ${KeySizes.WRAPPED_BOX_PRIVKEY} bytes"
        }
        require(newBoxPubkey.size == KeySizes.BOX_PUBKEY) { "new_box_pubkey must be ${KeySizes.BOX_PUBKEY} bytes" }
        require(prevWrappedDek.size == KeySizes.WRAPPED_DEK) {
            "prev_wrapped_dek must be ${KeySizes.WRAPPED_DEK} bytes"
        }
    }

    private val saltBytes: ByteArray = newSalt.copyOf()
    private val dekBytes: ByteArray = newWrappedDek.copyOf()
    private val privkeyBytes: ByteArray = newWrappedBoxPrivkey.copyOf()
    private val pubkeyBytes: ByteArray = newBoxPubkey.copyOf()
    private val prevDekBytes: ByteArray = prevWrappedDek.copyOf()

    val newSalt: ByteArray
        get() = saltBytes.copyOf()

    val newWrappedDek: ByteArray
        get() = dekBytes.copyOf()

    val newWrappedBoxPrivkey: ByteArray
        get() = privkeyBytes.copyOf()

    val newBoxPubkey: ByteArray
        get() = pubkeyBytes.copyOf()

    val prevWrappedDek: ByteArray
        get() = prevDekBytes.copyOf()

    // A fixed text, so no key byte can reach a message by string conversion.
    override fun toString(): String = "PasswordChangeRequest"
}

internal sealed class PasswordChangeResult {
    /** The change is committed; [keyVersion] is the account's key version after it. */
    class Changed(val keyVersion: Int) : PasswordChangeResult()

    /** The presented session is revoked, expired or gone, or the account is gone. Nothing was written. */
    object TokenNotLive : PasswordChangeResult()

    /** The account is waiting for a reset to be completed. Nothing was written. */
    object ResetPending : PasswordChangeResult()

    object NoKeys : PasswordChangeResult()

    object KeyVersionMismatch : PasswordChangeResult()

    object PreviousUnfinished : PasswordChangeResult()
}

// The password change in ONE transaction on the database lane: either every write below happens or
// none does. The verifier hash is made before the transaction (AccountStore.hashVerifier), because
// it takes hundreds of milliseconds and would hold the lane for every other request meanwhile. The
// old password was proved before this store is called. Inside, the order is fixed: first the checks,
// which only read (a live session, then the account row, then a pending reset, then no keys, then a
// wrong key version, then an unfinished re-key), and only then the writes: the account row, the previous-key rule, the other sessions with
// the hand-off codes, the wrong-password row. An exception anywhere rolls everything back and goes
// to the caller. Nothing here logs a value, and no message holds one.
internal class PasswordChangeStore(private val accounts: AccountStore, private val tokens: TokenStore) {
    private class AccountState(
        val foldedName: String,
        val keyVersion: Int,
        val keyed: Boolean,
        val prevHeld: Boolean,
        val pending: Boolean,
    )

    suspend fun change(accountId: Long, tokenId: Long, request: PasswordChangeRequest): PasswordChangeResult =
        accounts.db.transaction<PasswordChangeResult> { connection ->
            changeOn(connection, accountId, tokenId, request)
        }

    private fun changeOn(
        connection: Connection,
        accountId: Long,
        tokenId: Long,
        request: PasswordChangeRequest,
    ): PasswordChangeResult {
        // Step 1: the presented session must still be live inside this transaction.
        if (!tokens.isSessionLiveOn(connection, tokenId, accountId)) return PasswordChangeResult.TokenNotLive
        // Step 2: read the account state. No row means the account is gone.
        val state = readState(connection, accountId) ?: return PasswordChangeResult.TokenNotLive
        // Step 3: the four refusals, in this order. They only read. A pending account is refused first,
        // because a reset account is keyless too and the pending refusal tells the person what to do.
        if (state.pending) return PasswordChangeResult.ResetPending
        if (!state.keyed) return PasswordChangeResult.NoKeys
        if (request.newKeyVersion != state.keyVersion + 1) return PasswordChangeResult.KeyVersionMismatch
        if (state.prevHeld) return PasswordChangeResult.PreviousUnfinished
        // Step 4: one UPDATE writes every column as sent.
        writeAccount(connection, accountId, request)
        // Step 5: the previous-key rule, after the write.
        PrevKeyRule.clearIfNoOldRows(connection, accountId)
        // Step 6: every other session ends, this one and the agent tokens stay, hand-off codes go.
        tokens.revokeOtherSessionsOn(connection, accountId, tokenId)
        // Step 7: the wrong-password row of this name is cleared.
        accounts.attempts.clearOn(connection, state.foldedName)
        // Step 8: done.
        return PasswordChangeResult.Changed(request.newKeyVersion)
    }

    private fun readState(connection: Connection, accountId: Long): AccountState? =
        connection.prepareStatement(SELECT_STATE_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    AccountState(rows.getString(1), rows.getInt(2), rows.getInt(3) == 1, rows.getInt(4) == 1, rows.getInt(5) == 1)
                } else {
                    null
                }
            }
        }

    // The values are written as sent: the server never moves the old wrapped_dek into prev_wrapped_dek.
    private fun writeAccount(connection: Connection, accountId: Long, request: PasswordChangeRequest) {
        val verifier = request.newVerifier
        val updated = connection.prepareStatement(UPDATE_ACCOUNT_SQL).use { statement ->
            statement.setBytes(1, request.newSalt)
            statement.setInt(2, request.newKdf.memoryKib)
            statement.setInt(3, request.newKdf.iterations)
            statement.setInt(4, request.newKdf.parallelism)
            statement.setInt(5, request.newKdfVersion)
            statement.setString(6, verifier.algo)
            statement.setInt(7, verifier.iterations)
            statement.setBytes(8, verifier.salt)
            statement.setBytes(9, verifier.hash)
            statement.setBytes(10, request.newWrappedDek)
            statement.setBytes(11, request.newWrappedBoxPrivkey)
            statement.setBytes(12, request.newBoxPubkey)
            statement.setBytes(13, request.prevWrappedDek)
            statement.setInt(14, request.newKeyVersion)
            statement.setLong(15, accounts.clock.millis())
            statement.setLong(16, accountId)
            statement.executeUpdate()
        }
        check(updated == 1) { "sync-api: the password change touched $updated account rows, expected 1" }
    }

    private companion object {
        const val SELECT_STATE_SQL =
            "SELECT username_lower, key_version, wrapped_dek IS NOT NULL, prev_wrapped_dek IS NOT NULL, " +
                "reset_pending FROM accounts WHERE id = ?"

        const val UPDATE_ACCOUNT_SQL =
            "UPDATE accounts SET salt = ?, kdf_memory_kib = ?, kdf_iterations = ?, kdf_parallelism = ?, " +
                "kdf_version = ?, verifier_algo = ?, verifier_iters = ?, verifier_salt = ?, verifier_hash = ?, " +
                "wrapped_dek = ?, wrapped_box_privkey = ?, box_pubkey = ?, prev_wrapped_dek = ?, " +
                "key_version = ?, rekeyed_at = ? WHERE id = ?"
    }
}
