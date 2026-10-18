package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.KdfBounds
import dev.breaker.server.syncapi.accounts.RegisterResult
import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TestDatabases
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking

/** A clock the test moves by hand. Single-coroutine use only: the field is not synchronised. */
internal class MutableClock(start: Instant) : Clock() {
    var current: Instant = start

    fun advance(by: Duration) {
        current = current.plus(by)
    }

    fun set(instant: Instant) {
        current = instant
    }

    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

internal object TokenFixtures {
    val START: Instant = Instant.ofEpochMilli(AccountFixtures.FIXED_MILLIS)

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun tokenStore(temp: TempDatabase, clock: Clock, random: SecureRandom = SecureRandom()): TokenStore =
        TokenStore(temp.db, clock, random)

    fun register(temp: TempDatabase, username: String, seed: Int): RegisterResult.Registered = runBlocking {
        val result = AccountFixtures.newStore(temp).register(
            username,
            AccountFixtures.salt(seed),
            AccountFixtures.validKdf,
            KdfBounds.REQUIRED_KDF_VERSION,
            AccountFixtures.verifier(seed),
        )
        AccountFixtures.registered(result)
    }

    fun issuedOf(result: IssueResult): IssuedToken {
        if (result !is IssueResult.Issued) {
            throw AssertionError("sync-api: expected Issued but got $result")
        }
        return result.token
    }

    // The schema has no role administration yet, so a test changes the role by raw SQL.
    fun setRole(temp: TempDatabase, accountId: Long, role: Role) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET role = ? WHERE id = ?").use { statement ->
                    statement.setString(1, role.dbValue)
                    statement.setLong(2, accountId)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun tokenRowCount(temp: TempDatabase): Long = runBlocking {
        temp.db.transaction { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM tokens").single()
        }
    }

    fun liveTokenCount(temp: TempDatabase, accountId: Long): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM tokens WHERE account_id = ? AND revoked_at_ms IS NULL",
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test helper: count returned no row" }
                    rows.getLong(1)
                }
            }
        }
    }

    /** The revocation time of the row, or null when the column is NULL. */
    fun revokedAtMs(temp: TempDatabase, tokenId: Long): Long? = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT revoked_at_ms FROM tokens WHERE id = ?").use { statement ->
                statement.setLong(1, tokenId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test helper: no token row with id $tokenId" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }
    }

    fun storedHash(temp: TempDatabase, tokenId: Long): ByteArray = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT token_hash FROM tokens WHERE id = ?").use { statement ->
                statement.setLong(1, tokenId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test helper: no token row with id $tokenId" }
                    rows.getBytes(1)
                }
            }
        }
    }

    /**
     * [text] with its last character replaced by the alphabet character whose index differs
     * in the lowest bit. The last character of a fresh token has two spare low bits that
     * are zero, so the result decodes to the same 32 bytes while being a different spelling.
     */
    fun otherSpellingOfSameBytes(text: String): String {
        require(text.isNotEmpty()) { "sync-api test helper: empty token text" }
        val index = ALPHABET.indexOf(text[text.length - 1])
        require(index >= 0) { "sync-api test helper: last character is outside the url-safe alphabet" }
        return text.substring(0, text.length - 1) + ALPHABET[index xor 1]
    }

    /**
     * A random source that ignores entropy and fills every array with the bytes
     * (fill + index) and 255. Two sources with equal [fill] make equal tokens.
     */
    fun fakeRandom(fill: Int): SecureRandom = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (index in bytes.indices) {
                bytes[index] = ((fill + index) and 255).toByte()
            }
        }
    }
}
