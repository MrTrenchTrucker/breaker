package dev.breaker.server.syncapi.handoff

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking

// Shared fixtures for the sign-in hand-off tests. The challenge is computed here with the JDK digest
// and never with the token secret type, so a mistake in that type cannot hide itself from the tests.
internal object HandoffFixtures {
    val START: Instant = TokenFixtures.START

    fun handoffStore(
        temp: TempDatabase,
        tokenStore: TokenStore,
        clock: Clock,
        random: SecureRandom = SecureRandom(),
    ): HandoffStore = HandoffStore(temp.db, tokenStore, clock, random)

    // The challenge is the SHA-256 of the secret text, written as 43 URL-safe characters without padding.
    val CHALLENGE_FOR_SECRET: (String) -> String = { secret ->
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(java.nio.charset.StandardCharsets.US_ASCII))
        Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    // 43 characters of the token alphabet: six "Secret-" pieces (42 chars) and one more letter.
    const val SECRET: String = "Secret-" + "Secret-" + "Secret-" + "Secret-" + "Secret-" + "Secret-" + "S"

    val SEALED_PACKAGE_BYTES: ByteArray = ByteArray(113) { index -> (index * 3 + 1).toByte() }

    val SEALED_PACKAGE_TEXT: String = Base64.getEncoder().encodeToString(SEALED_PACKAGE_BYTES)

    fun rowCount(temp: TempDatabase): Long = runBlocking {
        temp.db.transaction { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM signin_handoff").single()
        }
    }

    fun rowCountFor(temp: TempDatabase, accountId: Long): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT count(*) FROM signin_handoff WHERE account_id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test helper: count returned no row" }
                    rows.getLong(1)
                }
            }
        }
    }

    /** The stored code hashes, oldest first (equal times keep the order the rows were written in). */
    fun storedCodeHashes(temp: TempDatabase): List<ByteArray> = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "SELECT code_hash FROM signin_handoff ORDER BY created_at_ms ASC, rowid ASC",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    val hashes = ArrayList<ByteArray>()
                    while (rows.next()) {
                        hashes.add(rows.getBytes(1))
                    }
                    hashes
                }
            }
        }
    }

    // A row written by direct SQL, for a state the store itself never writes (an expired row, equal
    // times). The challenge and the package are the fixed valid ones.
    fun insertRawRow(temp: TempDatabase, codeHash: ByteArray, accountId: Long, createdAtMs: Long, expiresAtMs: Long) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO signin_handoff (code_hash, account_id, challenge, package, created_at_ms, expires_at_ms) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setBytes(1, codeHash)
                    statement.setLong(2, accountId)
                    statement.setString(3, CHALLENGE_FOR_SECRET(SECRET))
                    statement.setBytes(4, SEALED_PACKAGE_BYTES)
                    statement.setLong(5, createdAtMs)
                    statement.setLong(6, expiresAtMs)
                    statement.executeUpdate()
                }
            }
        }
    }

    /** The device name of the token row, or null when the column is NULL. */
    fun tokenDeviceName(temp: TempDatabase, tokenId: Long): String? = runBlocking {
        temp.db.transaction<String?> { connection ->
            connection.prepareStatement("SELECT device_name FROM tokens WHERE id = ?").use { statement ->
                statement.setLong(1, tokenId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test helper: no token row with id $tokenId" }
                    rows.getString(1)
                }
            }
        }
    }
}
