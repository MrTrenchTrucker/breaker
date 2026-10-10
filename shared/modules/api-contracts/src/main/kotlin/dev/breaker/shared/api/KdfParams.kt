package dev.breaker.shared.api

/**
 * The password-hardening cost the client chose; the server stores and returns it, it never runs it.
 *
 * Mirrors the `KdfParams` schema in the OpenAPI spec. Memory is in MiB.
 */
data class KdfParams(
    val memory: Int,
    val iterations: Int,
    val parallelism: Int,
)
