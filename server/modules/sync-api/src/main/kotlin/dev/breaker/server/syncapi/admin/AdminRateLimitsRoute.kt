package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.auth.receiveAuthBody
import dev.breaker.server.syncapi.settings.ServerSettingsStore
import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

/** The exact 400 error body from openapi.yaml for /v1/admin/rate-limits PUT. */
private const val RATE_LIMITS_BAD_REQUEST =
    """{"error":"bad_request","message":"enabled must be true or false"}"""

/**
 * Installs `GET /v1/admin/rate-limits` and `PUT /v1/admin/rate-limits` on this
 * application — ONE switch for all the rate limits, mirroring
 * /v1/admin/wrong-pw-limit exactly.
 *
 * GET reads the live switch and answers `{"enabled":true}` or `{"enabled":false}`.
 * PUT accepts exactly one JSON member `enabled` whose value is the literal `true` or
 * `false`, flips the switch, and answers with the read-back stored state.
 *
 * Both methods answer 401 before 403 before any body work. A database failure (the
 * closed-database IllegalStateException, a SQL error) answers 503 Service Unavailable;
 * a cancellation is rethrown.
 *
 * Hand-written JSON - no serialization library, no api-contracts import. Reuses
 * `bearerToken`, `isAdmin`, `SERVICE_UNAVAILABLE_BODY`, `UNAUTHORIZED_BODY` and
 * `FORBIDDEN_BODY` from AdminWrongPwLimitRoute/AdminRevokeAgentTokenRoute in the same
 * package.
 *
 * Source for the numbers (constants, not editable here): ADR-041
 * (register 10/60s + global 120/60s; salt 60/60s + global 1200/60s; fixed 60 s window;
 * default ON).
 */
internal fun Application.adminRateLimitsRoute(tokenStore: TokenStore, settings: ServerSettingsStore) {
    routing {
        get("/v1/admin/rate-limits") {
            val token = bearerToken(call.request.header("Authorization"))
            val resolved: ResolvedToken? = try {
                tokenStore.resolve(token)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@get
            }
            if (resolved == null) {
                call.respondText(UNAUTHORIZED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            if (!isAdmin(resolved)) {
                call.respondText(FORBIDDEN_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }
            val enabled: Boolean = try {
                settings.rateLimitsEnabled()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respondText("""{"enabled":$enabled}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        put("/v1/admin/rate-limits") {
            val token = bearerToken(call.request.header("Authorization"))
            val resolved: ResolvedToken? = try {
                tokenStore.resolve(token)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@put
            }
            if (resolved == null) {
                call.respondText(UNAUTHORIZED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@put
            }
            if (!isAdmin(resolved)) {
                call.respondText(FORBIDDEN_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@put
            }
            val body = call.receiveAuthBody() ?: return@put
            val enabled = parseStrictEnabledBody(body)
            if (enabled == null) {
                call.respondText(RATE_LIMITS_BAD_REQUEST, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@put
            }
            try {
                settings.setRateLimitsEnabled(enabled)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@put
            }
            val stored: Boolean = try {
                settings.rateLimitsEnabled()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@put
            }
            call.respondText("""{"enabled":$stored}""", ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}

/** Parses a rate-limits PUT body strictly: exactly {"enabled":true} or {"enabled":false}. */
private fun parseStrictEnabledBody(body: String): Boolean? {
    val trimmed = body.trim { isJsonSpace(it) }
    if (trimmed.length < 2 || trimmed[0] != '{' || trimmed[trimmed.length - 1] != '}') return null
    val inner = trimmed.substring(1, trimmed.length - 1).trim { isJsonSpace(it) }
    if (inner.isEmpty()) return null

    var pos = 0
    var fieldCount = 0
    var enabledValue: Boolean? = null

    while (true) {
        while (pos < inner.length && isJsonSpace(inner[pos])) pos++
        if (pos >= inner.length) break

        if (inner[pos] != '"') return null
        val keyResult = parseJsonString(inner, pos) ?: return null
        val keyText = keyResult.first
        pos = keyResult.second

        while (pos < inner.length && isJsonSpace(inner[pos])) pos++
        if (pos >= inner.length || inner[pos] != ':') return null
        pos++

        while (pos < inner.length && isJsonSpace(inner[pos])) pos++
        if (pos >= inner.length) return null

        if (keyText != "enabled") return null
        if (enabledValue != null) return null

        val valueChar = inner[pos]
        when {
            valueChar == 't' -> {
                if (pos + 4 > inner.length || inner.substring(pos, pos + 4) != "true") return null
                pos += 4
                enabledValue = true
            }
            valueChar == 'f' -> {
                if (pos + 5 > inner.length || inner.substring(pos, pos + 5) != "false") return null
                pos += 5
                enabledValue = false
            }
            else -> return null
        }
        fieldCount++

        while (pos < inner.length && isJsonSpace(inner[pos])) pos++
        if (pos >= inner.length) break
        if (inner[pos] != ',') return null
        pos++
        while (pos < inner.length && isJsonSpace(inner[pos])) pos++
        if (pos >= inner.length) return null
    }

    if (fieldCount != 1 || enabledValue == null) return null
    return enabledValue
}

/** The four characters JSON allows between tokens (not every Unicode space). */
private fun isJsonSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'

private fun parseJsonString(text: String, start: Int): Pair<String, Int>? {
    if (start >= text.length || text[start] != '"') return null
    val sb = StringBuilder()
    var pos = start + 1
    while (pos < text.length) {
        val ch = text[pos]
        when {
            ch == '"' -> return Pair(sb.toString(), pos + 1)
            ch == '\\' -> {
                pos++
                if (pos >= text.length) return null
                when (text[pos]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 >= text.length) return null
                        val hex = text.substring(pos + 1, pos + 5)
                        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                        sb.append(hex.toInt(16).toChar())
                        pos += 4
                    }
                    else -> return null
                }
                pos++
            }
            else -> {
                sb.append(ch)
                pos++
            }
        }
    }
    return null
}
