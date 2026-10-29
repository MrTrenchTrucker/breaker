package dev.breaker.server.whisper.db

/**
 * Raised when the database cannot be brought to the schema this code expects.
 * The server must not start serving requests on a schema it does not understand,
 * so callers let this one escape.
 */
internal class MigrationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
