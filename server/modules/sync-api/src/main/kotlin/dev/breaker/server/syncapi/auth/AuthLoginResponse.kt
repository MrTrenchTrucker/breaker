package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.StoredKeys
import dev.breaker.server.syncapi.transcriptions.formatInstantMs
import java.util.Base64

/**
 * Builds the 200 login response JSON from the stored key columns. A byte member is null or a
 * JSON string of its standard padded base64 text; key_version is the stored integer; reset_at
 * and rekeyed_at are null or a JSON string of the UTC instant. A keyless account answers null
 * for every key member and 0 for key_version. Login and the reset completion answer with it.
 */
internal fun buildLoginResponse(role: String, sessionText: String, keys: StoredKeys): String {
    return """{"token":"$sessionText","role":"$role","wrapped_dek":${bytesMember(keys.wrappedDek)},"wrapped_box_privkey":${bytesMember(keys.wrappedBoxPrivkey)},"box_pubkey":${bytesMember(keys.boxPubkey)},"key_version":${keys.keyVersion},"prev_wrapped_dek":${bytesMember(keys.prevWrappedDek)},"reset_at":${instantMember(keys.resetAtMs)},"rekeyed_at":${instantMember(keys.rekeyedAtMs)}}"""
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
