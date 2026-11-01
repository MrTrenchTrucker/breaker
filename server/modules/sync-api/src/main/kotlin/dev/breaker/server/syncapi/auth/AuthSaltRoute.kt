package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.SaltLookup
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.Base64

/**
 * Installs `POST /v1/auth/salt` on this application.
 *
 * The route looks up an account by username and returns its salt and KDF parameters.
 * No decoy: an unknown username gets an honest "no such account" (ADR-006).
 *
 * Hand-written JSON — no serialization library, no api-contracts import.
 * Wire names match openapi.yaml exactly.
 */
internal fun Application.authSaltRoute(store: AccountStore) {
    routing {
        post("/v1/auth/salt") {
            val body = call.receiveAuthBody() ?: return@post
            val username = extractUsername(body)
            if (username == null) {
                call.respondText(
                    """{"error":"bad_request","message":"Invalid request body"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            when (val result = store.saltFor(username)) {
                is SaltLookup.Found -> {
                    val saltB64 = Base64.getEncoder().encodeToString(result.salt)
                    val kdf = result.kdf
                    call.respondText(
                        """{"salt":"$saltB64","kdf_params":{"memory_kib":${kdf.memoryKib},"iterations":${kdf.iterations},"parallelism":${kdf.parallelism}},"kdf_version":${result.kdfVersion}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                }
                SaltLookup.NoSuchAccount -> {
                    call.respondText(
                        """{"error":"no_such_account","message":"No such account"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.NotFound,
                    )
                }
            }
        }
    }
}

/**
 * Extracts the username from a JSON request body.
 *
 * Returns null unless the body is a valid JSON object with exactly one field
 * "username" whose value is a string. Rejects malformed JSON, missing fields,
 * wrong types, and extra unknown fields.
 */
private fun extractUsername(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.length < 2 || trimmed[0] != '{' || trimmed[trimmed.length - 1] != '}') {
        return null
    }

    val inner = trimmed.substring(1, trimmed.length - 1).trim()
    if (inner.isEmpty()) return null

    var pos = 0
    var username: String? = null
    var fieldCount = 0

    while (pos < inner.length) {
        // Skip whitespace and commas between fields
        while (pos < inner.length && (inner[pos].isWhitespace() || inner[pos] == ',')) pos++
        if (pos >= inner.length) break

        // Key must be a JSON string
        if (inner[pos] != '"') return null
        val keyResult = parseJsonString(inner, pos) ?: return null
        val (key, keyEnd) = keyResult
        pos = keyEnd

        // Skip whitespace before colon
        while (pos < inner.length && inner[pos].isWhitespace()) pos++

        // Colon separates key from value
        if (pos >= inner.length || inner[pos] != ':') return null
        pos++

        // Skip whitespace after colon
        while (pos < inner.length && inner[pos].isWhitespace()) pos++

        // Value must be a JSON string (rejects numbers, booleans, null, objects, arrays)
        if (pos >= inner.length || inner[pos] != '"') return null
        val valueResult = parseJsonString(inner, pos) ?: return null
        val (value, valueEnd) = valueResult
        pos = valueEnd
        fieldCount++

        if (key == "username") {
            username = value
        } else {
            // Extra unknown field — reject
            return null
        }
    }

    return if (fieldCount == 1) username else null
}

/**
 * Parses a JSON string starting at [start] (which must point to `"`).
 * Returns the decoded string and the position after the closing quote,
 * or null if the string is malformed.
 */
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
