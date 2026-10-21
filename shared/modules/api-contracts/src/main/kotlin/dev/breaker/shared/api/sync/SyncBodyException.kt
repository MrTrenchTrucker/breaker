package dev.breaker.shared.api.sync

/**
 * Thrown when a sync body is refused. The message names the refusal family
 * only; it never carries the body, a string value or a key.
 */
class SyncBodyException(message: String) : IllegalArgumentException(message)

/** The refusal families, as they appear after the fixed prefix of the message. */
internal object SyncBodyFamily {
    const val BASE64 = "base64"
    const val UTF8 = "utf8"
    const val BOM = "bom"
    const val NOT_OBJECT = "not_object"
    const val VERSION = "version"
    const val MISSING_KEY = "missing_key"
    const val DUPLICATE_KEY = "duplicate_key"
    const val UNKNOWN_KEY = "unknown_key"
    const val TYPE = "type"
    const val SOURCE = "source"
    const val DURATION = "duration"
    const val CREATED_AT = "created_at"
    const val SURROGATE = "surrogate"
    const val TRUNCATED = "truncated"
    const val TRAILING = "trailing"
    const val CLIENT_ID = "client_id"
    const val SYNTAX = "syntax"
}

internal fun refuse(family: String): Nothing =
    throw SyncBodyException("sync body refused: " + family)
