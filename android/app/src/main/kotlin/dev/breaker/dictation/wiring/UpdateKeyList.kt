package dev.breaker.dictation.wiring

import java.util.Base64

/**
 * Reads the project owner's update key list: one base64 text per line, each the raw 32-byte public key
 * of an Ed25519 signing key.
 *
 * Blank lines and lines that start with a number sign are skipped. Every other line must be canonical
 * standard base64 that decodes to exactly 32 bytes. When any such line fails, the whole answer is an empty
 * list, so a damaged list fails closed. Keys that are the same are kept once, in first order.
 *
 * This object is plain: it reads the owner's key list and holds no port.
 */
object UpdateKeyList {
    private const val KEY_BYTES: Int = 32
    private const val COMMENT: String = "#"

    /** The keys in [text], in order, or an empty list when any key line is refused. Never throws. */
    fun parse(text: String): List<ByteArray> =
        try {
            decodeAll(text)
        } catch (e: Exception) {
            emptyList()
        }

    private fun decodeAll(text: String): List<ByteArray> {
        val keys: MutableList<ByteArray> = ArrayList()
        for (raw in text.split('\n')) {
            val line: String = raw.trim()
            if (line.isEmpty() || line.startsWith(COMMENT)) continue
            val key: ByteArray = decodeKey(line) ?: return emptyList()
            if (keys.none { it.contentEquals(key) }) keys.add(key)
        }
        return keys
    }

    /** The 32 bytes of one key line, or null when the line is not canonical base64 of 32 bytes. */
    private fun decodeKey(line: String): ByteArray? {
        val bytes: ByteArray = try {
            Base64.getDecoder().decode(line)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size != KEY_BYTES) return null
        return if (Base64.getEncoder().encodeToString(bytes) == line) bytes else null
    }
}
