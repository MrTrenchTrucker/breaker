package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenKind
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

// POST /v1/sync (push one transcription) and GET /v1/transcriptions (the account's whole list, oldest
// first). Both take a user session token only: no or unknown token is 401, an agent token is 403.
// A store failure other than cancellation answers 503 with a fixed body; cancellation is rethrown.
internal fun Application.syncRoutes(tokenStore: TokenStore, transcriptionStore: TranscriptionStore) {
    routing {
        post("/v1/sync") {
            val accountId = call.sessionAccountOrRefuse(tokenStore) ?: return@post
            val text = call.receiveSyncBody() ?: return@post
            val body = when (val parsed = parseSyncPush(text)) {
                is SyncPushParse.Refused -> {
                    call.respondBadRequest(parsed.message)
                    return@post
                }
                is SyncPushParse.Accepted -> parsed.body
            }
            val outcome = try {
                transcriptionStore.push(accountId, body.clientId, body.keyVersion, body.encrypted)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }
            when (outcome) {
                // A push above the account's key_version and a lower-version refusal have their own
                // texts, so the store says which one applies.
                is PushResult.RefusedAboveAccount -> call.respondAboveAccount()
                is PushResult.RefusedLower -> call.respondLowerRefused()
                is PushResult.Stored, is PushResult.NoOp, is PushResult.Replaced ->
                    call.respond(HttpStatusCode.NoContent)
            }
        }

        get("/v1/transcriptions") {
            val accountId = call.sessionAccountOrRefuse(tokenStore) ?: return@get
            // The rendering is inside the try: a stored time that cannot be formatted is a failure of
            // the store's data, so it answers 503 like a store failure and never a 500.
            val json = try {
                renderTranscriptions(transcriptionStore.list(accountId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@get
            }
            call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}

// Returns the account of a live user session. Otherwise it writes the refusal and returns null.
private suspend fun ApplicationCall.sessionAccountOrRefuse(tokenStore: TokenStore): Long? {
    val presented = presentedBearerToken(request.headers[HttpHeaders.Authorization])
    if (presented == null) {
        respondSessionRequired()
        return null
    }
    val resolved: ResolvedToken? = try {
        tokenStore.resolve(presented)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        respondUnavailable()
        return null
    }
    if (resolved == null) {
        respondSessionRequired()
        return null
    }
    if (resolved.kind != TokenKind.SESSION) {
        respondAgentRefused()
        return null
    }
    return resolved.accountId
}

// Reads `Bearer <token>` the way the logout route does: the scheme is case-insensitive, the token
// is exactly what follows the single space, and an empty token or one with whitespace is no token.
private fun presentedBearerToken(header: String?): String? {
    if (header == null) return null
    val separator = header.indexOf(' ')
    if (separator <= 0) return null
    if (!header.substring(0, separator).equals(BEARER_SCHEME, ignoreCase = true)) return null
    val token = header.substring(separator + 1)
    if (token.isEmpty()) return null
    if (token.any { character -> character.isWhitespace() }) return null
    return token
}

private const val BEARER_SCHEME = "Bearer"

// The list as a JSON array. client_id and encrypted were checked when they were pushed (hex and
// hyphens, or the base64 alphabet), so neither holds a quote, a backslash or a control character.
private fun renderTranscriptions(rows: List<TranscriptionRow>): String {
    val items = rows.map { row ->
        "{\"client_id\":\"${row.clientId}\",\"key_version\":${row.keyVersion},\"encrypted\":\"${row.encrypted}\"," +
            "\"stored_at\":\"${formatStoredAt(row.storedAtMs)}\"}"
    }
    return items.joinToString(separator = ",", prefix = "[", postfix = "]")
}
