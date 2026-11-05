package dev.breaker.shared.api

/**
 * The response when login succeeds.
 *
 * Mirrors the `LoginResponse` schema in the OpenAPI spec.
 * The four key fields are null together for a keyless account.
 */
data class LoginResponse(
    val token: String,
    val role: String,
    val wrappedDek: String?,
    val wrappedBoxPrivkey: String?,
    val boxPubkey: String?,
    val keyVersion: Int,
    val prevWrappedDek: String?,
    val resetAt: String?,
    val rekeyedAt: String?,
) {
    init {
        require(keyVersion >= 0) {
            "keyVersion must be non-negative"
        }
    }

    override fun toString(): String =
        "LoginResponse(role=$role, keyVersion=$keyVersion, resetAt=$resetAt, rekeyedAt=$rekeyedAt, " +
        "boxPubkey=$boxPubkey, wrappedDek=${wrappedDek?.let { "${it.length} chars" } ?: "null"}, " +
        "wrappedBoxPrivkey=${wrappedBoxPrivkey?.let { "${it.length} chars" } ?: "null"}, " +
        "prevWrappedDek=${prevWrappedDek?.let { "${it.length} chars" } ?: "null"}, token=<redacted>)"
}
