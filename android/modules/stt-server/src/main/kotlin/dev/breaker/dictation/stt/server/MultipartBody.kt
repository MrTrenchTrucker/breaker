package dev.breaker.dictation.stt.server

import java.io.ByteArrayOutputStream

/**
 * A finished multipart/form-data body, with the content type that names its boundary.
 *
 * [bytes] is the exact wire form: CRLF line endings and the closing delimiter.
 */
internal class MultipartBody(val contentType: String, val bytes: ByteArray)

/**
 * Builds the upload body for the transcription endpoint.
 *
 * The body has three parts in this order: `file` (the WAV bytes, filename
 * `audio.wav`, content type `audio/wav`), `model` and `language`. Text values are
 * written as UTF-8. Whether the boundary text occurs inside the WAV bytes is not
 * checked here; the boundary is random (128 bits), so a clash is not expected.
 */
internal object MultipartBodies {

    private const val MAX_FIELD_CHARS = 128
    private const val MIN_BOUNDARY_CHARS = 16
    private const val MAX_BOUNDARY_CHARS = 70
    private const val CRLF = "\r\n"

    /**
     * True when [value] can be sent as a form field: not blank, at most 128
     * characters, and free of CR, LF, NUL and the double quote, which would break
     * the part header.
     */
    fun isSafeField(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_FIELD_CHARS) return false
        for (c in value) {
            if (c == '\r' || c == '\n' || c == '\u0000' || c == '"') return false
        }
        return true
    }

    /**
     * Builds the body for [wav] with the given [model], [language] and [boundary].
     *
     * Throws IllegalArgumentException, with a fixed message that holds no value,
     * for an unsafe field or a boundary that is not 16 to 70 lower-case hex characters.
     */
    fun create(wav: ByteArray, model: String, language: String, boundary: String): MultipartBody {
        require(isSafeField(model)) { "the model is not a usable form field" }
        require(isSafeField(language)) { "the language is not a usable form field" }
        require(isBoundaryUsable(boundary)) { "the boundary is not usable" }

        val out = ByteArrayOutputStream()
        text(out, "--$boundary$CRLF")
        text(out, "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"$CRLF")
        text(out, "Content-Type: audio/wav$CRLF")
        text(out, CRLF)
        out.write(wav)
        text(out, CRLF)
        text(out, "--$boundary$CRLF")
        text(out, "Content-Disposition: form-data; name=\"model\"$CRLF")
        text(out, CRLF)
        text(out, model)
        text(out, CRLF)
        text(out, "--$boundary$CRLF")
        text(out, "Content-Disposition: form-data; name=\"language\"$CRLF")
        text(out, CRLF)
        text(out, language)
        text(out, CRLF)
        text(out, "--$boundary--$CRLF")

        return MultipartBody("multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    private fun isBoundaryUsable(boundary: String): Boolean =
        boundary.length in MIN_BOUNDARY_CHARS..MAX_BOUNDARY_CHARS &&
            boundary.all { it in '0'..'9' || it in 'a'..'f' }

    private fun text(out: ByteArrayOutputStream, value: String) {
        out.write(value.toByteArray(Charsets.UTF_8))
    }
}
