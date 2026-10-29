package dev.breaker.server.syncapi.sync

import dev.breaker.server.syncapi.auth.SessionCheck
import dev.breaker.server.syncapi.auth.checkSessionBearer
import dev.breaker.server.syncapi.auth.respondSessionRefusal
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Installs `GET /v1/transcriptions`: the account's own rows, oldest first, as a JSON array of
 * `client_id`, `key_version`, `encrypted` and `stored_at` (RFC 3339, UTC, whole seconds).
 */
internal fun Route.transcriptionsPullRoute(tokenStore: TokenStore, transcriptionStore: TranscriptionStore) {
    get("/v1/transcriptions") {
        val session = checkSessionBearer(call.request.headers[HttpHeaders.Authorization], tokenStore)
        if (session !is SessionCheck.Live) {
            call.respondSessionRefusal(session)
            return@get
        }
        val rows = transcriptionStore.list(session.token.accountId)
        val body = StringBuilder()
        body.append('[')
        rows.forEachIndexed { index, row ->
            if (index > 0) body.append(',')
            body.append("{\"client_id\":")
            appendJsonString(body, row.clientId)
            body.append(",\"key_version\":").append(row.keyVersion)
            body.append(",\"encrypted\":")
            appendJsonString(body, row.encrypted)
            body.append(",\"stored_at\":")
            appendJsonString(body, storedAtText(row.storedAtMs))
            body.append('}')
        }
        body.append(']')
        call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

/** The stored time as RFC 3339 UTC in whole seconds: the fraction is cut off, never rounded. */
internal fun storedAtText(epochMs: Long): String = Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()
