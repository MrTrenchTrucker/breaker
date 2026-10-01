package dev.breaker.dictation.core.port

/**
 * Mints identifiers for new domain records.
 *
 * Implementations use whatever the platform offers. The domain asks for an id
 * rather than generating one, so tests can assert on exact values.
 */
fun interface IdSource {
    fun newId(): String
}
