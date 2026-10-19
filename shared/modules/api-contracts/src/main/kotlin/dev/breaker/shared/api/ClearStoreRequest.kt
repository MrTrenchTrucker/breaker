package dev.breaker.shared.api

/**
 * A request to clear stored transcriptions.
 *
 * Mirrors the `ClearStoreRequest` schema in the OpenAPI spec. With a null username
 * the whole store is cleared; otherwise only that user's store. The caller must set
 * confirm to true or the call is refused (400).
 */
data class ClearStoreRequest(
    val username: String?,
    val confirm: Boolean,
)
