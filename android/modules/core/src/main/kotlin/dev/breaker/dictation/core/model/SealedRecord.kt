package dev.breaker.dictation.core.model

/**
 * One record sealed for the server: the key version it was sealed at and the frame text.
 *
 * The text is opaque here. Core does not read, split or judge it; the port that made it
 * (RecordKeys) is the only code that knows what it holds. It is still handled like a
 * secret, so this is a plain class, not a data class: there is no generated toString or copy
 * that could print the text, and no value equality.
 *
 * The constructor refuses a key version below 1 (version 0 is the plain form and is never
 * sealed) and a blank text.
 */
class SealedRecord(val keyVersion: Int, val encrypted: String) {

    init {
        require(keyVersion >= 1) { "A sealed record needs a key version of at least 1" }
        require(encrypted.isNotBlank()) { "A sealed record cannot have a blank text" }
    }

    /** Never prints the text, only the key version and how long the text is. */
    override fun toString(): String =
        "SealedRecord(keyVersion=$keyVersion, ${encrypted.length} chars)"
}
