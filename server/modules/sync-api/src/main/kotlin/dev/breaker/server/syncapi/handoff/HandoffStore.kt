package dev.breaker.server.syncapi.handoff

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.SqliteDatabase
import dev.breaker.server.syncapi.tokens.TokenSecret
import dev.breaker.server.syncapi.tokens.TokenStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.util.Base64

// The sign-in hand-off: a one-time code, made while a person is signed in on the website and
// traded by the app for a session. One row per code holds the SHA-256 of the code, the challenge
// the app sent and the sealed package, which is stored as given and never opened. A code is dead
// AT its expiry instant, and the exchange deletes the row, so no used marker exists.
internal class HandoffStore(
    private val db: SqliteDatabase,
    private val tokenStore: TokenStore,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    private class HandoffRow(val accountId: Long, val challenge: String, val packageBytes: ByteArray)

    private class AccountData(val username: String, val roleText: String, val keyVersion: Int)

    /**
     * Makes a code for [accountId] and returns its text, which is kept nowhere: only its hash is
     * stored. At most [MAX_LIVE_CODES] codes of one account are live, so a further start ends the
     * oldest live ones first.
     */
    suspend fun start(accountId: Long, challenge: String, sealedPackage: ByteArray): StartResult {
        val nowMs = clock.millis()
        val secret = TokenSecret.generate(random)
        val hash = secret.hash()
        return db.transaction<StartResult> { connection ->
            deleteExpiredOn(connection, nowMs)
            if (!accountExists(connection, accountId)) {
                StartResult.NoSuchAccount
            } else {
                makeRoom(connection, accountId)
                insertRow(connection, hash, accountId, challenge, sealedPackage, nowMs)
                StartResult.Started(secret.reveal())
            }
        }
    }

    /**
     * Trades a code and the app's secret for a session. Every failure is the one [RedeemResult.Refused].
     * The same work runs for an unknown, malformed, expired or used code: the presented values are
     * hashed and compared against fixed filler values when there is no real row. A wrong secret leaves
     * the row untouched, so the right secret still redeems it.
     */
    suspend fun redeem(code: String, secret: String, deviceName: String?): RedeemResult {
        if (deviceName != null && !isValidDeviceName(deviceName)) {
            throw IllegalArgumentException("sync-api: invalid device name")
        }
        val nowMs = clock.millis()
        val parsedCode = TokenSecret.parse(code)
        val parsedSecret = TokenSecret.parse(secret)
        val codeHash = parsedCode?.hash() ?: FILLER_HASH
        val presented = parsedSecret?.let { challengeOf(it) } ?: FILLER_PRESENTED
        return db.transaction<RedeemResult> { connection ->
            val row = readLiveRow(connection, codeHash, nowMs)
            val stored = row?.challenge ?: FILLER_STORED
            val matches = MessageDigest.isEqual(
                presented.toByteArray(Charsets.US_ASCII),
                stored.toByteArray(Charsets.US_ASCII),
            )
            if (row == null || !matches || parsedCode == null || parsedSecret == null) {
                RedeemResult.Refused
            } else {
                finishRedeem(connection, codeHash, row, deviceName, nowMs)
            }
        }
    }

    // Mirrors TokenStore.isValidLabel: 1 to 64 code points, no ISO controls and no unpaired surrogates.
    private fun isValidDeviceName(name: String): Boolean {
        val length = name.codePointCount(0, name.length)
        if (length < 1 || length > MAX_DEVICE_NAME_CODE_POINTS) return false
        return name.codePoints().noneMatch { codePoint: Int ->
            Character.isISOControl(codePoint) || codePoint in DEVICE_NAME_SURROGATES
        }
    }

    /** Deletes every row whose expiry is at or before now and returns how many. The sweep calls this. */
    suspend fun deleteExpired(): Int {
        val nowMs = clock.millis()
        return db.transaction<Int> { connection -> deleteExpiredOn(connection, nowMs) }
    }

    // The challenge is the SHA-256 of the secret text, written as 43 URL-safe characters.
    private fun challengeOf(secret: TokenSecret): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(secret.hash())

    private fun deleteExpiredOn(connection: Connection, nowMs: Long): Int =
        connection.prepareStatement(DELETE_EXPIRED_SQL).use { statement ->
            statement.setLong(1, nowMs)
            statement.executeUpdate()
        }

    private fun accountExists(connection: Connection, accountId: Long): Boolean =
        connection.prepareStatement(SELECT_ACCOUNT_EXISTS_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows -> rows.next() }
        }

    // Keeps room for the row about to be inserted: when the account already holds the most live
    // codes, the oldest ones go (the row id breaks a tie of equal times).
    private fun makeRoom(connection: Connection, accountId: Long) {
        val live = connection.prepareStatement(COUNT_ACCOUNT_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sync-api: the sign-in code count could not be read" }
                rows.getLong(1)
            }
        }
        if (live >= MAX_LIVE_CODES) {
            connection.prepareStatement(DELETE_OLDEST_SQL).use { statement ->
                statement.setLong(1, accountId)
                statement.setLong(2, live - (MAX_LIVE_CODES - 1))
                statement.executeUpdate()
            }
        }
    }

    private fun insertRow(
        connection: Connection,
        codeHash: ByteArray,
        accountId: Long,
        challenge: String,
        sealedPackage: ByteArray,
        nowMs: Long,
    ) {
        connection.prepareStatement(INSERT_SQL).use { statement ->
            statement.setBytes(1, codeHash)
            statement.setLong(2, accountId)
            statement.setString(3, challenge)
            statement.setBytes(4, sealedPackage)
            statement.setLong(5, nowMs)
            statement.setLong(6, nowMs + CODE_LIFETIME.toMillis())
            statement.executeUpdate()
        }
    }

    private fun readLiveRow(connection: Connection, codeHash: ByteArray, nowMs: Long): HandoffRow? =
        connection.prepareStatement(SELECT_LIVE_SQL).use { statement ->
            statement.setBytes(1, codeHash)
            statement.setLong(2, nowMs)
            statement.executeQuery().use { rows ->
                if (rows.next()) HandoffRow(rows.getLong(1), rows.getString(2), rows.getBytes(3)) else null
            }
        }

    // Deleting the row and inserting the session run in the caller's one transaction: an exception
    // rolls both back, so the code stays live and the exception carries no value.
    private fun finishRedeem(
        connection: Connection,
        codeHash: ByteArray,
        row: HandoffRow,
        deviceName: String?,
        nowMs: Long,
    ): RedeemResult {
        val deleted = connection.prepareStatement(DELETE_ROW_SQL).use { statement ->
            statement.setBytes(1, codeHash)
            statement.executeUpdate()
        }
        if (deleted != 1) return RedeemResult.Refused
        val account = readAccount(connection, row.accountId)
        val issued = tokenStore.issueSessionOn(connection, row.accountId, deviceName, nowMs)
        return RedeemResult.Redeemed(
            issued.secret.reveal(),
            Role.fromDb(account.roleText),
            account.keyVersion,
            row.packageBytes,
            account.username,
            row.accountId,
        )
    }

    private fun readAccount(connection: Connection, accountId: Long): AccountData =
        connection.prepareStatement(SELECT_ACCOUNT_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sync-api: the account of a sign-in code could not be read" }
                AccountData(rows.getString(1), rows.getString(2), rows.getInt(3))
            }
        }

    companion object {
        const val MAX_LIVE_CODES = 5
        const val MAX_DEVICE_NAME_CODE_POINTS = 64
        val DEVICE_NAME_SURROGATES: IntRange = Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
        val CODE_LIFETIME: Duration = Duration.ofMinutes(5)

        private const val HASH_BYTES = 32

        // Used when the presented code is malformed, so the lookup and the compare still run.
        // The two texts differ, so a filler compare can never match.
        private val FILLER_HASH: ByteArray = ByteArray(HASH_BYTES)
        private val FILLER_PRESENTED: String = "P".repeat(TokenSecret.TEXT_LENGTH)
        private val FILLER_STORED: String = "Q".repeat(TokenSecret.TEXT_LENGTH)

        private const val DELETE_EXPIRED_SQL = "DELETE FROM signin_handoff WHERE expires_at_ms <= ?"

        private const val SELECT_ACCOUNT_EXISTS_SQL = "SELECT 1 FROM accounts WHERE id = ?"

        private const val COUNT_ACCOUNT_SQL = "SELECT count(*) FROM signin_handoff WHERE account_id = ?"

        private const val DELETE_OLDEST_SQL =
            "DELETE FROM signin_handoff WHERE code_hash IN " +
                "(SELECT code_hash FROM signin_handoff WHERE account_id = ? " +
                "ORDER BY created_at_ms ASC, rowid ASC LIMIT ?)"

        private const val INSERT_SQL =
            "INSERT INTO signin_handoff (code_hash, account_id, challenge, package, created_at_ms, expires_at_ms) " +
                "VALUES (?, ?, ?, ?, ?, ?)"

        // A row at or past its expiry is not live: live means expires_at_ms > now.
        private const val SELECT_LIVE_SQL =
            "SELECT account_id, challenge, package FROM signin_handoff WHERE code_hash = ? AND expires_at_ms > ?"

        private const val DELETE_ROW_SQL = "DELETE FROM signin_handoff WHERE code_hash = ?"

        private const val SELECT_ACCOUNT_SQL = "SELECT username, role, key_version FROM accounts WHERE id = ?"
    }
}
