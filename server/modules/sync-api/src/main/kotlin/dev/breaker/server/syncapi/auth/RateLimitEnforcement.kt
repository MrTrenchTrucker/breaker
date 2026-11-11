package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.accounts.RateLimitResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.response.header

/** Fixed 429 body for every rate limit (register, salt, complete-reset, sync). */
internal const val RATE_LIMIT_429_BODY =
    """{"error":"rate_limited","message":"Too many requests."}"""

/** Maximum Retry-After in whole seconds (ruling). */
internal const val MAX_RETRY_AFTER_SECONDS: Int = 3600

/**
 * Enforces two limits for one ([route], address): a per-address cap and a global
 * backstop cap, read live from [settings]. Both limits fail open: if the counter
 * store or the switch read throws, the request proceeds as if there were no limit.
 *
 * Both rows are read in ONE transaction: if either cap would be exceeded, the
 * request is refused and NEITHER counter is incremented (a refusal is never
 * counted). Only when both caps pass are both counters incremented.
 *
 * Returns null when the request may proceed, or a [RateLimitResult.Refused] when
 * the address (or the global backstop) is over its cap. When refused, this helper
 * does NOT answer the response itself — the caller answers 429 with Retry-After
 * and the fixed body.
 */
internal suspend fun checkRateLimits(
    call: ApplicationCall,
    counterStore: RateLimitCounterStore,
    settings: dev.breaker.server.syncapi.settings.ServerSettingsStore,
    route: String,
    perAddressCap: Int,
    globalCap: Int,
): RateLimitResult.Refused? = enforceLimits(
    switchOn = { settings.rateLimitsEnabled() },
    addressOf = { counterStore.peerAddress(call) },
    checkPair = { address -> counterStore.checkPair(route, address, perAddressCap, globalCap) },
)

/**
 * The rule behind [checkRateLimits], over three small ports so a test can play every failure:
 * the switch read, the source address, and the counter check. A store error never blocks a
 * user (ADR-041): a failed switch read or a failed counter check lets the request through and
 * nothing is counted; a cancellation is never swallowed.
 */
internal suspend fun enforceLimits(
    switchOn: suspend () -> Boolean,
    addressOf: () -> String,
    checkPair: suspend (String) -> RateLimitResult,
): RateLimitResult.Refused? {
    val on = try {
        switchOn()
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
    if (!on) return null
    val result = try {
        checkPair(addressOf())
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
    return if (result is RateLimitResult.Refused) result else null
}

/**
 * The rule for a route that counts FAILURES only (complete-reset), over four small ports so a test can play
 * every failure: the switch read, the source address, the read-only cap check and the failure count. On a
 * failing request it peeks the cap: over it, the refusal comes back and nothing is counted; otherwise one
 * failure is counted and the request goes on. A store error never blocks a user (ADR-041): a failed switch
 * read, a failed cap check or a failed count lets the request through; a cancellation is never swallowed.
 */
internal suspend fun enforceFailureCap(
    switchOn: suspend () -> Boolean,
    addressOf: () -> String,
    peekOver: suspend (String) -> RateLimitResult.Refused?,
    countFailure: suspend (String) -> Unit,
): RateLimitResult.Refused? {
    val on = try {
        switchOn()
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
    if (!on) return null
    val address = addressOf()
    val refused = try {
        peekOver(address)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
    if (refused != null) return refused
    try {
        countFailure(address)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // fail open: a failed count never changes the answer
    }
    return null
}

/** Answers a 429 with Retry-After (whole seconds, min 1, max 3600) and the fixed JSON body. */
internal suspend fun respondRateLimited(call: ApplicationCall, refused: RateLimitResult.Refused) {
    val retryAfter = (refused.retryAfterSeconds.coerceAtLeast(1)).coerceAtMost(MAX_RETRY_AFTER_SECONDS)
    call.response.header("Retry-After", retryAfter.toString())
    call.respondText(RATE_LIMIT_429_BODY, ContentType.Application.Json, HttpStatusCode.TooManyRequests)
}
