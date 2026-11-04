package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.SqliteDatabase
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal open class TokenStore(
    private val db: SqliteDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    // Open so that a test can override it to place a reset in the gap between the verify and the insert.
    // A pending account gets no session: the flag is read on this connection, in the same transaction.
    open suspend fun issueSession(accountId: Long): IssueResult {
        val secret = TokenSecret.generate(random)
        val hash = secret.hash()
        val nowMs = clock.millis()
        return db.transaction<IssueResult> { connection ->
            if (readOwnerRole(connection, accountId) == null) {
                IssueResult.NoSuchAccount
            } else if (readOwnerPending(connection, accountId)) {
                IssueResult.ResetPending
            } else {
                val id = insertToken(
                    connection,
                    accountId,
                    TokenKind.SESSION,
                    null,
                    null,
                    hash,
                    nowMs,
                    nowMs + SESSION_IDLE_LIFETIME.toMillis(),
                    null,
                )
                IssueResult.Issued(IssuedToken(id, secret, TokenKind.SESSION, null, null))
            }
        }
    }

    // Issues a session inside the caller's open transaction, on the caller's connection, so the
    // sign-in exchange can use the code and make the session in one commit. The caller has already
    // checked the account. The name is kept as given; a null name means no name. The reset completion
    // uses it after its own UPDATE has cleared reset_pending in the same transaction, and the sign-in
    // exchange checks reset_pending itself before it calls this, so this function does not read the flag.
    internal fun issueSessionOn(connection: Connection, accountId: Long, deviceName: String?, nowMs: Long): IssuedToken {
        val secret = TokenSecret.generate(random)
        val id = insertToken(
            connection,
            accountId,
            TokenKind.SESSION,
            null,
            null,
            secret.hash(),
            nowMs,
            nowMs + SESSION_IDLE_LIFETIME.toMillis(),
            deviceName,
        )
        return IssuedToken(id, secret, TokenKind.SESSION, null, null)
    }

    // No production route calls this yet. A pending owner gets no token, the same as for a session; the
    // check comes before the scope check, so a pending owner is refused whatever scope is asked for.
    suspend fun issueAgent(
        ownerAccountId: Long,
        scope: AgentScope,
        label: String,
        expiresAt: Instant?,
    ): IssueResult {
        if (!isValidLabel(label)) return IssueResult.InvalidLabel
        val nowMs = clock.millis()
        val expiresAtMs: Long? = expiresAt?.toEpochMilli()
        // An expiry equal to now is already dead: a token is dead AT its expiry instant.
        if (expiresAtMs != null && expiresAtMs <= nowMs) return IssueResult.InvalidExpiry

        val secret = TokenSecret.generate(random)
        val hash = secret.hash()
        return db.transaction<IssueResult> { connection ->
            val ownerRole = readOwnerRole(connection, ownerAccountId)
            if (ownerRole == null) {
                IssueResult.NoSuchAccount
            } else if (readOwnerPending(connection, ownerAccountId)) {
                IssueResult.ResetPending
            } else if (scope == AgentScope.ADMIN && ownerRole != Role.ADMIN) {
                IssueResult.ScopeNotAllowed
            } else {
                val id =
                    insertToken(connection, ownerAccountId, TokenKind.AGENT, scope, label, hash, nowMs, expiresAtMs, null)
                val reported: Instant? = if (expiresAtMs == null) null else Instant.ofEpochMilli(expiresAtMs)
                IssueResult.Issued(IssuedToken(id, secret, TokenKind.AGENT, scope, reported))
            }
        }
    }

    suspend fun resolve(presented: String): ResolvedToken? {
        val secret = TokenSecret.parse(presented) ?: return null
        val hash = secret.hash()
        val nowMs = clock.millis()
        return db.transaction<ResolvedToken?> { connection ->
            val token = readLiveToken(connection, hash, nowMs)
            if (token != null && token.kind == TokenKind.SESSION) {
                slideSessionExpiry(connection, token.tokenId, nowMs)
            }
            token
        }
    }

    /**
     * Peeks at the presented token without moving its expiry: the same live rule as resolve,
     * but read-only. Session tokens are not slid and agent tokens are never touched.
     */
    suspend fun peek(presented: String): ResolvedToken? {
        val secret = TokenSecret.parse(presented) ?: return null
        val hash = secret.hash()
        val nowMs = clock.millis()
        return db.transaction<ResolvedToken?> { connection -> readLiveToken(connection, hash, nowMs) }
    }

    private fun slideSessionExpiry(connection: Connection, tokenId: Long, nowMs: Long) {
        connection.prepareStatement(SLIDE_SESSION_SQL).use { statement ->
            statement.setLong(1, nowMs + SESSION_IDLE_LIFETIME.toMillis())
            statement.setLong(2, tokenId)
            statement.executeUpdate()
        }
    }

    suspend fun revokeAgentToken(tokenId: Long): Boolean {
        val nowMs = clock.millis()
        val changed = db.transaction<Int> { connection -> revoke(connection, REVOKE_AGENT_SQL, nowMs, tokenId, null) }
        return changed > 0
    }

    /** True when a row with this id exists and is an agent token, regardless of revoked state. */
    suspend fun agentTokenExists(tokenId: Long): Boolean =
        db.transaction<Boolean> { connection ->
            connection.prepareStatement(SELECT_AGENT_EXISTS_SQL).use { statement ->
                statement.setLong(1, tokenId)
                statement.executeQuery().use { rows -> rows.next() }
            }
        }

    // Logout revokes the presented session token alone: not the account's other
    // sessions (change-password's job) and never its agent tokens.
    suspend fun revokeSession(tokenId: Long): Boolean {
        val nowMs = clock.millis()
        val changed = db.transaction<Int> { connection -> revoke(connection, REVOKE_SESSION_SQL, nowMs, tokenId, null) }
        return changed > 0
    }

    // The account's unredeemed sign-in codes are deleted in the same transaction. The count
    // returned is the revoked token rows only.
    suspend fun revokeAllForAccount(accountId: Long): Int =
        db.transaction<Int> { connection -> revokeAllForAccountOn(connection, accountId) }

    // The same work on the caller's connection, so a caller can run it inside its own transaction.
    // Returns the number of revoked token rows.
    internal fun revokeAllForAccountOn(connection: Connection, accountId: Long): Int {
        val revoked = revoke(connection, REVOKE_ALL_SQL, clock.millis(), accountId, null)
        deleteAccountHandoff(connection, accountId)
        return revoked
    }

    // keepTokenId is only excluded from the set of live sessions of this account. An
    // id that is not one of them (an agent token, another account's session, an
    // already revoked or unknown id) spares nothing: every live session is revoked.
    // The account's unredeemed sign-in codes are deleted in the same transaction.
    suspend fun revokeOtherSessions(accountId: Long, keepTokenId: Long): Int =
        db.transaction<Int> { connection -> revokeOtherSessionsOn(connection, accountId, keepTokenId) }

    // The same work on the caller's connection, so a caller can run it inside its own transaction.
    // Returns the number of revoked token rows.
    internal fun revokeOtherSessionsOn(connection: Connection, accountId: Long, keepTokenId: Long): Int {
        val revoked = revoke(connection, REVOKE_OTHER_SESSIONS_SQL, clock.millis(), accountId, keepTokenId)
        deleteAccountHandoff(connection, accountId)
        return revoked
    }

    // True when the session [tokenId] of [accountId] is live now: not revoked and not expired, by the
    // same rule as readLiveToken. Read only: the expiry is not moved. Runs on the caller's connection.
    internal fun isSessionLiveOn(connection: Connection, tokenId: Long, accountId: Long): Boolean =
        connection.prepareStatement(SESSION_LIVE_SQL).use { statement ->
            statement.setLong(1, tokenId)
            statement.setLong(2, accountId)
            statement.setLong(3, clock.millis())
            statement.executeQuery().use { rows -> rows.next() }
        }

    private fun deleteAccountHandoff(connection: Connection, accountId: Long) {
        connection.prepareStatement(DELETE_ACCOUNT_HANDOFF_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate()
        }
    }

    private fun isValidLabel(label: String): Boolean {
        val length = label.codePointCount(0, label.length)
        if (length < 1 || length > MAX_LABEL_CODE_POINTS) return false
        // codePoints() yields a lone surrogate as itself, so one in range is unpaired.
        return label.codePoints().noneMatch { codePoint: Int ->
            Character.isISOControl(codePoint) || codePoint in SURROGATES
        }
    }

    private fun readOwnerRole(connection: Connection, accountId: Long): Role? =
        connection.prepareStatement(SELECT_ROLE_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                if (rows.next()) Role.fromDb(rows.getString(1)) else null
            }
        }

    // A missing row reads as not pending: the caller has already checked that the account exists.
    private fun readOwnerPending(connection: Connection, accountId: Long): Boolean =
        connection.prepareStatement(SELECT_PENDING_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                rows.next() && rows.getInt(1) == 1
            }
        }

    private fun insertToken(
        connection: Connection,
        accountId: Long,
        kind: TokenKind,
        scope: AgentScope?,
        label: String?,
        hash: ByteArray,
        createdAtMs: Long,
        expiresAtMs: Long?,
        deviceName: String?,
    ): Long {
        connection.prepareStatement(INSERT_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, kind.dbValue)
            if (scope == null) statement.setNull(3, Types.VARCHAR) else statement.setString(3, scope.dbValue)
            if (label == null) statement.setNull(4, Types.VARCHAR) else statement.setString(4, label)
            statement.setBytes(5, hash)
            statement.setLong(6, createdAtMs)
            if (expiresAtMs == null) statement.setNull(7, Types.BIGINT) else statement.setLong(7, expiresAtMs)
            if (deviceName == null) statement.setNull(8, Types.VARCHAR) else statement.setString(8, deviceName)
            statement.executeUpdate()
        }
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { rows ->
                check(rows.next()) { "sync-api: the new token row could not be read back" }
                rows.getLong(1)
            }
        }
    }

    private fun readLiveToken(connection: Connection, hash: ByteArray, nowMs: Long): ResolvedToken? =
        connection.prepareStatement(SELECT_LIVE_SQL).use { statement ->
            statement.setBytes(1, hash)
            statement.setLong(2, nowMs)
            statement.executeQuery().use { rows ->
                if (rows.next()) toResolved(rows) else null
            }
        }

    private fun toResolved(rows: ResultSet): ResolvedToken {
        val role = Role.fromDb(rows.getString(3))
        val kind = TokenKind.fromDb(rows.getString(4))
        var storedScope: AgentScope? = null
        var effectiveScope: AgentScope? = null
        if (kind == TokenKind.AGENT) {
            val scopeText: String? = rows.getString(5)
            val stored = AgentScope.fromDb(checkNotNull(scopeText) { "sync-api: agent token without a scope" })
            storedScope = stored
            // ADR-009: a token never carries more than its owner's role, also after a demotion.
            effectiveScope =
                if (stored == AgentScope.ADMIN && role == Role.ADMIN) AgentScope.ADMIN else AgentScope.TRANSCRIBE
        }
        return ResolvedToken(rows.getLong(1), rows.getLong(2), role, kind, storedScope, effectiveScope)
    }

    // A second id is bound only by the statements that have one.
    private fun revoke(connection: Connection, sql: String, nowMs: Long, first: Long, second: Long?): Int =
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, nowMs)
            statement.setLong(2, first)
            if (second != null) statement.setLong(3, second)
            statement.executeUpdate()
        }

    private companion object {
        const val MAX_LABEL_CODE_POINTS = 64
        // A session token expires after 30 days with no use; each authenticated use moves the
        // expiry to now + 30 days. A session row without an expiry gets one at its next use.
        val SESSION_IDLE_LIFETIME: Duration = Duration.ofDays(30)

        // A session token is also capped at an absolute 180 days from creation: the slide never
        // pushes its expiry past created_at_ms + 180d, so a session that keeps being used still
        // dies at the cap. Agent tokens are not capped (their expiry is set at issue and is not
        // slid), so the live-check guard is scoped to sessions and NULL-safe. The value is a
        // compile-time millisecond constant so it can sit in the SQL as a literal (SQLite has no
        // Duration type). 180 * 24 * 60 * 60 * 1000.
        const val MAX_SESSION_LIFETIME_MS: Long = 180L * 24 * 60 * 60 * 1000

        val SURROGATES: IntRange = Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code

        const val SELECT_ROLE_SQL = "SELECT role FROM accounts WHERE id = ?"

        const val SELECT_PENDING_SQL = "SELECT reset_pending FROM accounts WHERE id = ?"

        const val INSERT_SQL =
            "INSERT INTO tokens (account_id, kind, scope, label, token_hash, created_at_ms, expires_at_ms, device_name) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

        // One query: the owner's role is read now, never cached with the token. The
        // database compares hash to hash; the caller controls only the preimage.
        const val SELECT_LIVE_SQL =
            "SELECT t.id, t.account_id, a.role, t.kind, t.scope FROM tokens t " +
                "JOIN accounts a ON a.id = t.account_id " +
                "WHERE t.token_hash = ? AND t.revoked_at_ms IS NULL " +
                "AND (t.expires_at_ms IS NULL OR t.expires_at_ms > ?) " +
                "AND (t.kind <> 'session' OR t.expires_at_ms IS NULL " +
                "OR t.expires_at_ms <= t.created_at_ms + " + MAX_SESSION_LIFETIME_MS + ")"

        // The same live rule as SELECT_LIVE_SQL for a session: not revoked, not expired, and not past the
        // 180-day cap from its creation.
        const val SESSION_LIVE_SQL =
            "SELECT 1 FROM tokens WHERE id = ? AND account_id = ? AND kind = 'session' " +
                "AND revoked_at_ms IS NULL AND (expires_at_ms IS NULL OR expires_at_ms > ?) " +
                "AND (expires_at_ms IS NULL OR expires_at_ms <= created_at_ms + " + MAX_SESSION_LIFETIME_MS + ")"

        // Slides a session token's expiry to now + 30 days on each authenticated use, but never
        // past created_at_ms + 180d: MIN(now+30d, created+180d) is computed in SQL so the cap is
        // enforced atomically with the slide. Agent tokens are never touched by this.
        const val SLIDE_SESSION_SQL =
            "UPDATE tokens SET expires_at_ms = MIN(?, created_at_ms + " + MAX_SESSION_LIFETIME_MS + ") " +
                "WHERE id = ? AND kind = 'session'"

        const val SELECT_AGENT_EXISTS_SQL = "SELECT 1 FROM tokens WHERE id = ? AND kind = 'agent'"

        const val REVOKE_AGENT_SQL =
            "UPDATE tokens SET revoked_at_ms = ? WHERE id = ? AND kind = 'agent' AND revoked_at_ms IS NULL"

        const val REVOKE_SESSION_SQL =
            "UPDATE tokens SET revoked_at_ms = ? WHERE id = ? AND kind = 'session' AND revoked_at_ms IS NULL"

        const val REVOKE_ALL_SQL = "UPDATE tokens SET revoked_at_ms = ? WHERE account_id = ? AND revoked_at_ms IS NULL"

        const val REVOKE_OTHER_SESSIONS_SQL =
            "UPDATE tokens SET revoked_at_ms = ? WHERE account_id = ? AND kind = 'session' " +
                "AND revoked_at_ms IS NULL AND id <> ?"

        const val DELETE_ACCOUNT_HANDOFF_SQL = "DELETE FROM signin_handoff WHERE account_id = ?"
    }
}
