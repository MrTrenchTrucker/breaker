package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.AuthVerifier
import dev.breaker.server.syncapi.accounts.VerifyResult
import dev.breaker.server.syncapi.tokens.IssueResult
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.Base64

/**
 * Installs `POST /v1/auth/login` on this application.
 *
 * The route verifies the presented credentials and, on success, issues a session
 * token. The response carries the account's role and key material (null together
 * for a keyless account).
 *
 * Hand-written JSON — no serialization library, no api-contracts import.
 * Wire names match openapi.yaml exactly.
 */
internal fun Application.authLoginRoute(accountStore: AccountStore, tokenStore: TokenStore) {
    routing {
        post("/v1/auth/login") {
            val body = call.receiveAuthBody() ?: return@post
            val request = parseLoginRequest(body)
            if (request == null) {
                call.respondText(
                    """{"error":"bad_request","message":"Invalid request body"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val verifierBytes = try {
                Base64.getDecoder().decode(request.authVerifier)
            } catch (e: IllegalArgumentException) {
                null
            }
            val verifier = verifierBytes?.let { AuthVerifier.fromBytes(it) }
            if (verifier == null) {
                // Verifier is not valid base64 or not 32 bytes — bad credentials.
                call.respondText(
                    """{"error":"unauthorized","message":"Invalid username or verifier"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                return@post
            }

            when (val result = accountStore.verify(request.username, verifier)) {
                is VerifyResult.Verified -> {
                    when (val issued = tokenStore.issueSession(result.accountId)) {
                        is IssueResult.Issued -> {
                            call.respondText(
                                buildLoginResponse(result.role.dbValue, issued.token.secret.reveal()),
                                ContentType.Application.Json,
                                HttpStatusCode.OK,
                            )
                        }
                        else -> {
                            call.respondText(
                                """{"error":"unauthorized","message":"Invalid username or verifier"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.Unauthorized,
                            )
                        }
                    }
                }
                VerifyResult.Rejected -> {
                    call.respondText(
                        """{"error":"unauthorized","message":"Invalid username or verifier"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized,
                    )
                }
            }
        }
    }
}

private data class LoginRequest(val username: String, val authVerifier: String)

/**
 * Parses a login request body.
 *
 * Returns null unless the body is a valid JSON object with exactly two fields
 * "username" and "auth_verifier", both strings. Rejects malformed JSON, missing
 * fields, wrong types, and extra unknown fields.
 */
private fun parseLoginRequest(body: String): LoginRequest? {
    val trimmed = body.trim()
    if (trimmed.length < 2 || trimmed[0] != '{' || trimmed[trimmed.length - 1] != '}') {
        return null
    }

    val inner = trimmed.substring(1, trimmed.length - 1).trim()
    if (inner.isEmpty()) return null

    var pos = 0
    var username: String? = null
    var authVerifier: String? = null
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

        when (key) {
            "username" -> username = value
            "auth_verifier" -> authVerifier = value
            else -> return null  // Extra unknown field — reject
        }
    }

    if (fieldCount != 2) return null
    return LoginRequest(checkNotNull(username), checkNotNull(authVerifier))
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

/**
 * Builds the 200 login response JSON. Key material fields are null and
 * key_version is 0 because the account store does not expose key material
 * reading in this slice — keyless accounts have null keys and version 0.
 */
private fun buildLoginResponse(role: String, token: String): String {
    return """{"token":"$token","role":"$role","wrapped_dek":null,"wrapped_box_privkey":null,"box_pubkey":null,"key_version":0,"prev_wrapped_dek":null,"reset_at":null,"rekeyed_at":null}"""
}
