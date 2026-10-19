package dev.breaker.shared.api

/**
 * The server's health and released GIT tag.
 *
 * Mirrors the `AdminHealth` schema in the OpenAPI spec. Distinct from the job
 * group's `Health`, which reports forwarding status rather than software version.
 */
data class AdminHealth(
    val status: String,
    val gitTag: String,
)
