package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.accounts.Role
import java.time.Instant

internal class IssuedToken(
    val tokenId: Long,
    val secret: TokenSecret,
    val kind: TokenKind,
    val scope: AgentScope?,
    val expiresAt: Instant?,
) {
    // Logs and failure messages must never carry the secret: only the id and the kind.
    override fun toString(): String = "IssuedToken(id=$tokenId, kind=$kind)"
}

internal sealed class IssueResult {
    class Issued(val token: IssuedToken) : IssueResult()

    object NoSuchAccount : IssueResult()

    object InvalidLabel : IssueResult()

    object InvalidExpiry : IssueResult()

    object ScopeNotAllowed : IssueResult()
}

internal data class ResolvedToken(
    val tokenId: Long,
    val accountId: Long,
    /** The owner's role right now, read in the same query, never cached. */
    val role: Role,
    val kind: TokenKind,
    /** What the row says; null for a session token. */
    val storedScope: AgentScope?,
    /** What the caller may act as: never more than the owner's current role; null for a session token. */
    val effectiveScope: AgentScope?,
)
