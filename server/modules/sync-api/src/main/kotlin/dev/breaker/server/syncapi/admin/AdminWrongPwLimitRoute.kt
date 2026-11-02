package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.auth.receiveAuthBody
import dev.breaker.server.syncapi.settings.ServerSettingsStore
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

/** The exact 400 error body from openapi.yaml for /v1/admin/wrong-pw-limit PUT. */
internal const val WRONG_PW_LIMIT_BAD_REQUEST =
    """{"error":"bad_request","message":"enabled must be true or false"}"""

/**
 * Installs `GET /v1/admin/wrong-pw-limit` and `PUT /v1/admin/wrong-pw-limit` on this
 * application.
 *
 * GET reads the live switch and answers `{"enabled":true}` or `{"enabled":false}`.
 * PUT accepts exactly one JSON member `enabled` whose value is the literal `true` or
 * `false`, flips the switch, and answers with the read-back stored state.
 *
 * Both methods answer 401 before 403 before any body work. A database failure is not
 * caught (no 503 on the admin routes).
 *
 * Hand-written JSON - no serialization library, no api-contracts import. Reuses
 * `bearerToken` and `isAdmin` from AdminRevokeAgentTokenRoute in the same package.
 */
internal fun Application.adminWrongPwLimitRoute(tokenStore: TokenStore, settings: ServerSettingsStore) {
    routing {
        get("/v1/admin/wrong-pw-limit") {
            val token = bearerToken(call.request.header("Authorization"))
            val resolved = tokenStore.resolve(token)
            if (resolved == null) {
                call.respondText(UNAUTHORIZED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            if (!isAdmin(resolved)) {
                call.respondText(FORBIDDEN_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }
            val enabled = settings.wrongPwLimitEnabled()
            call.respondText("""{"enabled":$enabled}""", ContentType.Application.Json, HttpStatusCode.OK)
        }

        put("/v1/admin/wrong-pw-limit") {
            val token = bearerToken(call.request.header("Authorization"))
            val resolved = tokenStore.resolve(token)
            if (resolved == null) {
                call.respondText(UNAUTHORIZED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@put
            }
            if (!isAdmin(resolved)) {
                call.respondText(FORBIDDEN_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@put
            }
            val body = call.receiveAuthBody() ?: return@put
            val enabled = parseWrongPwLimitBody(body)
            if (enabled == null) {
                call.respondText(WRONG_PW_LIMIT_BAD_REQUEST, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@put
            }
            settings.setWrongPwLimitEnabled(enabled)
            val stored = settings.wrongPwLimitEnabled()
            call.respondText("""{"enabled":$stored}""", ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}

/**
 * Parses a wrong-pw-limit PUT body strictly.
 *
 * Accepts only `{"enabled":true}` or `{"enabled":false}` (whitespace between tokens
 * allowed). Rejects empty body, non-JSON, array or string at top level, `{}`, a string
 * `"true"`, a number, `null`, an extra member, a repeated `enabled` member, or trailing
 * text after the object. Returns null on any refusal.
 */
private fun parseWrongPwLimitBody(body: String): Boolean? {
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
