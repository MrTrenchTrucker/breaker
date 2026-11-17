package dev.breaker.shared.api

/**
 * The request to register a new account.
 *
 * Mirrors the `RegisterRequest` schema in the OpenAPI spec: username, salt,
 * kdfParams, kdfVersion, authVerifier, and the optional installCode (the
 * first-admin install code, ADR-043 slice C).
 * Never a password, never a DEK — the client derives the KEK and sends the verifier.
 */
data class RegisterRequest(
    val username: String,
    val salt: String,
    val kdfParams: KdfParams,
    val kdfVersion: Int,
    val authVerifier: String,
    val installCode: String? = null,
) {
    init {
        require(java.util.Base64.getDecoder().decode(salt).size == 16) {
            "salt must be 16 bytes after decode"
        }
    }

    override fun toString(): String =
        "RegisterRequest(username=$username, salt=$salt, kdfParams=$kdfParams, " +
        "kdfVersion=$kdfVersion, authVerifier=<redacted>)"
}
