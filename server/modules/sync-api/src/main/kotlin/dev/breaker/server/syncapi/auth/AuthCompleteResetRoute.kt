package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.AuthVerifier
import dev.breaker.server.syncapi.accounts.KdfBounds
import dev.breaker.server.syncapi.accounts.KdfParams
import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.accounts.UsernameRules
import dev.breaker.server.syncapi.accounts.withinBounds
import dev.breaker.server.syncapi.admin.bearerToken
import dev.breaker.server.syncapi.reset.CompleteResetRequest
import dev.breaker.server.syncapi.reset.CompleteResetResult
import dev.breaker.server.syncapi.reset.ResetStore
import dev.breaker.server.syncapi.tokens.TokenKind
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.respondAgentRefused
import dev.breaker.server.syncapi.transcriptions.respondUnavailable
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import java.util.Base64

// The one answer for every failed lookup: an unknown or malformed name, a wrong, malformed, expired
// or used code, and an account that is not waiting for a reset. The status and the bytes never differ.
private const val COMPLETE_RESET_REFUSED_BODY =
    """{"error":"unauthorized","message":"Invalid username or reset code"}"""

// Rate-limit caps (constants, source: ADR-041).
internal const val COMPLETE_RESET_PER_ADDRESS: Int = 10
internal const val COMPLETE_RESET_GLOBAL: Int = 120
internal const val COMPLETE_RESET_ROUTE: String = "complete-reset"

// The decoded size and the standard padded base64 text length of the client salt and the verifier.
private const val RESET_SALT_BYTES = 16
private const val RESET_SALT_TEXT_LENGTH = 24
private const val RESET_VERIFIER_TEXT_LENGTH = 44

/**
 * Installs `POST /v1/auth/complete-reset` on this application.
 *
 * A person whose account was reset sets the new password with the reset code. No session is needed.
 * The Authorization header is read only to refuse an agent token (403); any other header is ignored
 * and no session expiry is moved.
 *
 * Rate limit (ADR-041 step d): FAILED attempts only, per source address (10 per 60 s) plus a global
 * backstop (120 per 60 s). The check happens after the body is read and checkCode runs: a RIGHT code
 * is never counted and never refused — it proceeds to the slow hash and the store write as today.
 * Failure paths that count one failure: body does not parse (400), body shape wrong (400), username
 * or code rules fail (one fixed 401), store.complete comes back Refused (401). 413 and the agent 403
 * do not count. The counter store and the rate-limit switch fail open: on a read error the request
 * proceeds as if there were no limit.
 *
 * counterStore is nullable: null means no limit, which is how existing tests mount this route
 * without hitting the cap. syncApiModule passes the real store.
 */
internal fun Application.authCompleteResetRoute(
    accountStore: AccountStore,
    tokenStore: TokenStore,
    counterStore: RateLimitCounterStore?,
) {
    val store = ResetStore(accountStore, tokenStore)
    val settings = accountStore.settings
    routing {
        post("/v1/auth/complete-reset") {
            val presented = bearerToken(call.request.header("Authorization"))
            if (presented.isNotEmpty()) {
                val seen = try {
                    tokenStore.peek(presented)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    call.respondUnavailable()
                    return@post
                }
                if (seen != null && seen.kind == TokenKind.AGENT) {
                    call.respondAgentRefused()
                    return@post
                }
            }

            val text = call.receiveAuthBody() ?: return@post
            val parsed = parseCompleteResetBody(text)
            if (parsed == null) {
                if (refuseIfOverCap(call, counterStore, settings)) return@post
                call.respondBadRequest()
                return@post
            }
            if (!UsernameRules.isValid(parsed.username)) {
                if (refuseIfOverCap(call, counterStore, settings)) return@post
                call.respondText(COMPLETE_RESET_REFUSED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            val folded = UsernameRules.fold(parsed.username)

            val matched = try {
                store.checkCode(folded, parsed.resetCode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }
            if (!matched) {
                if (refuseIfOverCap(call, counterStore, settings)) return@post
                call.respondText(COMPLETE_RESET_REFUSED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }

            // A RIGHT code is never counted and never refused for rate: proceed to the slow hash
            // and the store write exactly as before the limit existed.
            val saltBytes = decodeResetBytes(parsed.saltText, RESET_SALT_TEXT_LENGTH, RESET_SALT_BYTES)
            val kdf = KdfParams(parsed.kdfMemoryKib, parsed.kdfIterations, parsed.kdfParallelism)
            val verifierBytes = decodeResetBytes(parsed.verifierText, RESET_VERIFIER_TEXT_LENGTH, AuthVerifier.LENGTH)
            val verifier = verifierBytes?.let { AuthVerifier.fromBytes(it) }
            if (saltBytes == null || !kdf.withinBounds() || parsed.kdfVersion != KdfBounds.REQUIRED_KDF_VERSION ||
                verifier == null
            ) {
                call.respondBadRequest()
                return@post
            }

            // The slow hash runs here, before the store opens its transaction.
            val request = try {
                val hashed = accountStore.hashVerifier(verifier)
                CompleteResetRequest(
                    newSalt = saltBytes,
                    newKdf = kdf,
                    newKdfVersion = parsed.kdfVersion,
                    newVerifier = hashed,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }

            val outcome = try {
                store.complete(folded, parsed.resetCode, request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }
            when (outcome) {
                is CompleteResetResult.Completed -> {
                    call.respondText(
                        buildLoginResponse(outcome.role.dbValue, outcome.sessionText, outcome.keys),
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                }
                CompleteResetResult.Refused -> {
                    if (refuseIfOverCap(call, counterStore, settings)) return@post
                    call.respondText(COMPLETE_RESET_REFUSED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                }
            }
        }
    }
}

/**
 * On a FAILING request: peeks the cap (read only). Over it, answers 429 and returns true; otherwise counts
 * one failure and returns false. A null store, the switch off, and any store or switch read error all let the
 * request through unchanged (the rule is [enforceFailureCap]).
 */
private suspend fun refuseIfOverCap(
    call: io.ktor.server.application.ApplicationCall,
    counterStore: RateLimitCounterStore?,
    settings: dev.breaker.server.syncapi.settings.ServerSettingsStore,
): Boolean {
    if (counterStore == null) return false
    val refused = enforceFailureCap(
        switchOn = { settings.rateLimitsEnabled() },
        addressOf = { counterStore.peerAddress(call) },
        peekOver = { address ->
            counterStore.peekOverCap(COMPLETE_RESET_ROUTE, address, COMPLETE_RESET_PER_ADDRESS, COMPLETE_RESET_GLOBAL)
        },
        countFailure = { address -> counterStore.countFailure(COMPLETE_RESET_ROUTE, address) },
    )
    if (refused == null) return false
    respondRateLimited(call, refused)
    return true
}

// Returns the decoded bytes when the text has exactly [textLength] characters and strict standard
// base64 (padded, no whitespace, no url-safe characters) decodes it to exactly [byteCount] bytes.
// Returns null for any other text.
private fun decodeResetBytes(text: String, textLength: Int, byteCount: Int): ByteArray? {
    if (text.length != textLength) return null
    val bytes = try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (bytes.size != byteCount) return null
    return bytes
}
