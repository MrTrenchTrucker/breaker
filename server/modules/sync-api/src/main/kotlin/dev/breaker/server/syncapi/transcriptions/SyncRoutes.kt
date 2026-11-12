package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.auth.enforceLimits
import dev.breaker.server.syncapi.auth.respondRateLimited
import dev.breaker.server.syncapi.escapeJsonString
import dev.breaker.server.syncapi.settings.ServerSettingsStore
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
// A pull with since sets the session's number; each pull purges the delete records all live sessions passed.
// A store failure other than cancellation answers 503 with a fixed body; cancellation is rethrown.
// POST /v1/sync is limited per account (ADR-041): right after the session check and before the body is read, one
// counter for the key acct:<account id> (route sync, 600 per 60 s, no global counter), under the same admin switch
// as the other limits. A switch or counter failure lets the request through; a cancellation is rethrown.
// Null counterStore or settings means no limit: the module passes both; a test of the route alone can leave them out.
internal const val SYNC_ROUTE = "sync"
internal const val SYNC_PER_ACCOUNT: Int = 600

internal fun Application.syncRoutes(
    tokenStore: TokenStore,
    transcriptionStore: TranscriptionStore,
    counterStore: RateLimitCounterStore? = null,
    settings: ServerSettingsStore? = null,
) {
    routing {
        post("/v1/sync") {
            val accountId = call.sessionAccountOrRefuse(tokenStore) ?: return@post
            if (counterStore != null && settings != null) {
                val refused = enforceLimits(
                    switchOn = { settings.rateLimitsEnabled() },
                    addressOf = { "acct:$accountId" },
                    checkPair = { key -> counterStore.check(SYNC_ROUTE, key, SYNC_PER_ACCOUNT) },
                )
                if (refused != null) {
                    respondRateLimited(call, refused)
                    return@post
                }
            }
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
            val resolved = call.sessionOrRefuse(tokenStore) ?: return@get
            val accountId = resolved.accountId
            val sinceValue = call.request.queryParameters["since"]
            val cursor = sinceValue?.let { FeedCursor.parse(it) }
            if (sinceValue != null && cursor == null) {
                call.respondCursorInvalid()
                return@get
            }
            // A cursor whose account is not the caller's is refused before the store is asked.
            if (cursor != null && cursor is FeedCursor.Incremental && cursor.accountId != accountId) {
                call.respondCursorInvalid()
                return@get
            }
            if (cursor is FeedCursor.Listing && cursor.accountId != accountId) {
                call.respondCursorInvalid()
                return@get
            }
            val since = when (cursor) {
                is FeedCursor.Incremental -> cursor.change
                is FeedCursor.Listing -> cursor.position
                null -> null
            }
            val listHead = when (cursor) {
                is FeedCursor.Listing -> cursor.head
                else -> null
            }
            val page = try {
                transcriptionStore.pull(tokenStore, accountId, resolved.tokenId, since, listHead)
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
                        renderTranscriptionsPage(accountId, page.records, page.next, page.more, page.listHead)
                    } catch (_: Exception) {
                        call.respondUnavailable()
                        return@get
                    }
                    call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
                }
            }
        }

        olderRoute(tokenStore, transcriptionStore, counterStore, settings)

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
    listHead: Long? = null,
): String {
    val items = records.map { row ->
        if (row.deleted) {
            "{\"client_id\":${escapeJsonString(row.clientId)},\"change\":${row.change}," +
                "\"deleted\":true,\"stored_at\":${escapeJsonString(formatInstantMs(row.storedAtMs))}}"
        } else {
            "{\"client_id\":${escapeJsonString(row.clientId)},\"change\":${row.change}," +
                "\"deleted\":false,\"key_version\":${row.keyVersion},\"encrypted\":${escapeJsonString(row.encrypted.toString())},\"stored_at\":" +
                "${escapeJsonString(formatInstantMs(row.storedAtMs))}}"
        }
    }
    val nextText = if (listHead != null) "$accountId-$next-$listHead" else "$accountId-$next"
    return "{\"items\":[${items.joinToString(",")}],\"next\":${escapeJsonString(nextText)},\"more\":$more}"
}
