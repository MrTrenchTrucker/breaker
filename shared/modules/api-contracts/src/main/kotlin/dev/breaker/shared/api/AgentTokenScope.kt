package dev.breaker.shared.api

/**
 * The token's scope. `transcribe` lets it call the transcription endpoints,
 * `admin` additionally the admin group.
 *
 * Mirrors the `AgentTokenScope` schema in the OpenAPI spec.
 */
enum class AgentTokenScope {
    TRANSCRIBE,
    ADMIN;

    companion object {
        /**
         * Returns the AgentTokenScope for the given string value, or null if not found.
         */
        fun fromString(value: String): AgentTokenScope? =
            values().find { it.name.lowercase() == value.lowercase() }
    }
}
