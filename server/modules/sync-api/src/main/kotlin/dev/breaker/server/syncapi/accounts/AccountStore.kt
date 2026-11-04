package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.SqliteDatabase
import dev.breaker.server.syncapi.settings.ServerSettingsStore
import java.sql.Connection
import java.time.Clock
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AccountStore(
    internal val db: SqliteDatabase,
    private val hasher: VerifierHasher = VerifierHasher(),
    internal val clock: Clock = Clock.systemUTC(),
    internal val settings: ServerSettingsStore = ServerSettingsStore(db),
) {
    internal val attempts = SigninAttemptStore(db, clock)

    // The not-found timing decoy: one fixed verifier, built once at startup with the hasher's
    // own iteration count. The not-found path runs one [hasher.matches] against it before it
    // answers, so a missing account costs the same as a wrong verifier. It is never a real
    // account: the row read comes first, and only a null row reaches the decoy.
    private val timingDecoy: StoredVerifier =
        StoredVerifier(VerifierHasher.ALGO, hasher.iterations, ByteArray(VerifierHasher.SALT_BYTES), ByteArray(VerifierHasher.HASH_BYTES))

    private class AccountRow(val id: Long, val role: Role, val stored: StoredVerifier, val pending: Boolean)

    suspend fun register(
        username: String,
        salt: ByteArray,
        kdf: KdfParams,
        kdfVersion: Int,
        verifier: AuthVerifier,
    ): RegisterResult {
        if (!UsernameRules.isValid(username)) return RegisterResult.InvalidField("username")
        if (salt.size != CLIENT_SALT_BYTES) return RegisterResult.InvalidField("salt")
        if (kdfVersion != KdfBounds.REQUIRED_KDF_VERSION) return RegisterResult.InvalidField("kdf_version")
        if (!kdf.withinBounds()) return RegisterResult.KdfOutOfBounds

        // The hash takes hundreds of milliseconds. Running it before the transaction
        // keeps the single database lane free for other requests meanwhile. A name
        // that turns out to be taken costs one wasted hash; that is the price.
        val stored = withContext(Dispatchers.Default) { hasher.hashNew(verifier) }
        val saltCopy = salt.copyOf()
        val createdAtMs = clock.millis()
        return db.transaction<RegisterResult> { connection ->
            insertAccount(connection, username, saltCopy, kdf, kdfVersion, stored, createdAtMs)
        }
    }

    // No decoy for an unknown name: the answer for a missing account is a plain
    // NoSuchAccount, by design, and the caller decides what the outside world sees.
    suspend fun saltFor(username: String): SaltLookup {
        if (!UsernameRules.isValid(username)) return SaltLookup.NoSuchAccount
        val folded = UsernameRules.fold(username)
        return db.transaction<SaltLookup> { connection ->
            connection.prepareStatement(SELECT_PARAMS_SQL).use { statement ->
                statement.setString(1, folded)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        val kdf = KdfParams(rows.getInt(2), rows.getInt(3), rows.getInt(4))
                        SaltLookup.Found(rows.getBytes(1), kdf, rows.getInt(5))
                    } else {
                        SaltLookup.NoSuchAccount
                    }
                }
            }
        }
    }

    // Stores the first key set of an account. The UPDATE matches only a keyless row, so a second call
    // changes nothing, and key_version is only ever raised by one. An account that is waiting for a reset
    // is refused before the UPDATE. A SQL error is not caught here: the transaction rolls back and the
    // exception goes to the caller.
    suspend fun storeKeys(accountId: Long, keys: KeyBundle): StoreKeysResult {
        val dek = keys.wrappedDek
        val privkey = keys.wrappedBoxPrivkey
        val pubkey = keys.boxPubkey
        return db.transaction<StoreKeysResult> { connection ->
            if (pendingOn(connection, accountId) == true) return@transaction StoreKeysResult.ResetPending
            val changed = connection.prepareStatement(STORE_KEYS_SQL).use { statement ->
                statement.setBytes(1, dek)
                statement.setBytes(2, privkey)
                statement.setBytes(3, pubkey)
                statement.setLong(4, accountId)
                statement.executeUpdate()
            }
            if (changed == 1) {
                connection.prepareStatement("SELECT key_version FROM accounts WHERE id = ?").use { statement ->
                    statement.setLong(1, accountId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "sync-api: the key version could not be read back" }
                        StoreKeysResult.Stored(rows.getInt(1))
                    }
                }
            } else {
                val exists = connection.prepareStatement("SELECT 1 FROM accounts WHERE id = ?").use { statement ->
                    statement.setLong(1, accountId)
                    statement.executeQuery().use { rows -> rows.next() }
                }
                if (exists) StoreKeysResult.AlreadyKeyed else StoreKeysResult.NoSuchAccount
            }
        }
    }

    // The stored key columns of one account, or null when no account has this id.
    suspend fun keysFor(accountId: Long): StoredKeys? =
        db.transaction<StoredKeys?> { connection -> keysOn(connection, accountId) }

    // The same read on the caller's connection, so a caller can run it inside its own transaction.
    internal fun keysOn(connection: Connection, accountId: Long): StoredKeys? =
        connection.prepareStatement(SELECT_KEYS_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    val keyVersion = rows.getInt(4)
                    val rekeyedAt = rows.getLong(6)
                    val rekeyedAtMs: Long? = if (rows.wasNull()) null else rekeyedAt
                    val resetAt = rows.getLong(7)
                    val resetAtMs: Long? = if (rows.wasNull()) null else resetAt
                    StoredKeys(
                        rows.getBytes(1),
                        rows.getBytes(2),
                        rows.getBytes(3),
                        keyVersion,
                        rows.getBytes(5),
                        rekeyedAtMs,
                        resetAtMs,
                    )
                } else {
                    null
                }
            }
        }

    // Whether the account is waiting for a reset to be completed, or null when no account has this id.
    suspend fun resetPendingOf(accountId: Long): Boolean? =
        db.transaction<Boolean?> { connection -> pendingOn(connection, accountId) }

    private fun pendingOn(connection: Connection, accountId: Long): Boolean? =
        connection.prepareStatement(SELECT_PENDING_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getInt(1) == 1 else null
            }
        }

    // The folded name of one account, or null when no account has this id. The password change uses it
    // to find the wrong-password row it clears.
    suspend fun foldedNameOf(accountId: Long): String? =
        db.transaction<String?> { connection ->
            connection.prepareStatement(SELECT_FOLDED_NAME_SQL).use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
        }

    // The same hashing as register, off the database lane, so a caller can hash before it opens its
    // own transaction.
    suspend fun hashVerifier(verifier: AuthVerifier): StoredVerifier =
        withContext(Dispatchers.Default) { hasher.hashNew(verifier) }

    // Runs a counting write inside try/catch: CancellationException is rethrown, any other exception
    // is swallowed so a failed counting write never changes a login answer (fail open).
    private suspend fun guardCounting(block: suspend () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
        }
    }

    suspend fun verify(username: String, verifier: AuthVerifier): VerifyResult {
        if (!UsernameRules.isValid(username)) return VerifyResult.Rejected
        val folded = UsernameRules.fold(username)

        // The admin switch is read live on every call: after the malformed-username
        // return and before the lock check. A failed read counts as ON (the default).
        val limitOn = try {
            settings.wrongPwLimitEnabled()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            true
        }
        if (!limitOn) {
            // Switch OFF: no lock read, no count, no clear. Read the row, hash,
            // return Rejected or Verified exactly as an account with no limit.
            val row = db.transaction<AccountRow?> { connection -> readAccountRow(connection, folded) }
            if (row == null) {
                // Not-found costs one full hash, the same as a wrong verifier: the decoy runs
                // outside the (now ended) transaction, on the default lane.
                withContext(Dispatchers.Default) { hasher.matches(verifier, timingDecoy) }
                return VerifyResult.Rejected
            }
            if (row.pending) return VerifyResult.Rejected
            val matches = withContext(Dispatchers.Default) { hasher.matches(verifier, row.stored) }
            if (!matches) return VerifyResult.Rejected
            return VerifyResult.Verified(row.id, row.role)
        }

        // Switch ON: the lock, count and clear path, unchanged.
        val until = try {
            attempts.lockedUntil(folded)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            0L
        }
        if (until > 0L) {
            return VerifyResult.Locked(SigninAttemptStore.retryAfterSeconds(until, clock.millis()))
        }

        val row = db.transaction<AccountRow?> { connection -> readAccountRow(connection, folded) }
        if (row == null) {
            // Same decoy as the switch-off path: not-found costs one full hash.
            withContext(Dispatchers.Default) { hasher.matches(verifier, timingDecoy) }
            guardCounting { attempts.recordWrong(folded) }
            return VerifyResult.Rejected
        }
        // A pending account never counts a wrong try and never runs the hash.
        if (row.pending) return VerifyResult.Rejected
        // The transaction has ended: the slow hash must not hold the database lane.
        val matches = withContext(Dispatchers.Default) { hasher.matches(verifier, row.stored) }
        if (!matches) {
            guardCounting { attempts.recordWrong(folded) }
            return VerifyResult.Rejected
        }
        guardCounting { attempts.clear(folded) }
        return VerifyResult.Verified(row.id, row.role)
    }

    /** Delegates to the attempt store: the locked_until_ms for [folded], else 0L. */
    suspend fun lockedUntil(folded: String): Long = attempts.lockedUntil(folded)

    private fun insertAccount(
        connection: Connection,
        username: String,
        salt: ByteArray,
        kdf: KdfParams,
        kdfVersion: Int,
        stored: StoredVerifier,
        createdAtMs: Long,
    ): RegisterResult {
        val folded = UsernameRules.fold(username)
        val taken = connection.prepareStatement("SELECT 1 FROM accounts WHERE username_lower = ?").use { statement ->
            statement.setString(1, folded)
            statement.executeQuery().use { rows -> rows.next() }
        }
        if (taken) return RegisterResult.UsernameTaken

        connection.prepareStatement(INSERT_SQL).use { statement ->
            statement.setString(1, username)
            statement.setString(2, folded)
            statement.setBytes(3, salt)
            statement.setInt(4, kdf.memoryKib)
            statement.setInt(5, kdf.iterations)
            statement.setInt(6, kdf.parallelism)
            statement.setInt(7, kdfVersion)
            statement.setString(8, stored.algo)
            statement.setInt(9, stored.iterations)
            statement.setBytes(10, stored.salt)
            statement.setBytes(11, stored.hash)
            statement.setLong(12, createdAtMs)
            statement.executeUpdate()
        }
        // A row left over for this name (wrong tries made before the account existed) is dropped
        // in the same transaction, so a new account never starts locked.
        connection.prepareStatement(DROP_ATTEMPTS_SQL).use { statement ->
            statement.setString(1, folded)
            statement.executeUpdate()
        }
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, role FROM accounts WHERE id = last_insert_rowid()").use { rows ->
                check(rows.next()) { "sync-api: the new account row could not be read back" }
                RegisterResult.Registered(rows.getLong(1), Role.fromDb(rows.getString(2)))
            }
        }
    }

    /**
     * Deletes the wrong-password row of [username]. The admin reset and the password change call
     * it when they are built. A malformed name touches nothing. A database error is not swallowed,
     * so a reset never pretends it worked. It touches no token: a signed-in session is not affected.
     */
    suspend fun clearAttempts(username: String) {
        if (!UsernameRules.isValid(username)) return
        attempts.clear(UsernameRules.fold(username))
    }

    private fun readAccountRow(connection: Connection, folded: String): AccountRow? =
        connection.prepareStatement(SELECT_VERIFIER_SQL).use { statement ->
            statement.setString(1, folded)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    val stored = StoredVerifier(rows.getString(3), rows.getInt(4), rows.getBytes(5), rows.getBytes(6))
                    AccountRow(rows.getLong(1), Role.fromDb(rows.getString(2)), stored, rows.getInt(7) == 1)
                } else {
                    null
                }
            }
        }

    private companion object {
        // The client salt is the schema's 16-byte column, unrelated to the hasher's own salt.
        const val CLIENT_SALT_BYTES = 16

        const val SELECT_PARAMS_SQL =
            "SELECT salt, kdf_memory_kib, kdf_iterations, kdf_parallelism, kdf_version " +
                "FROM accounts WHERE username_lower = ?"

        const val STORE_KEYS_SQL =
            "UPDATE accounts SET wrapped_dek = ?, wrapped_box_privkey = ?, box_pubkey = ?, " +
                "key_version = key_version + 1 WHERE id = ? AND wrapped_dek IS NULL"

        const val SELECT_KEYS_SQL =
            "SELECT wrapped_dek, wrapped_box_privkey, box_pubkey, key_version, prev_wrapped_dek, " +
                "rekeyed_at, reset_at FROM accounts WHERE id = ?"

        const val SELECT_PENDING_SQL = "SELECT reset_pending FROM accounts WHERE id = ?"

        const val SELECT_FOLDED_NAME_SQL = "SELECT username_lower FROM accounts WHERE id = ?"

        const val DROP_ATTEMPTS_SQL = "DELETE FROM signin_attempts WHERE username_lower = ?"

        const val SELECT_VERIFIER_SQL =
            "SELECT id, role, verifier_algo, verifier_iters, verifier_salt, verifier_hash, reset_pending " +
                "FROM accounts WHERE username_lower = ?"

        // The role is decided inside this one statement: the first account ever
        // stored becomes the admin. A count read in a separate step could be stale
        // by the time the row is written; here the count and the insert are one
        // atomic unit under the write lock the enclosing transaction already holds.
        const val INSERT_SQL =
            "INSERT INTO accounts (username, username_lower, role, salt, kdf_memory_kib, kdf_iterations, " +
                "kdf_parallelism, kdf_version, verifier_algo, verifier_iters, verifier_salt, verifier_hash, " +
                "created_at_ms) VALUES (?, ?, " +
                "CASE WHEN (SELECT COUNT(*) FROM accounts) = 0 THEN 'admin' ELSE 'user' END, " +
                "?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    }
}
