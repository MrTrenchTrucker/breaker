package dev.breaker.dictation.sync

/** One pull request: the path and the optional `since`. Only [path] and [query] are public; the
 * factories belong to the pull loop and the module's own code. */
class PullRequest private constructor(
    /** The optional `since` query value; null for the first request. */
    internal val since: String?,
) {

    companion object {
        private const val PATH = "/v1/transcriptions"

        /** The first request: no `since`. */
        internal fun first(): PullRequest = PullRequest(null)

        /** A later request: keeps [cursor] exactly as given, but refuses an empty text. */
        internal fun after(cursor: String): PullRequest {
            if (cursor.isEmpty()) {
                throw IllegalArgumentException("after: an empty cursor is not a cursor")
            }
            return PullRequest(cursor)
        }
    }

    /** The fixed pull path. */
    fun path(): String = PATH

    /** The query text: "" for the first request, "since=" + percent-encoded cursor for a later one. */
    fun query(): String = since?.let { "since=" + percentEncode(it) } ?: ""

    private fun percentEncode(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        val hex = ('0'..'9').joinToString("") + "ABCDEF"
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (isUnreserved(char)) {
                out.append(char)
            } else {
                out.append('%')
                out.append(hex[(value shr 4) and 0x0F])
                out.append(hex[value and 0x0F])
            }
        }
        return out.toString()
    }

    private fun isUnreserved(char: Char): Boolean =
        (char in 'A'..'Z') || (char in 'a'..'z') || (char in '0'..'9') ||
            char == '-' || char == '.' || char == '_' || char == '~'
}
