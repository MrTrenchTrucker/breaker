package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.SqliteDatabase
import java.sql.Connection
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AccountStore(
    private val db: SqliteDatabase,
    private val hasher: VerifierHasher = VerifierHasher(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private class AccountRow(val id: Long, val role: Role, val stored: StoredVerifier)

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

    suspend fun verify(username: String, verifier: AuthVerifier): VerifyResult {
        if (!UsernameRules.isValid(username)) return VerifyResult.Rejected
        val folded = UsernameRules.fold(username)
        val row = db.transaction<AccountRow?> { connection -> readAccountRow(connection, folded) }
            ?: return VerifyResult.Rejected
        // The transaction has ended: the slow hash must not hold the database lane.
        val matches = withContext(Dispatchers.Default) { hasher.matches(verifier, row.stored) }
        return if (matches) VerifyResult.Verified(row.id, row.role) else VerifyResult.Rejected
    }

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
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id, role FROM accounts WHERE id = last_insert_rowid()").use { rows ->
                check(rows.next()) { "sync-api: the new account row could not be read back" }
                RegisterResult.Registered(rows.getLong(1), Role.fromDb(rows.getString(2)))
            }
        }
    }

    private fun readAccountRow(connection: Connection, folded: String): AccountRow? =
        connection.prepareStatement(SELECT_VERIFIER_SQL).use { statement ->
            statement.setString(1, folded)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    val stored = StoredVerifier(rows.getString(3), rows.getInt(4), rows.getBytes(5), rows.getBytes(6))
                    AccountRow(rows.getLong(1), Role.fromDb(rows.getString(2)), stored)
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

        const val SELECT_VERIFIER_SQL =
            "SELECT id, role, verifier_algo, verifier_iters, verifier_salt, verifier_hash " +
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
