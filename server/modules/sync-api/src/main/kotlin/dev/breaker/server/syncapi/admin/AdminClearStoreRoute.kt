package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.UsernameRules
import dev.breaker.server.syncapi.auth.receiveAuthBody
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import dev.breaker.server.syncapi.transcriptions.respondBadRequest
import dev.breaker.server.syncapi.transcriptions.respondUnavailable
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

// The exact 404 body from openapi.yaml for an account that does not exist.
private const val ACCOUNT_NOT_FOUND_BODY =
    """{"error":"not_found","message":"Account not found"}"""

// The exact 400 body for confirm that is false or absent.
private const val CONFIRM_MUST_BE_TRUE_BODY =
    """{"error":"bad_request","message":"confirm must be true"}"""

/** The parsed result of a clear-store body. */
private sealed class ClearBodyResult {
    object ConfirmMissing : ClearBodyResult()  // confirm key absent -> contract 400
    object ShapeError : ClearBodyResult()      // wrong types / extra members -> respondBadRequest 400
    data class Ok(val username: String?, val confirm: Boolean) : ClearBodyResult()
}

/** Parses a clear-store body strictly: exactly one JSON object whose members are `username`
 * (a JSON string or null, optional) and `confirm` (the JSON literal true or false, required).
 * A string "true", a number, null or an object for confirm is a shape error. Returns
 * ConfirmMissing when `confirm` is absent (the contract treats absent as false). */
private fun parseClearBody(body: String): ClearBodyResult {
    val trimmed = body.trim()
    if (trimmed.length < 2 || trimmed[0] != '{' || trimmed[trimmed.length - 1] != '}') {
        return ClearBodyResult.ShapeError
    }
    val inner = trimmed.substring(1, trimmed.length - 1).trim()
    if (inner.isEmpty()) return ClearBodyResult.ConfirmMissing

    var pos = 0
    var expectComma = false
    var seenUsername = false
    var seenConfirm = false
    var usernameValue: String? = null
    var confirmValue: Boolean? = null

    while (pos < inner.length) {
        while (pos < inner.length && inner[pos].isWhitespace()) pos++
        if (pos >= inner.length) break

        if (inner[pos] == ',') {
            if (!expectComma) return ClearBodyResult.ShapeError
            pos++
            while (pos < inner.length && inner[pos].isWhitespace()) pos++
            if (pos >= inner.length) return ClearBodyResult.ShapeError  // trailing comma
            expectComma = false
            continue
        }
        if (expectComma) return ClearBodyResult.ShapeError  // no comma between members

        // Key must be a quoted string.
        if (inner[pos] != '"') return ClearBodyResult.ShapeError
        val keyResult = parseClearJsonString(inner, pos) ?: return ClearBodyResult.ShapeError
        val (key, keyEnd) = keyResult
        pos = keyEnd

        while (pos < inner.length && inner[pos].isWhitespace()) pos++
        if (pos >= inner.length || inner[pos] != ':') return ClearBodyResult.ShapeError
        pos++

        while (pos < inner.length && inner[pos].isWhitespace()) pos++
        if (pos >= inner.length) return ClearBodyResult.ShapeError  // no value after colon

        // Key must be known and not duplicated.
        if (key != "username" && key != "confirm") return ClearBodyResult.ShapeError
        if (key == "username" && seenUsername) return ClearBodyResult.ShapeError
        if (key == "confirm" && seenConfirm) return ClearBodyResult.ShapeError

        when {
            inner[pos] == '"' -> {
                if (key == "confirm") return ClearBodyResult.ShapeError  // confirm must be boolean
                val valueResult = parseClearJsonString(inner, pos) ?: return ClearBodyResult.ShapeError
                val (decoded, valueEnd) = valueResult
                pos = valueEnd
                seenUsername = true
                usernameValue = decoded
            }
            inner.startsWith("null", pos) -> {
                if (key == "confirm") return ClearBodyResult.ShapeError  // confirm must be boolean
                val afterNull = pos + 4
                if (afterNull < inner.length && inner[afterNull] != ',' && !inner[afterNull].isWhitespace() && inner[afterNull] != '}') {
                    return ClearBodyResult.ShapeError
                }
                seenUsername = true
                usernameValue = null
                pos = afterNull
            }
            inner.startsWith("true", pos) -> {
                if (key == "username") return ClearBodyResult.ShapeError  // username must be string/null
                val afterTrue = pos + 4
                if (afterTrue < inner.length && inner[afterTrue] != ',' && !inner[afterTrue].isWhitespace() && inner[afterTrue] != '}') {
                    return ClearBodyResult.ShapeError
                }
                seenConfirm = true
                confirmValue = true
                pos = afterTrue
            }
            inner.startsWith("false", pos) -> {
                if (key == "username") return ClearBodyResult.ShapeError
                val afterFalse = pos + 5
                if (afterFalse < inner.length && inner[afterFalse] != ',' && !inner[afterFalse].isWhitespace() && inner[afterFalse] != '}') {
                    return ClearBodyResult.ShapeError
                }
                seenConfirm = true
                confirmValue = false
                pos = afterFalse
            }
            else -> return ClearBodyResult.ShapeError  // number, object, array
        }

        expectComma = true
    }

    if (!seenConfirm) return ClearBodyResult.ConfirmMissing
    return ClearBodyResult.Ok(usernameValue, confirmValue!!)
}

/** Parses a JSON string starting at [start] (which must point to `"`). Returns the decoded
 * string and the position after the closing quote, or null if malformed. */
private fun parseClearJsonString(text: String, start: Int): Pair<String, Int>? {
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

/**
 * Installs `POST /v1/admin/clear-store` on this application.
 *
 * An admin (a session, or an agent token with the admin scope) clears transcriptions per-user
 * (a `username` member) or for all users (`username` null or omitted). `confirm` must be true or
 * the call is refused 400. Every live row of each target account gets one delete record: a new
 * change number per row, body cleared, `deleted` set, stored time = now; rows already deleted are
 * untouched and take no change number. The call answers 200 with `{"cleared":N}`, the total rows
 * marked. For all users the clear runs one transaction per account: a failure on one account
 * answers 503 and leaves the accounts already cleared cleared.
 *
 * The route checks, in this order: the caller (401 for any dead, missing or malformed token), the
 * admin role (403), the body size cap (413), the body shape (exactly `username` string-or-null
 * optional and `confirm` boolean required: 400), `confirm` must be true (400), the name rules and
 * the account (404 for both), then the write. A store failure answers the fixed 503
 * (`respondUnavailable`, no exception text in a body); a cancellation is rethrown. Nothing else is
 * touched: no session or token revoked, no cursor touched.
 */
internal fun Application.adminClearStoreRoute(
    tokenStore: TokenStore,
    accountStore: AccountStore,
    transcriptionStore: TranscriptionStore,
) {
    routing {
        post("/v1/admin/clear-store") {
            val presented = bearerToken(call.request.header("Authorization"))
            val resolved = try {
                tokenStore.resolve(presented)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }
            if (resolved == null) {
                call.respondText(UNAUTHORIZED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            if (!isAdmin(resolved)) {
                call.respondText(FORBIDDEN_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }

            val body = call.receiveAuthBody() ?: return@post
            when (val parsed = parseClearBody(body)) {
                ClearBodyResult.ConfirmMissing -> {
                    call.respondText(CONFIRM_MUST_BE_TRUE_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                ClearBodyResult.ShapeError -> {
                    call.respondBadRequest("The body is not exactly one JSON object with the members username and confirm")
                    return@post
                }
                is ClearBodyResult.Ok -> {
                    if (!parsed.confirm) {
                        call.respondText(CONFIRM_MUST_BE_TRUE_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val totalCleared = if (parsed.username != null) {
                        if (!UsernameRules.isValid(parsed.username)) {
                            call.respondText(ACCOUNT_NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
                            return@post
                        }
                        val folded = UsernameRules.fold(parsed.username)
                        val accountId = try {
                            accountStore.accountIdOfFolded(folded)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            call.respondUnavailable()
                            return@post
                        }
                        if (accountId == null) {
                            call.respondText(ACCOUNT_NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
                            return@post
                        }
                        try {
                            transcriptionStore.clearAll(accountId)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            call.respondUnavailable()
                            return@post
                        }
                    } else {
                        val ids = try {
                            accountStore.accountIds()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            call.respondUnavailable()
                            return@post
                        }
                        var total = 0
                        for (id in ids) {
                            total += try {
                                transcriptionStore.clearAll(id)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                call.respondUnavailable()
                                return@post
                            }
                        }
                        total
                    }
                    call.respondText("""{"cleared":$totalCleared}""", ContentType.Application.Json, HttpStatusCode.OK)
                }
            }
        }
    }
}
