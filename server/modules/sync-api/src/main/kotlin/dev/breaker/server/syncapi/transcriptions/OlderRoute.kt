package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.auth.enforceLimits
import dev.breaker.server.syncapi.auth.respondRateLimited
import dev.breaker.server.syncapi.escapeJsonString
import dev.breaker.server.syncapi.settings.ServerSettingsStore
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException

// GET /v1/transcriptions/older: one page of the account's own live rows strictly older than a bound,
// newest first. Read only, and session only: no or unknown token is 401, an agent token is 403.
// It writes nothing: no session number and no purge.
// The rate limit (ADR-041) is per account, right after the session check and before the bound is read,
// so a malformed bound counts. Under the same admin switch as the other limits. No global counter.

internal const val OLDER_ROUTE = "older"

/**
 * Requests one account may make to the older read in one fixed 60 second window. A guess chosen from
 * the cost of one read and one answer: NOT measured.
 */
internal const val OLDER_PER_ACCOUNT: Int = 30

/** The fixed 400 text when older_than is missing, empty or does not parse. */
internal const val MESSAGE_OLDER_BOUND_INVALID: String =
    "older_than must be a stored time in milliseconds, or that time and a change number joined by a dash"

internal fun Route.olderRoute(
    tokenStore: TokenStore,
    transcriptionStore: TranscriptionStore,
    counterStore: RateLimitCounterStore?,
    settings: ServerSettingsStore?,
) {
    get("/v1/transcriptions/older") {
        val accountId = call.sessionAccountOrRefuse(tokenStore) ?: return@get
        if (counterStore != null && settings != null) {
            val refused = enforceLimits(
                switchOn = { settings.rateLimitsEnabled() },
                addressOf = { "acct:$accountId" },
                checkPair = { key -> counterStore.check(OLDER_ROUTE, key, OLDER_PER_ACCOUNT) },
            )
            if (refused != null) {
                respondRateLimited(call, refused)
                return@get
            }
        }
        val bound = parseOlderBound(call.request.queryParameters["older_than"])
        if (bound == null) {
            call.respondBadRequest(MESSAGE_OLDER_BOUND_INVALID)
            return@get
        }
        val page = try {
            transcriptionStore.older(accountId, bound)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            call.respondUnavailable()
            return@get
        }
        val json = try {
            renderOlderPage(page, bound)
        } catch (_: Exception) {
            call.respondUnavailable()
            return@get
        }
        call.respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
    }
}

// The page object JSON: {items, next, more}. Each of the items has the live record shape of the list
// route. next is the last row's stored time and change joined by a dash, or the request's own bound
// when the page is empty.
private fun renderOlderPage(page: OlderPage, bound: OlderBound): String {
    val items = page.records.map { row ->
        "{\"client_id\":${escapeJsonString(row.clientId)},\"change\":${row.change}," +
            "\"deleted\":false,\"key_version\":${row.keyVersion}," +
            "\"encrypted\":${escapeJsonString(row.encrypted.toString())},\"stored_at\":" +
            "${escapeJsonString(formatInstantMs(row.storedAtMs))}}"
    }
    val last = page.records.lastOrNull()
    val next = if (last != null) "${last.storedAtMs}-${last.change}" else bound.render()
    return "{\"items\":[${items.joinToString(",")}],\"next\":${escapeJsonString(next)},\"more\":${page.more}}"
}
