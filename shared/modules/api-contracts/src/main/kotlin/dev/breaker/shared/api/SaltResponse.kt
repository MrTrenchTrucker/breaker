package dev.breaker.shared.api

/**
 * The response with an account's salt and KDF parameters.
 *
 * Mirrors the `SaltResponse` schema in the OpenAPI spec.
 */
data class SaltResponse(
    val salt: String,
    val kdfParams: KdfParams,
    val kdfVersion: Int,
) {
    init {
        require(java.util.Base64.getDecoder().decode(salt).size == 16) {
            "salt must be 16 bytes after decode"
        }
    }
}
