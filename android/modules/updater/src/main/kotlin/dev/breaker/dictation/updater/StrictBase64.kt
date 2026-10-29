package dev.breaker.dictation.updater

import java.util.Base64

/**
 * Decodes standard base64 with padding, and accepts the text only when it is the canonical encoding of the bytes.
 *
 * Returns null for empty text, for a character outside the standard alphabet, for a line break, for missing
 * padding, and for any other text that does not re-encode to exactly the same characters.
 */
internal fun decodeCanonicalBase64(text: String): ByteArray? {
    if (text.isEmpty()) return null
    val bytes = try { Base64.getDecoder().decode(text) } catch (e: IllegalArgumentException) { return null }
    return if (Base64.getEncoder().encodeToString(bytes) == text) bytes else null
}
