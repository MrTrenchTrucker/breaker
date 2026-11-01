package dev.breaker.server.syncapi.transcriptions

// The checked body of POST /v1/sync. Exactly the keys client_id, key_version and encrypted;
// client_id a lowercase canonical UUID; key_version a JSON integer from 0 to Int.MAX_VALUE with no
// sign, fraction, exponent or leading zero; encrypted a non-empty standard base64 text with correct
// padding and no whitespace. The server never decodes encrypted and does not check its trailing bits.

internal const val MESSAGE_BODY_SHAPE: String =
    "request body must be one JSON object with exactly the keys client_id, key_version and encrypted"
internal const val MESSAGE_CLIENT_ID: String = "client_id must be a lowercase canonical UUID"
internal val MESSAGE_KEY_VERSION: String = "key_version must be an integer from 0 to ${Int.MAX_VALUE}"
internal const val MESSAGE_ENCRYPTED: String = "encrypted must be non-empty standard base64 with correct padding"

internal class SyncPushBody(val clientId: String, val keyVersion: Int, val encrypted: String)

internal sealed class SyncPushParse {
    internal class Accepted(val body: SyncPushBody) : SyncPushParse()

    internal class Refused(val message: String) : SyncPushParse()
}

internal fun parseSyncPush(text: String): SyncPushParse {
    val members = readSyncJsonObject(text) ?: return SyncPushParse.Refused(MESSAGE_BODY_SHAPE)
    val exactKeys = members.size == 3 &&
        members.containsKey("client_id") &&
        members.containsKey("key_version") &&
        members.containsKey("encrypted")
    if (!exactKeys) return SyncPushParse.Refused(MESSAGE_BODY_SHAPE)

    val clientId = (members.getValue("client_id") as? SyncJsonText)?.value
    if (clientId == null || !isCanonicalUuid(clientId)) return SyncPushParse.Refused(MESSAGE_CLIENT_ID)

    val keyVersion = keyVersionOf(members.getValue("key_version"))
        ?: return SyncPushParse.Refused(MESSAGE_KEY_VERSION)

    val encrypted = (members.getValue("encrypted") as? SyncJsonText)?.value
    if (encrypted == null || !isStandardBase64(encrypted)) return SyncPushParse.Refused(MESSAGE_ENCRYPTED)

    return SyncPushParse.Accepted(SyncPushBody(clientId, keyVersion, encrypted))
}

private val MAX_KEY_VERSION: Long = Int.MAX_VALUE.toLong()

// Only plain digits, no leading zero unless the value is 0, at most ten digits, and not above Int.MAX_VALUE.
private fun keyVersionOf(value: SyncJsonValue): Int? {
    if (value !is SyncJsonNumeral) return null
    val raw = value.raw
    if (raw.isEmpty() || raw.length > 10) return null
    if (!raw.all { character -> character in '0'..'9' }) return null
    if (raw.length > 1 && raw[0] == '0') return null
    val number = raw.toLongOrNull() ?: return null
    if (number > MAX_KEY_VERSION) return null
    return number.toInt()
}

// 8-4-4-4-12 lowercase hexadecimal digits with hyphens, 36 characters in all.
private fun isCanonicalUuid(text: String): Boolean {
    if (text.length != 36) return false
    for (index in text.indices) {
        val character = text[index]
        val hyphenPosition = index == 8 || index == 13 || index == 18 || index == 23
        val ok = if (hyphenPosition) {
            character == '-'
        } else {
            character in '0'..'9' || character in 'a'..'f'
        }
        if (!ok) return false
    }
    return true
}

// Standard alphabet (A-Z a-z 0-9 + /), a length that is a multiple of four, and '=' only as one or
// two characters at the very end. Whitespace and URL-safe characters fail the alphabet check.
private fun isStandardBase64(text: String): Boolean {
    if (text.isEmpty() || text.length % 4 != 0) return false
    val paddingStart = when {
        text.endsWith("==") -> text.length - 2
        text.endsWith("=") -> text.length - 1
        else -> text.length
    }
    for (index in text.indices) {
        val character = text[index]
        val alphabet = character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
            character == '+' || character == '/'
        val padding = character == '=' && index >= paddingStart
        if (!alphabet && !padding) return false
    }
    return true
}
