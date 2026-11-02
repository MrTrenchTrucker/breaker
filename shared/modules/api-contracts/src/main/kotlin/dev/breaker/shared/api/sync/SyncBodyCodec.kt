package dev.breaker.shared.api.sync

import dev.breaker.shared.api.SyncPush
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * The codec for the sync body. The key_version 0 form of `encrypted` is the
 * body JSON object as UTF-8, written as padded standard base64 with no line
 * breaks. No cryptography is involved at key_version 0.
 */
object SyncBodyCodec {
    const val BODY_VERSION: Int = 1

    private const val MAX_DURATION_MS: Long = 9007199254740991L
    private val KNOWN_KEYS = setOf("v", "text", "source", "model", "duration_ms", "created_at")
    private val DURATION_RE = Regex("0|[1-9][0-9]{0,15}")
    private val CLIENT_ID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Encodes a body as the body JSON object: fixed key order, no whitespace. Refuses an input the decoder would refuse. */
    fun encodeBody(body: SyncBody): ByteArray {
        if (body.durationMs < 0L || body.durationMs > MAX_DURATION_MS) refuse(SyncBodyFamily.DURATION)
        val createdAt = formatCreatedAtMs(body.createdAtMs)
        val textJson = jsonString(body.text)
        val modelJson = jsonString(body.model)
        val json = "{\"v\":" + BODY_VERSION + ",\"text\":" + textJson + ",\"source\":\"" + body.source.wire + "\",\"model\":" + modelJson + ",\"duration_ms\":" + body.durationMs + ",\"created_at\":\"" + createdAt + "\"}"
        return json.encodeToByteArray()
    }

    /** Decodes and checks a body JSON object. Every refusal is a SyncBodyException; no partly read body is returned. */
    fun decodeBody(bytes: ByteArray): SyncBody {
        if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF) refuse(SyncBodyFamily.BOM)
        val text = decodeUtf8Strict(bytes)
        val members = SyncBodyScanner(text).parseDocument()
        for (key in members.keys) if (key !in KNOWN_KEYS) refuse(SyncBodyFamily.UNKNOWN_KEY)
        val vRaw = members["v"] ?: refuse(SyncBodyFamily.MISSING_KEY)
        if (!(vRaw is JsonValue.Num && vRaw.token == "1")) refuse(SyncBodyFamily.VERSION)
        val textRaw = members["text"] ?: refuse(SyncBodyFamily.MISSING_KEY)
        val textValue = (textRaw as? JsonValue.Str)?.value ?: refuse(SyncBodyFamily.TYPE)
        val srcRaw = members["source"] ?: refuse(SyncBodyFamily.MISSING_KEY)
        val srcWire = (srcRaw as? JsonValue.Str)?.value ?: refuse(SyncBodyFamily.TYPE)
        val source = SyncSource.fromWire(srcWire) ?: refuse(SyncBodyFamily.SOURCE)
        val modelRaw = members["model"] ?: refuse(SyncBodyFamily.MISSING_KEY)
        val modelValue = (modelRaw as? JsonValue.Str)?.value ?: refuse(SyncBodyFamily.TYPE)
        val durRaw = members["duration_ms"] ?: refuse(SyncBodyFamily.MISSING_KEY)
        val durToken = (durRaw as? JsonValue.Num)?.token ?: refuse(SyncBodyFamily.TYPE)
        val durationMs = parseDuration(durToken)
        val crRaw = members["created_at"] ?: refuse(SyncBodyFamily.MISSING_KEY)
        val crText = (crRaw as? JsonValue.Str)?.value ?: refuse(SyncBodyFamily.TYPE)
        val createdAtMs = parseCreatedAtMs(crText)
        return SyncBody(textValue, source, modelValue, durationMs, createdAtMs)
    }

    /** The key_version 0 `encrypted` string for a body: padded standard base64 of the body bytes, no line breaks. */
    fun encodePlain(body: SyncBody): String = Base64.getEncoder().encodeToString(encodeBody(body))

    /** Reads a key_version 0 `encrypted` string back to a body. Refuses every non-canonical base64 form. */
    fun decodePlain(encrypted: String): SyncBody = decodeBody(decodeBase64Strict(encrypted))

    /** True when the candidate is a lowercase canonical UUID (8-4-4-4-12). The whole input must match. */
    fun isClientId(candidate: String): Boolean = CLIENT_ID_RE.matches(candidate)

    /** The push for one body at key_version 0. Refuses a client id that is not a lowercase canonical UUID. */
    fun plainPush(clientId: String, body: SyncBody): SyncPush {
        if (!isClientId(clientId)) refuse(SyncBodyFamily.CLIENT_ID)
        val encrypted = encodePlain(body)
        return SyncPush(clientId, 0, encrypted, null)
    }

    /** The created_at form of epoch milliseconds: UTC, always three fractional digits. */
    fun formatCreatedAt(epochMs: Long): String = formatCreatedAtMs(epochMs)

    /** Parses the created_at form back to epoch milliseconds. */
    fun parseCreatedAt(text: String): Long = parseCreatedAtMs(text)

    private fun jsonString(s: String): String {
        val sb = StringBuilder()
        writeJsonString(sb, s)
        return sb.toString()
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val s = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            refuse(SyncBodyFamily.UTF8)
        }
        checkNoLoneSurrogate(s)
        return s
    }

    // The JDK strict decoder above already refuses an encoded surrogate (bytes ED A0 80),
    // so on the JVM this scan finds nothing. Keep it: the Android runtime decoder is not
    // the JDK one and may let a surrogate through.
    private fun checkNoLoneSurrogate(s: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                i += 2
            } else if (c.isSurrogate()) {
                refuse(SyncBodyFamily.UTF8)
            } else {
                i++
            }
        }
    }

    private fun decodeBase64Strict(text: String): ByteArray {
        if (text.isEmpty()) refuse(SyncBodyFamily.BASE64)
        val values = IntArray(text.length)
        var n = 0
        var pad = 0
        for (c in text) {
            if (c == '=') {
                pad++
            } else {
                if (pad > 0) refuse(SyncBodyFamily.BASE64)
                val v = base64Value(c)
                if (v < 0) refuse(SyncBodyFamily.BASE64)
                values[n] = v
                n++
            }
        }
        if (pad > 2) refuse(SyncBodyFamily.BASE64)
        val expectedPad = when (n % 4) {
            0 -> 0
            2 -> 2
            3 -> 1
            else -> -1
        }
        if (pad != expectedPad) refuse(SyncBodyFamily.BASE64)
        if ((n % 4 == 2 && (values[n - 1] and 0x0F) != 0) || (n % 4 == 3 && (values[n - 1] and 0x03) != 0)) refuse(SyncBodyFamily.BASE64)
        val tail = when (n % 4) {
            2 -> 1
            3 -> 2
            else -> 0
        }
        val out = ByteArray((n / 4) * 3 + tail)
        var o = 0
        var i = 0
        while (i < n) {
            val v0 = values[i]
            val v1 = if (i + 1 < n) values[i + 1] else 0
            val v2 = if (i + 2 < n) values[i + 2] else 0
            val v3 = if (i + 3 < n) values[i + 3] else 0
            out[o] = ((v0 shl 2) or (v1 shr 4)).toByte()
            o++
            if (i + 2 < n) {
                out[o] = (((v1 and 0x0F) shl 4) or (v2 shr 2)).toByte()
                o++
            }
            if (i + 3 < n) {
                out[o] = (((v2 and 0x03) shl 6) or v3).toByte()
                o++
            }
            i += 4
        }
        return out
    }

    private fun base64Value(c: Char): Int = when (c) {
        in 'A'..'Z' -> c.code - 'A'.code
        in 'a'..'z' -> c.code - 'a'.code + 26
        in '0'..'9' -> c.code - '0'.code + 52
        '+' -> 62; '/' -> 63
        else -> -1
    }

    private fun parseDuration(token: String): Long {
        if (!DURATION_RE.matches(token)) refuse(SyncBodyFamily.DURATION)
        val n = token.toLong()
        if (n > MAX_DURATION_MS) refuse(SyncBodyFamily.DURATION)
        return n
    }
}
