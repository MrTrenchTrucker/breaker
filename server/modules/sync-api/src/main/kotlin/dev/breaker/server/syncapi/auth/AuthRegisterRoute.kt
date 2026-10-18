package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.AuthVerifier
import dev.breaker.server.syncapi.accounts.KdfParams
import dev.breaker.server.syncapi.accounts.RegisterResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.Base64

internal fun Route.authRegisterRoute(store: AccountStore) {
    post("/v1/auth/register") {
        val body = call.receiveText()
        val response = handleRegisterBody(store, body)
        call.respondText(response.body, ContentType.Application.Json, response.status)
    }
}

internal sealed class RegisterResponse {
    abstract val status: HttpStatusCode
    abstract val body: String

    data class Created(val username: String, val role: String) : RegisterResponse() {
        override val status: HttpStatusCode = HttpStatusCode.Created
        override val body: String = """{"username":"$username","role":"$role"}"""
    }

    data class BadRequest(val message: String) : RegisterResponse() {
        override val status: HttpStatusCode = HttpStatusCode.BadRequest
        override val body: String = """{"error":"invalid_field","message":"$message"}"""
    }

    object Conflict : RegisterResponse() {
        override val status: HttpStatusCode = HttpStatusCode.Conflict
        override val body: String = """{"error":"username_taken","message":"Username already registered"}"""
    }
}

private suspend fun handleRegisterBody(store: AccountStore, body: String): RegisterResponse {
    val json = try {
        parseJson(body)
    } catch (_: JsonParseException) {
        return RegisterResponse.BadRequest("malformed JSON body")
    }

    if (json !is Json.Obj) {
        return RegisterResponse.BadRequest("request body must be a JSON object")
    }

    val expectedKeys = setOf("username", "salt", "kdf_params", "kdf_version", "auth_verifier")
    val actualKeys = json.entries.keys
    if (actualKeys.size != expectedKeys.size || !actualKeys.containsAll(expectedKeys)) {
        val extra = actualKeys - expectedKeys
        val missing = expectedKeys - actualKeys
        if (extra.isNotEmpty()) {
            return RegisterResponse.BadRequest("unknown field: ${extra.first()}")
        }
        return RegisterResponse.BadRequest("missing field: ${missing.first()}")
    }

    val username = when (val v = json.entries["username"]) {
        is Json.Str -> v.value
        null -> return RegisterResponse.BadRequest("missing field: username")
        else -> return RegisterResponse.BadRequest("username must be a string")
    }

    val saltStr = when (val v = json.entries["salt"]) {
        is Json.Str -> v.value
        null -> return RegisterResponse.BadRequest("missing field: salt")
        else -> return RegisterResponse.BadRequest("salt must be a string")
    }

    val kdfVersion = when (val v = json.entries["kdf_version"]) {
        is Json.Num -> v.value.toInt()
        null -> return RegisterResponse.BadRequest("missing field: kdf_version")
        else -> return RegisterResponse.BadRequest("kdf_version must be an integer")
    }

    val verifierStr = when (val v = json.entries["auth_verifier"]) {
        is Json.Str -> v.value
        null -> return RegisterResponse.BadRequest("missing field: auth_verifier")
        else -> return RegisterResponse.BadRequest("auth_verifier must be a string")
    }

    val kdfParamsJson = json.entries["kdf_params"]
    if (kdfParamsJson == null) {
        return RegisterResponse.BadRequest("missing field: kdf_params")
    }
    if (kdfParamsJson !is Json.Obj) {
        return RegisterResponse.BadRequest("kdf_params must be an object")
    }
    val kdfExpectedKeys = setOf("memory_kib", "iterations", "parallelism")
    val kdfActualKeys = kdfParamsJson.entries.keys
    if (kdfActualKeys.size != kdfExpectedKeys.size || !kdfActualKeys.containsAll(kdfExpectedKeys)) {
        val extra = kdfActualKeys - kdfExpectedKeys
        val missing = kdfExpectedKeys - kdfActualKeys
        if (extra.isNotEmpty()) {
            return RegisterResponse.BadRequest("unknown field in kdf_params: ${extra.first()}")
        }
        return RegisterResponse.BadRequest("missing field in kdf_params: ${missing.first()}")
    }

    val memoryKib = when (val v = kdfParamsJson.entries["memory_kib"]) {
        is Json.Num -> v.value.toInt()
        null -> return RegisterResponse.BadRequest("missing field in kdf_params: memory_kib")
        else -> return RegisterResponse.BadRequest("kdf_params.memory_kib must be an integer")
    }
    val iterations = when (val v = kdfParamsJson.entries["iterations"]) {
        is Json.Num -> v.value.toInt()
        null -> return RegisterResponse.BadRequest("missing field in kdf_params: iterations")
        else -> return RegisterResponse.BadRequest("kdf_params.iterations must be an integer")
    }
    val parallelism = when (val v = kdfParamsJson.entries["parallelism"]) {
        is Json.Num -> v.value.toInt()
        null -> return RegisterResponse.BadRequest("missing field in kdf_params: parallelism")
        else -> return RegisterResponse.BadRequest("kdf_params.parallelism must be an integer")
    }

    val saltBytes = try {
        Base64.getDecoder().decode(saltStr)
    } catch (_: IllegalArgumentException) {
        return RegisterResponse.BadRequest("salt is not valid base64")
    }

    val verifierBytes = try {
        Base64.getDecoder().decode(verifierStr)
    } catch (_: IllegalArgumentException) {
        return RegisterResponse.BadRequest("auth_verifier is not valid base64")
    }

    val verifier = AuthVerifier.fromBytes(verifierBytes)
        ?: return RegisterResponse.BadRequest("auth_verifier must decode to 32 bytes")

    val kdf = KdfParams(memoryKib, iterations, parallelism)

    val result = store.register(username, saltBytes, kdf, kdfVersion, verifier)

    return when (result) {
        is RegisterResult.Registered -> RegisterResponse.Created(username, result.role.dbValue)
        RegisterResult.UsernameTaken -> RegisterResponse.Conflict
        RegisterResult.KdfOutOfBounds -> RegisterResponse.BadRequest("kdf_params out of bounds")
        is RegisterResult.InvalidField -> RegisterResponse.BadRequest("invalid field: ${result.field}")
    }
}

// ---------------------------------------------------------------------------
// Hand-written JSON parser — no kotlinx.serialization, no Gson, no Jackson.
// ---------------------------------------------------------------------------

private class JsonParseException(message: String) : Exception(message)

private sealed class Json {
    data class Obj(val entries: Map<String, Json>) : Json()
    data class Str(val value: String) : Json()
    data class Num(val value: Long) : Json()
    data class Bool(val value: Boolean) : Json()
    object Null : Json()
}

private fun parseJson(text: String): Json {
    val parser = JsonParser(text)
    val result = parser.parseValue()
    parser.skipWhitespace()
    if (!parser.isAtEnd()) {
        throw JsonParseException("trailing characters after JSON value")
    }
    return result
}

private class JsonParser(private val text: String) {
    private var pos = 0

    fun isAtEnd(): Boolean = pos >= text.length

    fun parseValue(): Json {
        skipWhitespace()
        if (isAtEnd()) throw JsonParseException("unexpected end of input")
        return when (val c = text[pos]) {
            '{' -> parseObject()
            '"' -> Json.Str(parseString())
            't' -> parseLiteral("true", Json.Bool(true))
            'f' -> parseLiteral("false", Json.Bool(false))
            'n' -> parseLiteral("null", Json.Null)
            in '0'..'9', '-' -> parseNumber()
            else -> throw JsonParseException("unexpected character: $c")
        }
    }

    private fun parseObject(): Json {
        expect('{')
        val entries = LinkedHashMap<String, Json>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return Json.Obj(entries)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            entries[key] = value
            skipWhitespace()
            when (peek()) {
                ',' -> { pos++; continue }
                '}' -> { pos++; break }
                else -> throw JsonParseException("expected ',' or '}' in object")
            }
        }
        return Json.Obj(entries)
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (!isAtEnd()) {
            val c = text[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (isAtEnd()) throw JsonParseException("unterminated escape")
                    when (val esc = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) throw JsonParseException("bad unicode escape")
                            val hex = text.substring(pos, pos + 4)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw JsonParseException("bad escape: \\$esc")
                    }
                }
                else -> sb.append(c)
            }
        }
        throw JsonParseException("unterminated string")
    }

    private fun parseNumber(): Json {
        val start = pos
        if (peek() == '-') pos++
        while (!isAtEnd() && text[pos].isDigit()) pos++
        if (pos == start || (pos == start + 1 && text[start] == '-')) {
            throw JsonParseException("expected a digit")
        }
        val numberText = text.substring(start, pos)
        val value = numberText.toLongOrNull()
            ?: throw JsonParseException("bad number: $numberText")
        return Json.Num(value)
    }

    private fun parseLiteral(literal: String, value: Json): Json {
        if (text.startsWith(literal, pos)) {
            pos += literal.length
            return value
        }
        throw JsonParseException("expected $literal")
    }

    internal fun skipWhitespace() {
        while (!isAtEnd() && text[pos].isWhitespace()) pos++
    }
    private fun peek(): Char {
        if (isAtEnd()) throw JsonParseException("unexpected end of input")
        return text[pos]
    }

    private fun expect(c: Char) {
        if (isAtEnd() || text[pos] != c) {
            throw JsonParseException("expected '$c'")
        }
        pos++
    }
}
