package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.StoredKeys
import dev.breaker.server.syncapi.escapeJsonString
import dev.breaker.server.syncapi.transcriptions.formatInstantMs
import java.util.Base64

/**
 * Builds the 200 login response JSON from the stored key columns. A byte member is null or a
 * JSON string of its standard padded base64 text; key_version is the stored integer; reset_at
 * and rekeyed_at are null or a JSON string of the UTC instant. A keyless account answers null
 * for every key member and 0 for key_version. Login and the reset completion answer with it.
 */
internal fun buildLoginResponse(role: String, sessionText: String, keys: StoredKeys): String {
    val token = escapeJsonString(sessionText)
    val roleText = escapeJsonString(role)
    val wrappedDek = bytesMember(keys.wrappedDek)
    val wrappedBoxPrivkey = bytesMember(keys.wrappedBoxPrivkey)
    val boxPubkey = bytesMember(keys.boxPubkey)
    val prevWrappedDek = bytesMember(keys.prevWrappedDek)
    val resetAt = instantMember(keys.resetAtMs)
    val rekeyedAt = instantMember(keys.rekeyedAtMs)
    return "{\"token\":" + token + ",\"role\":" + roleText +
        ",\"wrapped_dek\":" + wrappedDek + ",\"wrapped_box_privkey\":" + wrappedBoxPrivkey +
        ",\"box_pubkey\":" + boxPubkey + ",\"key_version\":" + keys.keyVersion +
        ",\"prev_wrapped_dek\":" + prevWrappedDek + ",\"reset_at\":" + resetAt +
        ",\"rekeyed_at\":" + rekeyedAt + "}"
}

// The base64 alphabet needs no JSON escaping.
private fun bytesMember(bytes: ByteArray?): String {
    if (bytes == null) return "null"
    return "\"" + Base64.getEncoder().encodeToString(bytes) + "\""
}

private fun instantMember(epochMillis: Long?): String {
    if (epochMillis == null) return "null"
    return "\"" + formatInstantMs(epochMillis) + "\""
}
