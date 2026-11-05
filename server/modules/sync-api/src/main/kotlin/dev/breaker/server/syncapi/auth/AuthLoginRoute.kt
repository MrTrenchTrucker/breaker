package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.AuthVerifier
import dev.breaker.server.syncapi.accounts.VerifyResult
import dev.breaker.server.syncapi.tokens.IssueResult
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.respondUnavailable
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
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

            // A store failure in verify, keysFor or issueSession answers the fixed 503, never a 500.
            val result = try {
                accountStore.verify(request.username, verifier)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }
            when (result) {
                is VerifyResult.Verified -> {
                    val keys = try {
                        accountStore.keysFor(result.accountId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        call.respondUnavailable()
                        return@post
                    }
                    if (keys == null) {
                        call.respondText(
                            """{"error":"unauthorized","message":"Invalid username or verifier"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized,
                        )
                        return@post
                    }
                    val issued = try {
                        tokenStore.issueSession(result.accountId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        call.respondUnavailable()
                        return@post
                    }
                    // Any result but Issued, ResetPending included (a reset landed between the verify and the
                    // insert), gets the fixed 401 of a wrong verifier. No wrong try is counted for it.
                    when (issued) {
                        is IssueResult.Issued -> {
                            call.respondText(
                                buildLoginResponse(result.role.dbValue, issued.token.secret.reveal(), keys),
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
                is VerifyResult.Locked -> {
                    call.respondLocked(result.secondsRemaining)
                }
            }
        }
    }
}

/**
 * Answers 423 for a locked name: the Retry-After header in whole seconds and a body that says the
 * wait in whole minutes, at least one. Login and change-password both answer a locked name with it.
 */
internal suspend fun ApplicationCall.respondLocked(secondsRemaining: Long) {
    response.headers.append(HttpHeaders.RetryAfter, secondsRemaining.toString())
    val minutes = ((secondsRemaining + 59) / 60).coerceAtLeast(1)
    respondText(
        """{"error":"locked","message":"Too many wrong tries. Try again in $minutes minutes."}""",
        ContentType.Application.Json,
        HttpStatusCode.Locked,
    )
}

internal data class LoginRequest(
    val username: String,
    val authVerifier: String,
) {
    override fun toString(): String =
        "LoginRequest(username=$username, authVerifier=<redacted>)"
}

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
