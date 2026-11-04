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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

// POST /v1/sync (push one transcription) and GET /v1/transcriptions (the account's feed, oldest
// first, paged by since). Both take a user session token only: no or unknown token is 401, an agent token is 403.
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
                transcriptionStore.push(accountId, body.clientId, body.keyVersion, body.encrypted, body.resave)
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
            val sinceValue = call.request.queryParameters["since"]
            val parsed = sinceValue?.let { parseSince(it) }
            if (sinceValue != null && parsed == null) {
                call.respondCursorInvalid()
                return@get
            }
            val since = parsed?.second
            if (since != null && parsed!!.first != accountId) {
                call.respondCursorInvalid()
                return@get
            }
            val page = try {
                transcriptionStore.page(accountId, since)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@get
            }
            when (page) {
                is PageResult.Gone -> {
                    call.respondGone()
                    return@get
                }
                is PageResult.Page -> {
                    val json = try {
                        renderTranscriptionsPage(accountId, page.records, page.next, page.more)
                    } catch (_: Exception) {
                        call.respondUnavailable()
                        return@get
                    }
                    call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
                }
            }
        }

        delete("/v1/transcriptions/{client_id}") {
            val accountId = call.sessionAccountOrRefuse(tokenStore) ?: return@delete
            val clientId = call.parameters["client_id"] ?: ""
            if (!isCanonicalUuid(clientId)) {
                call.respondClientIdRefused()
                return@delete
            }
            val outcome = try {
                transcriptionStore.delete(accountId, clientId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@delete
            }
            when (outcome) {
                is DeleteResult.Deleted, is DeleteResult.NoOp ->
                    call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

// Returns the account of a live user session. Otherwise it writes the refusal and returns null.
internal suspend fun ApplicationCall.sessionAccountOrRefuse(tokenStore: TokenStore): Long? {
    return sessionOrRefuse(tokenStore)?.accountId
}

// Returns the resolved token of a live user session. Otherwise it writes the refusal and returns null.
internal suspend fun ApplicationCall.sessionOrRefuse(tokenStore: TokenStore): ResolvedToken? {
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
    return resolved
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

// The page object JSON: {items, next, more}. A live record renders key_version and encrypted;
// a delete record renders neither. [accountId] is the session's account id in decimal.
private fun renderTranscriptionsPage(
    accountId: Long,
    records: List<TranscriptionPageRecord>,
    next: Long,
    more: Boolean,
): String {
    val items = records.map { row ->
        if (row.deleted) {
            "{\"client_id\":\"${row.clientId}\",\"change\":${row.change},\"deleted\":true,\"stored_at\":\"${formatStoredAt(row.storedAtMs)}\"}"
        } else {
            "{\"client_id\":\"${row.clientId}\",\"change\":${row.change},\"deleted\":false,\"key_version\":${row.keyVersion},\"encrypted\":\"${row.encrypted}\",\"stored_at\":\"${formatStoredAt(row.storedAtMs)}\"}"
        }
    }
    return "{\"items\":[${items.joinToString(",")}],\"next\":\"$accountId-$next\",\"more\":$more}"
}

private val CURSOR_PATTERN = Regex("^[0-9]{1,19}-[0-9]{1,19}$")

// Parses the since query parameter: <account>-<change>. Returns the pair when the value matches
// the pattern and both parts fit Longs; null otherwise (absent or malformed).
private fun parseSince(value: String): Pair<Long, Long>? {
    if (!CURSOR_PATTERN.matches(value)) return null
    val hyphen = value.indexOf('-')
    val accountPart = value.substring(0, hyphen)
    // The account part is the decimal of the account id: no leading zero, so one account has one text.
    if (accountPart.length > 1 && accountPart.startsWith("0")) return null
    val changePart = value.substring(hyphen + 1)
    return accountPart.toLongOrNull()?.let { account -> changePart.toLongOrNull()?.let { change -> account to change } }
}
