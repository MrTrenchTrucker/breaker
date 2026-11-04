package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.VerifyResult
import dev.breaker.server.syncapi.passwordchange.PasswordChangeRequest
import dev.breaker.server.syncapi.passwordchange.PasswordChangeResult
import dev.breaker.server.syncapi.passwordchange.PasswordChangeStore
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.respondSessionRequired
import dev.breaker.server.syncapi.transcriptions.respondSyncError
import dev.breaker.server.syncapi.transcriptions.respondUnavailable
import dev.breaker.server.syncapi.transcriptions.sessionOrRefuse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

private const val MESSAGE_OLD_VERIFIER_WRONG = "Invalid old verifier"
private const val MESSAGE_NO_KEYS = "The account has no keys"
private const val MESSAGE_KEY_VERSION = "key_version is not the account's current key_version plus one"
private const val MESSAGE_PREVIOUS_UNFINISHED = "A previous re-key is unfinished"

/**
 * Installs `POST /v1/auth/change-password` on this application.
 *
 * A signed-in user replaces the account's password material and key wrapping in one request. The
 * route checks, in this order: the user session (an agent token is refused before the body is
 * read), whether the account waits for a reset (409, before the body is read), the body size cap, the strict body shape, then the old verifier through the same path as
 * login (a wrong one is a counted wrong try, a locked name answers 423). Only then does the store
 * make the change, in one transaction that re-reads the session and the account state first. No
 * request value is ever echoed into a response, an exception text or a log.
 */
internal fun Application.authChangePasswordRoute(accountStore: AccountStore, tokenStore: TokenStore) {
    val store = PasswordChangeStore(accountStore, tokenStore)
    routing {
        post("/v1/auth/change-password") {
            val resolved = call.sessionOrRefuse(tokenStore) ?: return@post
            // A reset account is refused here, before the body is read and before the old verifier
            // is checked, so the refusal never counts a wrong try.
            val pending = try {
                accountStore.resetPendingOf(resolved.accountId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }
            if (pending == null) {
                call.respondSessionRequired()
                return@post
            }
            if (pending) {
                call.respondResetPending()
                return@post
            }
            val body = call.receiveAuthBody() ?: return@post
            val parsed = parseChangePasswordBody(body)
            if (parsed == null) {
                call.respondBadRequest()
                return@post
            }

            val name = try {
                accountStore.foldedNameOf(resolved.accountId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }
            if (name == null) {
                call.respondSessionRequired()
                return@post
            }

            val proof = try {
                accountStore.verify(name, parsed.oldVerifier)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }
            when (proof) {
                is VerifyResult.Verified -> {
                    if (proof.accountId != resolved.accountId) {
                        call.respondSessionRequired()
                        return@post
                    }
                }
                VerifyResult.Rejected -> {
                    call.respondSyncError(HttpStatusCode.Unauthorized, "unauthorized", MESSAGE_OLD_VERIFIER_WRONG)
                    return@post
                }
                is VerifyResult.Locked -> {
                    call.respondLocked(proof.secondsRemaining)
                    return@post
                }
            }

            // The slow hash runs here, before the store opens its transaction.
            val request = try {
                val hashed = accountStore.hashVerifier(parsed.newVerifier)
                PasswordChangeRequest(
                    newSalt = parsed.newSalt,
                    newKdf = parsed.newKdf,
                    newKdfVersion = parsed.newKdfVersion,
                    newVerifier = hashed,
                    newWrappedDek = parsed.newWrappedDek,
                    newWrappedBoxPrivkey = parsed.newWrappedBoxPrivkey,
                    newBoxPubkey = parsed.newBoxPubkey,
                    prevWrappedDek = parsed.prevWrappedDek,
                    newKeyVersion = parsed.newKeyVersion,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }

            val outcome = try {
                store.change(resolved.accountId, resolved.tokenId, request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }
            when (outcome) {
                is PasswordChangeResult.Changed -> {
                    call.respondText(
                        """{"key_version":${outcome.keyVersion}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                }
                PasswordChangeResult.TokenNotLive -> {
                    call.respondSessionRequired()
                }
                PasswordChangeResult.ResetPending -> {
                    call.respondResetPending()
                }
                PasswordChangeResult.NoKeys -> {
                    call.respondSyncError(HttpStatusCode.Conflict, "conflict", MESSAGE_NO_KEYS)
                }
                PasswordChangeResult.KeyVersionMismatch -> {
                    call.respondSyncError(HttpStatusCode.Conflict, "conflict", MESSAGE_KEY_VERSION)
                }
                PasswordChangeResult.PreviousUnfinished -> {
                    call.respondSyncError(HttpStatusCode.Conflict, "conflict", MESSAGE_PREVIOUS_UNFINISHED)
                }
            }
        }
    }
}
