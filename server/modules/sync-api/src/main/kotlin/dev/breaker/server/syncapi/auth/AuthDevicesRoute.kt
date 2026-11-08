package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.tokens.DeviceSession
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.formatInstantMs
import dev.breaker.server.syncapi.transcriptions.sessionOrRefuse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Installs `GET /v1/auth/devices` on this application.
 *
 * The answer lists the caller's live sessions, oldest first, at most 50 rows. Each row holds the
 * token id, the stored device name or null, the last-seen text and whether the row is the token
 * that was presented. A missing or dead token answers 401, an agent token answers 403 and a store
 * failure answers 503, each with the module's fixed body; nothing else is written.
 *
 * Hand-written JSON: no serialization library, no api-contracts import. A private escaper writes
 * the name so a quote, a backslash, a newline or an emoji cannot break the body.
 */
internal fun Application.authDevicesRoute(tokenStore: TokenStore) {
    routing {
        get("/v1/auth/devices") {
            val resolved = call.sessionOrRefuse(tokenStore) ?: return@get
            val sessions = when (val guarded = call.guardedStoreCall {
                tokenStore.deviceSessions(resolved.accountId)
            }) {
                is StoreCall.Done -> guarded.value
                StoreCall.Failed -> return@get
            }
            val body = renderDeviceList(sessions, resolved.tokenId)
            call.respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}

private fun renderDeviceList(sessions: List<DeviceSession>, currentTokenId: Long): String {
    val out = StringBuilder()
    out.append("{\"devices\":[")
    for (i in sessions.indices.reversed()) {
        if (i < sessions.size - 1) out.append(",")
        val row = sessions[i]
        out.append("{\"id\":")
        out.append(row.id)
        out.append(",\"name\":")
        if (row.name == null) {
            out.append("null")
        } else {
            writeJsonString(out, row.name)
        }
        out.append(",\"last_seen\":")
        writeJsonString(out, formatInstantMs(row.lastSeenMs))
        out.append(",\"current\":")
        out.append(row.id == currentTokenId)
        out.append("}")
    }
    out.append("]}")
    return out.toString()
}

private fun writeJsonString(out: StringBuilder, value: String) {
    out.append('"')
    for (ch in value) {
        when (ch.code) {
            0x22 -> out.append("\\\"")
            0x5C -> out.append("\\\\")
            0x08 -> out.append("\\b")
            0x0C -> out.append("\\f")
            0x0A -> out.append("\\n")
            0x0D -> out.append("\\r")
            0x09 -> out.append("\\t")
            in 0x00..0x1F -> {
                out.append("\\u00")
                out.append(HEX_DIGITS[(ch.code ushr 4) and 0xF])
                out.append(HEX_DIGITS[ch.code and 0xF])
            }
            else -> out.append(ch)
        }
    }
    out.append('"')
}

private const val HEX_DIGITS = "0123456789abcdef"
