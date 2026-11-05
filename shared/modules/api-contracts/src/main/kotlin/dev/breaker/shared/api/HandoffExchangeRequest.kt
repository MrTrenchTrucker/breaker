package dev.breaker.shared.api

/**
 * The request that exchanges a sign-in code and its secret for a session.
 *
 * Mirrors the `HandoffExchangeRequest` schema in the OpenAPI spec.
 * The code and the secret are the credential; deviceName is optional.
 */
data class HandoffExchangeRequest(
    val code: String,
    val secret: String,
    val deviceName: String?,
) {
    init {
        require(code.matches(Regex("^[A-Za-z0-9_-]{43}$"))) {
            "code must be 43 chars of [A-Za-z0-9_-]"
        }
        require(secret.matches(Regex("^[A-Za-z0-9_-]{43}$"))) {
            "secret must be 43 chars of [A-Za-z0-9_-]"
        }
        require(deviceName == null || deviceName.length in 1..64) {
            "deviceName must be null or 1..64 code points"
        }
    }

    override fun toString(): String =
        "HandoffExchangeRequest(code=<redacted>, secret=<redacted>, deviceName=$deviceName)"
}
