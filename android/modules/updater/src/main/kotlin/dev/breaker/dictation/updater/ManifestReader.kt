package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo

/**
 * Reads the update manifest from its text: one JSON object with five required text fields and two optional ones.
 *
 * Limits: the input is the text of the document, and the size cap counts its UTF-8 bytes (65 536 at most).
 * A caller that holds raw bytes must decode them strictly as UTF-8 before calling [read]; a lenient decode
 * would change the bytes the signature covers. Unknown top-level fields are parsed by the strict reader and
 * then ignored. The version field is a display name only and is kept as sent; the version_code field is the
 * integer release code and is parsed by [VersionCode].
 */
internal object ManifestReader {
    /** The largest document, in UTF-8 bytes. */
    internal const val MAX_BYTES: Int = 65_536

    /** The most code points allowed in the notes field. */
    internal const val MAX_NOTES: Int = 4096

    /** The most characters allowed in the key id field. */
    internal const val MAX_KEY_ID: Int = 64

    /** The most characters allowed in the display name field. */
    internal const val MAX_NAME: Int = 64

    /** The refusal for a document larger than the cap. */
    internal const val TOO_LARGE: String = "The update file is larger than 64 KiB."

    /** The refusal for a document that is not valid JSON. */
    internal const val NOT_JSON: String = "The update file is not valid JSON."

    /** The refusal for a document that is valid JSON but not one object. */
    internal const val NOT_OBJECT: String = "The update file is not one JSON object."

    /** The refusal for a required field that is absent or blank. */
    internal const val MISSING_FIELD: String = "The update file is missing a required field."

    /** The refusal for a known field whose value is not text. */
    internal const val NOT_TEXT: String = "A field in the update file is not text."

    /** The refusal for a display name that is not 1 to 64 characters of the allowed set. */
    internal const val BAD_VERSION_NAME: String = "The update file version name is not usable."

    /** The refusal for a version code that is not canonical decimal from 1 to 2100000000. */
    internal const val BAD_VERSION_CODE: String = "The update file version code is not usable."

    /** The refusal for a digest that is not 64 lowercase hex characters. */
    internal const val BAD_DIGEST: String = "The update file digest is not 64 lowercase hex characters."

    /** The refusal for a key id that is not 1 to 64 characters of the allowed set. */
    internal const val BAD_KEY_ID: String = "The update file key id is not usable."

    /** The refusal for notes that are too long or carry a control character. */
    internal const val BAD_NOTES: String = "The update file notes are not usable as plain text."

    private const val DIGEST_LENGTH: Int = 64

    /** Reads [text] as a manifest and returns the accepted fields, or a fixed refusal sentence. */
    fun read(text: String): ManifestOutcome {
        val size = utf8Length(text)
        if (size > MAX_BYTES) return refusal(TOO_LARGE)
        val root = try { JsonParser.parse(text) } catch (error: JsonSyntaxException) { return refusal(NOT_JSON) }
        val fields = (root as? JsonValue.Obj)?.fields ?: return refusal(NOT_OBJECT)
        for (name in listOf("version", "version_code", "apk_url", "sha256", "signature")) {
            requiredProblem(fields, name)?.let { return refusal(it) }
        }
        val name = textOf(fields, "version")
        if (!isDisplayName(name)) return refusal(BAD_VERSION_NAME)
        val versionCode = VersionCode.parse(textOf(fields, "version_code")) ?: return refusal(BAD_VERSION_CODE)
        val notesValue = fields["notes"]
        if (notesValue != null && notesValue !is JsonValue.Str) return refusal(NOT_TEXT)
        val keyIdValue = fields["key_id"]
        if (keyIdValue != null && keyIdValue !is JsonValue.Str) return refusal(NOT_TEXT)
        val digest = textOf(fields, "sha256")
        if (!isDigest(digest)) return refusal(BAD_DIGEST)
        val keyId = (keyIdValue as? JsonValue.Str)?.value
        if (keyId != null && !isKeyId(keyId)) return refusal(BAD_KEY_ID)
        val notes = (notesValue as? JsonValue.Str)?.value ?: ""
        if (!notesUsable(notes)) return refusal(BAD_NOTES)
        val release = ReleaseInfo(
            version = name,
            apkUrl = textOf(fields, "apk_url"),
            sha256 = digest,
            signature = textOf(fields, "signature"),
            notes = notes,
        )
        return ManifestOutcome.Accepted(ParsedManifest(release, versionCode, keyId))
    }

    /** Returns the refusal for a required field, or null when the field is present, text and not blank. */
    private fun requiredProblem(fields: Map<String, JsonValue>, name: String): String? {
        val value = fields[name] ?: return MISSING_FIELD
        if (value !is JsonValue.Str) return NOT_TEXT
        if (value.value.isBlank()) return MISSING_FIELD
        return null
    }

    private fun textOf(fields: Map<String, JsonValue>, name: String): String =
        (fields.getValue(name) as JsonValue.Str).value

    private fun refusal(reason: String): ManifestOutcome = ManifestOutcome.Refused(reason)

    /** The length of [text] in UTF-8 bytes, counted without a JDK call. */
    private fun utf8Length(text: String): Int {
        var total = 0
        for (c in text) {
            total += when {
                c < '\u0080' -> 1
                c < '\u0800' -> 2
                c in '\uD800'..'\uDFFF' -> 2
                else -> 3
            }
        }
        return total
    }

    /** A display name is 1 to 64 characters, each one of A-Z a-z 0-9 . _ - +. */
    private fun isDisplayName(text: String): Boolean =
        text.length in 1..MAX_NAME &&
            text.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-' || c == '+' }

    private fun isDigest(text: String): Boolean =
        text.length == DIGEST_LENGTH && text.all { c -> c in '0'..'9' || c in 'a'..'f' }

    private fun isKeyId(text: String): Boolean =
        text.length in 1..MAX_KEY_ID &&
            text.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-' }

    /** Notes may be empty; otherwise they hold at most [MAX_NOTES] code points and no control character but line feed. */
    private fun notesUsable(text: String): Boolean {
        if (text.isEmpty()) return true
        if (codePointCount(text) > MAX_NOTES) return false
        for (c in text) {
            if (c != '\n' && isControl(c)) return false
        }
        return true
    }

    /** The number of code points in [text]; a valid surrogate pair counts once. */
    private fun codePointCount(text: String): Int = text.count { c -> c !in '\uDC00'..'\uDFFF' }

    private fun isControl(c: Char): Boolean = c < ' ' || c in '\u007F'..'\u009F'
}
