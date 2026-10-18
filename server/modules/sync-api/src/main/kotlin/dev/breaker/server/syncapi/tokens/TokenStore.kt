package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.SqliteDatabase
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.Clock
import java.time.Instant

internal class TokenStore(
    private val db: SqliteDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun issueSession(accountId: Long): IssueResult {
        val secret = TokenSecret.generate(random)
        val hash = secret.hash()
        val nowMs = clock.millis()
        return db.transaction<IssueResult> { connection ->
            if (readOwnerRole(connection, accountId) == null) {
                IssueResult.NoSuchAccount
            } else {
                val id = insertToken(connection, accountId, TokenKind.SESSION, null, null, hash, nowMs, null)
                IssueResult.Issued(IssuedToken(id, secret, TokenKind.SESSION, null, null))
            }
        }
    }

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
            } else if (scope == AgentScope.ADMIN && ownerRole != Role.ADMIN) {
                IssueResult.ScopeNotAllowed
            } else {
                val id =
                    insertToken(connection, ownerAccountId, TokenKind.AGENT, scope, label, hash, nowMs, expiresAtMs)
                val reported: Instant? = if (expiresAtMs == null) null else Instant.ofEpochMilli(expiresAtMs)
                IssueResult.Issued(IssuedToken(id, secret, TokenKind.AGENT, scope, reported))
            }
        }
    }

    suspend fun resolve(presented: String): ResolvedToken? {
        val secret = TokenSecret.parse(presented) ?: return null
        val hash = secret.hash()
        val nowMs = clock.millis()
        return db.transaction<ResolvedToken?> { connection -> readLiveToken(connection, hash, nowMs) }
    }

    suspend fun revokeAgentToken(tokenId: Long): Boolean {
        val nowMs = clock.millis()
        val changed = db.transaction<Int> { connection -> revoke(connection, REVOKE_AGENT_SQL, nowMs, tokenId, null) }
        return changed > 0
    }

    suspend fun revokeAllForAccount(accountId: Long): Int {
        val nowMs = clock.millis()
        return db.transaction<Int> { connection -> revoke(connection, REVOKE_ALL_SQL, nowMs, accountId, null) }
    }

    // keepTokenId is only excluded from the set of live sessions of this account. An
    // id that is not one of them (an agent token, another account's session, an
    // already revoked or unknown id) spares nothing: every live session is revoked.
    suspend fun revokeOtherSessions(accountId: Long, keepTokenId: Long): Int {
        val nowMs = clock.millis()
        return db.transaction<Int> { connection ->
            revoke(connection, REVOKE_OTHER_SESSIONS_SQL, nowMs, accountId, keepTokenId)
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

    private fun insertToken(
        connection: Connection,
        accountId: Long,
        kind: TokenKind,
        scope: AgentScope?,
        label: String?,
        hash: ByteArray,
        createdAtMs: Long,
        expiresAtMs: Long?,
    ): Long {
        connection.prepareStatement(INSERT_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, kind.dbValue)
            if (scope == null) statement.setNull(3, Types.VARCHAR) else statement.setString(3, scope.dbValue)
            if (label == null) statement.setNull(4, Types.VARCHAR) else statement.setString(4, label)
            statement.setBytes(5, hash)
            statement.setLong(6, createdAtMs)
            if (expiresAtMs == null) statement.setNull(7, Types.BIGINT) else statement.setLong(7, expiresAtMs)
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

        val SURROGATES: IntRange = Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code

        const val SELECT_ROLE_SQL = "SELECT role FROM accounts WHERE id = ?"

        const val INSERT_SQL =
            "INSERT INTO tokens (account_id, kind, scope, label, token_hash, created_at_ms, expires_at_ms) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"

        // One query: the owner's role is read now, never cached with the token. The
        // database compares hash to hash; the caller controls only the preimage.
        const val SELECT_LIVE_SQL =
            "SELECT t.id, t.account_id, a.role, t.kind, t.scope FROM tokens t " +
                "JOIN accounts a ON a.id = t.account_id " +
                "WHERE t.token_hash = ? AND t.revoked_at_ms IS NULL " +
                "AND (t.expires_at_ms IS NULL OR t.expires_at_ms > ?)"

        const val REVOKE_AGENT_SQL =
            "UPDATE tokens SET revoked_at_ms = ? WHERE id = ? AND kind = 'agent' AND revoked_at_ms IS NULL"

        const val REVOKE_ALL_SQL = "UPDATE tokens SET revoked_at_ms = ? WHERE account_id = ? AND revoked_at_ms IS NULL"

        const val REVOKE_OTHER_SESSIONS_SQL =
            "UPDATE tokens SET revoked_at_ms = ? WHERE account_id = ? AND kind = 'session' " +
                "AND revoked_at_ms IS NULL AND id <> ?"
    }
}
