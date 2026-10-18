package dev.breaker.server.syncapi.tokens

internal enum class TokenKind(val dbValue: String) {
    SESSION("session"),
    AGENT("agent");

    companion object {
        // The table's CHECK keeps other values out, so reaching the throw means the
        // file was changed behind our back; failing loudly beats guessing a kind.
        fun fromDb(value: String): TokenKind {
            for (kind in entries) {
                if (kind.dbValue == value) {
                    return kind
                }
            }
            throw IllegalStateException("sync-api: unknown token kind in the database: $value")
        }
    }
}

internal enum class AgentScope(val dbValue: String) {
    TRANSCRIBE("transcribe"),
    ADMIN("admin");

    companion object {
        fun fromDb(value: String): AgentScope {
            for (scope in entries) {
                if (scope.dbValue == value) {
                    return scope
                }
            }
            throw IllegalStateException("sync-api: unknown token scope in the database: $value")
        }
    }
}
