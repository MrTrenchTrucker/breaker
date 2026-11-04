package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.AuthVerifier
import dev.breaker.server.syncapi.accounts.KdfBounds
import dev.breaker.server.syncapi.accounts.KdfParams
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

// The decoded size and the standard padded base64 text length of the client salt and the verifier.
private const val RESET_SALT_BYTES = 16
private const val RESET_SALT_TEXT_LENGTH = 24
private const val RESET_VERIFIER_TEXT_LENGTH = 44

/**
 * Installs `POST /v1/auth/complete-reset` on this application.
 *
 * A person whose account was reset sets the new password with the reset code. No session is needed.
 * The Authorization header is read only to refuse an agent token (403, before the body is read);
 * any other header is ignored and no session expiry is moved. The route checks, in this order: the
 * agent refusal, the body size cap (413), the body shape (400), the name rules and the reset code
 * (one fixed 401 for every failure), then the salt, the KDF values and the verifier (400), all
 * before anything is written. The slow hash runs before the store opens its transaction. Success
 * answers with the login body. There is no attempt counter, a locked name does not block a right
 * code, and nothing here logs a request value.
 */
internal fun Application.authCompleteResetRoute(accountStore: AccountStore, tokenStore: TokenStore) {
    val store = ResetStore(accountStore, tokenStore)
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
                call.respondBadRequest()
                return@post
            }
            if (!UsernameRules.isValid(parsed.username)) {
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
                call.respondText(COMPLETE_RESET_REFUSED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }

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
                    call.respondText(COMPLETE_RESET_REFUSED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                }
            }
        }
    }
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
