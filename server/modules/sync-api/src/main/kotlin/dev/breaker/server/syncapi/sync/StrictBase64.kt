package dev.breaker.server.syncapi.sync

import java.util.Base64

/**
 * True when [text] is canonical standard base64: the standard alphabet, padded to a whole number
 * of four-character groups, with no non-zero bits after the last byte. The decoder accepts some
 * texts that do not encode back to themselves (no padding, or non-zero trailing bits); those are
 * refused here. The text is never put into a message.
 *
 * The shared codec keeps the same rule in a private function, so this file repeats it.
 */
internal fun isStrictBase64(text: String): Boolean {
    if (text.isEmpty()) return false
    val decoded = try {
        Base64.getDecoder().decode(text)
    } catch (refusal: IllegalArgumentException) {
        return false
    }
    return Base64.getEncoder().encodeToString(decoded) == text
}
