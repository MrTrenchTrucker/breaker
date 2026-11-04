package dev.breaker.server.syncapi.reset

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.tokens.TokenSecret
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.TranscriptionPurge
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration

// The admin reset and its completion. A reset is ONE transaction on the database lane: either every
// write happens or none does. It replaces the verifier with random bytes (so no password can match),
// marks the account pending, clears the key columns, ends every token and hand-off code, deletes the
// stored transcriptions, clears the wrong-password row and stores the hash of a new reset code, which
// replaces any older one. Completing a reset spends the code and sets the new verifier in one
// transaction too. Nothing here logs a value, and no message holds one. The clock is the account
// store's clock everywhere, and a code is dead AT its expiry instant.
internal class ResetStore(
    private val accounts: AccountStore,
    private val tokens: TokenStore,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Match(val accountId: Long, val role: Role, val codeHash: ByteArray)

    /**
     * Resets the account with this folded name. The code text is made and hashed before the
     * transaction and returned once; only its hash is stored. An exception anywhere rolls everything
     * back and goes to the caller.
     */
    suspend fun resetAccount(foldedName: String): ResetAccountResult {
        val nowMs = accounts.clock.millis()
        val codeText = TokenSecret.generate(random)
        val codeHash = codeText.hash()
        val fresh = ByteArray(FRESH_VERIFIER_BYTES)
        random.nextBytes(fresh)
        return accounts.db.transaction<ResetAccountResult> { connection ->
            val accountId = readAccountId(connection, foldedName)
            if (accountId == null) {
                ResetAccountResult.NoSuchAccount
            } else {
                overwriteAccount(connection, accountId, fresh, nowMs)
                tokens.revokeAllForAccountOn(connection, accountId)
                TranscriptionPurge.deleteAllOn(connection, accountId)
                accounts.attempts.clearOn(connection, foldedName)
                replaceCode(connection, accountId, codeHash, nowMs)
                ResetAccountResult.Reset(codeText.reveal())
            }
        }
    }

    /** True only when [code] is the live code of the pending account [foldedName]. Writes nothing. */
    suspend fun checkCode(foldedName: String, code: String): Boolean {
        val nowMs = accounts.clock.millis()
        return accounts.db.transaction<Boolean> { connection -> matchOn(connection, foldedName, code, nowMs) != null }
    }

    /**
     * Spends the code and sets the new salt, cost values and verifier in one transaction, clears the
     * wrong-password row and signs the person in. Every refusal is the one [CompleteResetResult.Refused]
     * and writes nothing. A failure after the code is spent rolls the spend back.
     */
    suspend fun complete(foldedName: String, code: String, request: CompleteResetRequest): CompleteResetResult {
        val nowMs = accounts.clock.millis()
        return accounts.db.transaction<CompleteResetResult> { connection ->
            completeOn(connection, foldedName, code, request, nowMs)
        }
    }

    /** Deletes every reset code at or past its expiry and returns how many. The sweep calls this. */
    suspend fun deleteExpired(): Int {
        val nowMs = accounts.clock.millis()
        return accounts.db.transaction<Int> { connection ->
            connection.prepareStatement(DELETE_EXPIRED_SQL).use { statement ->
                statement.setLong(1, nowMs)
                statement.executeUpdate()
            }
        }
    }

    private fun completeOn(
        connection: Connection,
        foldedName: String,
        code: String,
        request: CompleteResetRequest,
        nowMs: Long,
    ): CompleteResetResult {
        // (1) The lookup and compare. No match writes nothing.
        val match = matchOn(connection, foldedName, code, nowMs) ?: return CompleteResetResult.Refused
        // (2) Spend the code. A count other than 1 means another completion got there first.
        if (deleteCode(connection, match) != 1) return CompleteResetResult.Refused
        // (3) The new salt, cost values and verifier, and the pending mark off.
        writeNewCredentials(connection, match.accountId, request)
        // (4) The wrong-password row of this name is cleared.
        accounts.attempts.clearOn(connection, foldedName)
        // (5) A new session, made with the same call as the sign-in exchange.
        val issued = tokens.issueSessionOn(connection, match.accountId, null, nowMs)
        // (6) The stored keys, which are all empty after a reset, for the sign-in answer.
        val keys = checkNotNull(accounts.keysOn(connection, match.accountId)) {
            "sync-api: the account of a reset code could not be read"
        }
        return CompleteResetResult.Completed(match.role, issued.secret.reveal(), keys)
    }

    // One lookup and one compare, for every caller. An unknown name, a malformed code and a missing
    // row all run the same compare against fixed filler values, so they take the same path as a wrong
    // code. The two fillers differ, so a filler compare never matches.
    private fun matchOn(connection: Connection, foldedName: String, code: String, nowMs: Long): Match? {
        val parsed = TokenSecret.parse(code)
        val presented = parsed?.hash() ?: FILLER_HASH
        val row = readLiveCode(connection, foldedName, nowMs)
        val stored = row?.codeHash ?: FILLER_STORED
        val equal = MessageDigest.isEqual(presented, stored)
        return if (row != null && parsed != null && equal) row else null
    }

    private fun readLiveCode(connection: Connection, foldedName: String, nowMs: Long): Match? =
        connection.prepareStatement(SELECT_CODE_SQL).use { statement ->
            statement.setString(1, foldedName)
            statement.setLong(2, nowMs)
            statement.executeQuery().use { rows ->
                if (rows.next()) Match(rows.getLong(1), Role.fromDb(rows.getString(2)), rows.getBytes(3)) else null
            }
        }

    private fun readAccountId(connection: Connection, foldedName: String): Long? =
        connection.prepareStatement(SELECT_ID_SQL).use { statement ->
            statement.setString(1, foldedName)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }

    // One UPDATE. The right side of change_seq reads the old value, so purged_through equals the new
    // change_seq: every old cursor, a caught-up one too, is told to resync. key_version, rekeyed_at and
    // the other columns are not named, so they stay.
    private fun overwriteAccount(connection: Connection, accountId: Long, fresh: ByteArray, nowMs: Long) {
        val changed = connection.prepareStatement(OVERWRITE_ACCOUNT_SQL).use { statement ->
            statement.setBytes(1, fresh)
            statement.setLong(2, nowMs)
            statement.setLong(3, accountId)
            statement.executeUpdate()
        }
        check(changed == 1) { "sync-api: the reset touched $changed account rows, expected 1" }
    }

    // A new code replaces any older one of the account.
    private fun replaceCode(connection: Connection, accountId: Long, codeHash: ByteArray, nowMs: Long) {
        connection.prepareStatement(DELETE_CODE_OF_ACCOUNT_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate()
        }
        connection.prepareStatement(INSERT_CODE_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setBytes(2, codeHash)
            statement.setLong(3, nowMs)
            statement.setLong(4, nowMs + CODE_LIFETIME.toMillis())
            statement.executeUpdate()
        }
    }

    private fun deleteCode(connection: Connection, match: Match): Int =
        connection.prepareStatement(DELETE_CODE_SQL).use { statement ->
            statement.setLong(1, match.accountId)
            statement.setBytes(2, match.codeHash)
            statement.executeUpdate()
        }

    private fun writeNewCredentials(connection: Connection, accountId: Long, request: CompleteResetRequest) {
        val verifier = request.newVerifier
        val updated = connection.prepareStatement(COMPLETE_ACCOUNT_SQL).use { statement ->
            statement.setBytes(1, request.newSalt)
            statement.setInt(2, request.newKdf.memoryKib)
            statement.setInt(3, request.newKdf.iterations)
            statement.setInt(4, request.newKdf.parallelism)
            statement.setInt(5, request.newKdfVersion)
            statement.setString(6, verifier.algo)
            statement.setInt(7, verifier.iterations)
            statement.setBytes(8, verifier.salt)
            statement.setBytes(9, verifier.hash)
            statement.setLong(10, accountId)
            statement.executeUpdate()
        }
        check(updated == 1) { "sync-api: completing a reset touched $updated account rows, expected 1" }
    }

    private companion object {
        const val FRESH_VERIFIER_BYTES = 32
        const val HASH_BYTES = 32

        // The code lives 24 hours from the moment it is made.
        val CODE_LIFETIME: Duration = Duration.ofHours(24)

        // Used when the presented code is malformed or no row exists, so the compare still runs.
        // The two values differ, so a filler compare can never match.
        val FILLER_HASH: ByteArray = ByteArray(HASH_BYTES)
        val FILLER_STORED: ByteArray = ByteArray(HASH_BYTES) { 1 }

        const val SELECT_ID_SQL = "SELECT id FROM accounts WHERE username_lower = ?"

        const val OVERWRITE_ACCOUNT_SQL =
            "UPDATE accounts SET verifier_hash = ?, reset_pending = 1, wrapped_dek = NULL, " +
                "wrapped_box_privkey = NULL, box_pubkey = NULL, prev_wrapped_dek = NULL, reset_at = ?, " +
                "change_seq = change_seq + 1, purged_through = change_seq + 1 WHERE id = ?"

        const val DELETE_CODE_OF_ACCOUNT_SQL = "DELETE FROM reset_codes WHERE account_id = ?"

        const val INSERT_CODE_SQL =
            "INSERT INTO reset_codes (account_id, code_hash, issued_at_ms, expires_at_ms) VALUES (?, ?, ?, ?)"

        // A row at or past its expiry is not live: live means expires_at_ms > now.
        const val SELECT_CODE_SQL =
            "SELECT a.id, a.role, c.code_hash FROM accounts a JOIN reset_codes c ON c.account_id = a.id " +
                "WHERE a.username_lower = ? AND a.reset_pending = 1 AND c.expires_at_ms > ?"

        const val DELETE_CODE_SQL = "DELETE FROM reset_codes WHERE account_id = ? AND code_hash = ?"

        const val COMPLETE_ACCOUNT_SQL =
            "UPDATE accounts SET salt = ?, kdf_memory_kib = ?, kdf_iterations = ?, kdf_parallelism = ?, " +
                "kdf_version = ?, verifier_algo = ?, verifier_iters = ?, verifier_salt = ?, verifier_hash = ?, " +
                "reset_pending = 0 WHERE id = ? AND reset_pending = 1"

        const val DELETE_EXPIRED_SQL = "DELETE FROM reset_codes WHERE expires_at_ms <= ?"
    }
}
