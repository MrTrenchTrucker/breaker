package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.KeyBundle
import dev.breaker.server.syncapi.accounts.StoreKeysResult
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.respondSessionRequired
import dev.breaker.server.syncapi.transcriptions.respondUnavailable
import dev.breaker.server.syncapi.transcriptions.sessionAccountOrRefuse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import java.util.Base64

// The text and decoded sizes of the three values: standard padded base64 of 60, 60 and 32 bytes.
private const val WRAPPED_TEXT_LENGTH = 80
private const val PUBKEY_TEXT_LENGTH = 44
private const val WRAPPED_BYTES = 60
private const val PUBKEY_BYTES = 32

/**
 * Installs `POST /v1/auth/keys` on this application.
 *
 * A signed-in user stores the account's three key values once. The route checks, in this order:
 * the user session (an agent token is refused before the body is read), the body size cap, the
 * JSON shape, then the base64 text and decoded size of each value. Only then does it write, in one
 * store call. An account that already has keys is refused with 409. No request value is ever
 * echoed into a response, an exception text or a log.
 */
internal fun Application.authKeysRoute(accountStore: AccountStore, tokenStore: TokenStore) {
    routing {
        post("/v1/auth/keys") {
            val accountId = call.sessionAccountOrRefuse(tokenStore) ?: return@post
            val body = call.receiveAuthBody() ?: return@post
            val fields = parseObjectFields(body, required = listOf("wrapped_dek", "wrapped_box_privkey", "box_pubkey"))
            val wrappedDek = decodeExact(fields?.get("wrapped_dek"), WRAPPED_TEXT_LENGTH, WRAPPED_BYTES)
            val wrappedBoxPrivkey = decodeExact(fields?.get("wrapped_box_privkey"), WRAPPED_TEXT_LENGTH, WRAPPED_BYTES)
            val boxPubkey = decodeExact(fields?.get("box_pubkey"), PUBKEY_TEXT_LENGTH, PUBKEY_BYTES)
            if (wrappedDek == null || wrappedBoxPrivkey == null || boxPubkey == null) {
                call.respondBadRequest()
                return@post
            }
            val bundle = KeyBundle(wrappedDek, wrappedBoxPrivkey, boxPubkey)
            val outcome = try {
                accountStore.storeKeys(accountId, bundle)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }
            when (outcome) {
                is StoreKeysResult.Stored -> {
                    call.respondText(
                        """{"key_version":${outcome.keyVersion}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                }
                is StoreKeysResult.AlreadyKeyed -> {
                    call.respondText(
                        """{"error":"conflict","message":"The account already has keys"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Conflict,
                    )
                }
                is StoreKeysResult.NoSuchAccount -> {
                    call.respondSessionRequired()
                }
            }
        }
    }
}

// Returns the decoded bytes when the text has exactly [textLength] characters and strict standard
// base64 (padded, no whitespace, no url-safe characters) decodes it to exactly [byteCount] bytes.
// Returns null for a missing value or any other text.
private fun decodeExact(text: String?, textLength: Int, byteCount: Int): ByteArray? {
    if (text == null || text.length != textLength) return null
    val bytes = try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (bytes.size != byteCount) return null
    return bytes
}
