package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.handoff.HandoffStore
import dev.breaker.server.syncapi.handoff.RedeemResult
import dev.breaker.server.syncapi.handoff.StartResult
import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenKind
import dev.breaker.server.syncapi.tokens.TokenStore
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

private val CHALLENGE_PATTERN = "^[A-Za-z0-9_-]{43}$".toRegex()
private val SEALED_PACKAGE_PATTERN = "^[A-Za-z0-9+/]{151}=$".toRegex()
private val CODE_PATTERN = "^[A-Za-z0-9_-]{43}$".toRegex()
private val SECRET_PATTERN = "^[A-Za-z0-9_-]{43}$".toRegex()
private const val MAX_DEVICE_NAME_CODE_POINTS = 64
private val HEX4 = "^[0-9a-fA-F]{4}$".toRegex()

/** Installs POST /v1/auth/handoff/start and POST /v1/auth/handoff/exchange on this application. */
internal fun Application.authHandoffRoutes(handoffStore: HandoffStore, tokenStore: TokenStore) {
    routing {
        post("/v1/auth/handoff/start") {
            val presented = presentedBearerToken(call.request.headers[HttpHeaders.Authorization])
            if (presented == null) {
                call.respondSessionRequired()
                return@post
            }
            val resolved: ResolvedToken? = try {
                tokenStore.resolve(presented)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(
                    """{"error":"service_unavailable","message":"Store unavailable"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
            if (resolved == null) {
                call.respondSessionRequired()
                return@post
            }
            if (resolved.kind != TokenKind.SESSION) {
                call.respondAgentRefused()
                return@post
            }

            val body = call.receiveAuthBody() ?: return@post
            when (val result = parseStartRequest(body)) {
                is ParseResult.Refused -> {
                    call.respondBadRequest()
                    return@post
                }
                is ParseResult.Ok -> {
                    val challenge = result.fields["challenge"]!!
                    val sealedPackage = result.fields["sealed_package"]!!
                    if (!CHALLENGE_PATTERN.matches(challenge) || !SEALED_PACKAGE_PATTERN.matches(sealedPackage)) {
                        call.respondBadRequest()
                        return@post
                    }
                    val sealedBytes: ByteArray = try {
                        Base64.getDecoder().decode(sealedPackage)
                    } catch (_: IllegalArgumentException) {
                        call.respondBadRequest()
                        return@post
                    }
                    // The contract says the server checks only the length of the package: any pattern-valid
                    // text that decodes to 113 bytes is stored as those bytes, and the exchange answers with
                    // the canonical text of the stored bytes.
                    when (val start = handoffStore.start(resolved.accountId, challenge, sealedBytes)) {
                        is StartResult.Started -> {
                            call.respondText(
                                """{"code":"${start.code}"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.Created,
                            )
                        }
                        is StartResult.NoSuchAccount -> {
                            call.respondSessionRequired()
                        }
                    }
                }
            }
        }

        post("/v1/auth/handoff/exchange") {
            val body = call.receiveAuthBody() ?: return@post
            when (val result = parseExchangeRequest(body)) {
                is ParseResult.Refused -> {
                    call.respondBadRequest()
                    return@post
                }
                is ParseResult.Ok -> {
                    val fields = result.fields
                    val code = fields["code"]!!
                    val secret = fields["secret"]!!
                    val deviceName = fields["device_name"]
                    if (!CODE_PATTERN.matches(code) || !SECRET_PATTERN.matches(secret)) {
                        call.respondExchangeRefused()
                        return@post
                    }
                    if (deviceName != null && !isValidDeviceName(deviceName)) {
                        call.respondBadRequest()
                        return@post
                    }
                    when (val redeem = handoffStore.redeem(code, secret, deviceName)) {
                        is RedeemResult.Redeemed -> {
                            call.respondText(
                                buildExchangeResponse(redeem),
                                ContentType.Application.Json,
                                HttpStatusCode.OK,
                            )
                        }
                        is RedeemResult.Refused -> {
                            call.respondExchangeRefused()
                        }
                    }
                }
            }
        }
    }
}

/** The result of parsing a request body: [Refused] for any malformed input, [Ok] with the fields otherwise. */
private sealed class ParseResult {
    object Refused : ParseResult()
    data class Ok(val fields: Map<String, String?>) : ParseResult()
}

private suspend fun ApplicationCall.respondBadRequest() {
    respondText(
        """{"error":"bad_request","message":"Invalid request body"}""",
        ContentType.Application.Json,
        HttpStatusCode.BadRequest,
    )
}

private suspend fun ApplicationCall.respondSessionRequired() {
    respondText(
        """{"error":"unauthorized","message":"Invalid or missing token"}""",
        ContentType.Application.Json,
        HttpStatusCode.Unauthorized,
    )
}

private suspend fun ApplicationCall.respondAgentRefused() {
    respondText(
        """{"error":"forbidden","message":"User session token required; an agent token is refused"}""",
        ContentType.Application.Json,
        HttpStatusCode.Forbidden,
    )
}

private suspend fun ApplicationCall.respondExchangeRefused() {
    respondText(
        """{"error":"unauthorized","message":"Invalid sign-in code or secret"}""",
        ContentType.Application.Json,
        HttpStatusCode.Unauthorized,
    )
}

/** Reads the presented token out of an `Authorization` header: `Bearer <token>`, scheme case-insensitive, no whitespace in the token. */
private fun presentedBearerToken(header: String?): String? {
    if (header == null) return null
    val separator = header.indexOf(' ')
    if (separator <= 0) return null
    if (!header.substring(0, separator).equals("Bearer", ignoreCase = true)) return null
    val token = header.substring(separator + 1)
    if (token.isEmpty()) return null
    if (token.any { character -> character.isWhitespace() }) return null
    return token
}

private fun parseStartRequest(body: String): ParseResult {
    return parseObjectFields(body, required = listOf("challenge", "sealed_package"))?.let { fields ->
        if (fields["challenge"] == null || fields["sealed_package"] == null) ParseResult.Refused
        else ParseResult.Ok(fields)
    } ?: ParseResult.Refused
}

private fun parseExchangeRequest(body: String): ParseResult {
    return parseObjectFields(body, required = listOf("code", "secret"), optional = listOf("device_name"))?.let { fields ->
        if (fields["code"] == null || fields["secret"] == null) ParseResult.Refused
        else ParseResult.Ok(fields)
    } ?: ParseResult.Refused
}

/**
 * Parses a flat JSON object. Every value must be a JSON string or `null`.
 * Returns null on malformed JSON, missing required fields, wrong types, duplicate fields, or unknown fields.
 * Total: no input may throw.
 */
private fun parseObjectFields(
    body: String,
    required: List<String>,
    optional: List<String> = emptyList(),
): Map<String, String?>? {
    return try {
        parseObjectFieldsTotal(body, required, optional)
    } catch (_: Throwable) {
        null
    }
}

private fun parseObjectFieldsTotal(
    body: String,
    required: List<String>,
    optional: List<String>,
): Map<String, String?>? {
    val trimmed = body.trim()
    if (trimmed.length < 2 || trimmed[0] != '{' || trimmed[trimmed.length - 1] != '}') return null
    val inner = trimmed.substring(1, trimmed.length - 1).trim()
    if (inner.isEmpty()) {
        if (required.isEmpty()) return emptyMap() else return null
    }

    val result = mutableMapOf<String, String?>()
    var pos = 0
    var expectComma = false

    while (pos < inner.length) {
        while (pos < inner.length && inner[pos].isWhitespace()) pos++
        if (pos >= inner.length) break

        // Commas only between members, never leading, never double, never trailing.
        if (inner[pos] == ',') {
            if (!expectComma) return null
            pos++
            while (pos < inner.length && inner[pos].isWhitespace()) pos++
            if (pos >= inner.length) return null  // trailing comma
            expectComma = false
            continue
        }

        if (expectComma) return null  // no comma between members

        // Key must be a quoted string.
        if (inner[pos] != '"') return null
        val keyResult = parseJsonString(inner, pos) ?: return null
        val (key, keyEnd) = keyResult
        pos = keyEnd

        while (pos < inner.length && inner[pos].isWhitespace()) pos++
        if (pos >= inner.length || inner[pos] != ':') return null
        pos++

        while (pos < inner.length && inner[pos].isWhitespace()) pos++
        if (pos >= inner.length) return null  // no value after colon

        val value: String?
        when {
            inner[pos] == '"' -> {
                val valueResult = parseJsonString(inner, pos) ?: return null
                val (decoded, valueEnd) = valueResult
                pos = valueEnd
                value = decoded
            }
            inner.startsWith("null", pos) -> {
                value = null
                pos += 4
            }
            else -> return null  // wrong type: number, boolean, object, array
        }

        if (key in result) return null
        if (key !in required && key !in optional) return null
        result[key] = value
        expectComma = true
    }

    for (req in required) {
        if (req !in result) return null
    }
    return result.toMap()
}

/** Parses a JSON string starting at [start] (which must point to `"`). Returns the decoded string and the position after the closing quote, or null if malformed. */
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
                        if (!HEX4.matches(hex)) return null  // exactly 4 hex digits, no sign
                        val code = hex.toInt(16)
                        sb.append(code.toChar())
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

// Mirrors HandoffStore.isValidDeviceName (private there, duplicated by necessity): 1 to 64 code points, no ISO controls and no unpaired surrogates.
private fun isValidDeviceName(name: String): Boolean {
    val length = name.codePointCount(0, name.length)
    if (length < 1 || length > MAX_DEVICE_NAME_CODE_POINTS) return false
    return name.codePoints().noneMatch { codePoint: Int ->
        Character.isISOControl(codePoint) || codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
    }
}

private fun buildExchangeResponse(result: RedeemResult.Redeemed): String {
    return """{"token":"${result.sessionToken}","role":"${result.role.dbValue}","key_version":${result.keyVersion},"sealed_package":"${Base64.getEncoder().encodeToString(result.sealedPackage)}","username":"${result.username}","account_id":${result.accountId}}"""
}
