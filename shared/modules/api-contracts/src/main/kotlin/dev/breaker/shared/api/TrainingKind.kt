package dev.breaker.shared.api

/**
 * Which phrase a training sample belongs to: the `wake` phrase or the `send` phrase.
 *
 * Mirrors the `TrainingKind` schema in the OpenAPI spec.
 */
enum class TrainingKind {
    WAKE,
    SEND;

    companion object {
        /**
         * Returns the TrainingKind for the given string value, or null if not found.
         */
        fun fromString(value: String): TrainingKind? =
            values().find { it.name.lowercase() == value.lowercase() }
    }
}
