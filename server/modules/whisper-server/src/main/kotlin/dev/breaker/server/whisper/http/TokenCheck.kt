package dev.breaker.server.whisper.http

/** The result of checking whether a presented token is live. */
internal sealed class TokenAnswer {
    /** The token is live and belongs to `accountId`. */
    data class Live(val accountId: Long) : TokenAnswer()
    /** The token is not live (401 from the token service). */
    object NotLive : TokenAnswer()
    /** The token check itself could not be performed (unavailable). */
    object Unavailable : TokenAnswer()
}

/** A client that asks sync-api over HTTP whether a presented token is live. */
internal interface TokenCheck {
    /** Look up the token; for a live session token its expiry moves to now + 30 days. */
    suspend fun use(token: String): TokenAnswer
    /** Peek at the token's liveness without writing anything. */
    suspend fun peek(token: String): TokenAnswer
}
