package dev.breaker.server.syncapi

internal class SyncApiConfig(val host: String, val port: Int, val dbPath: String) {

    companion object {
        const val ENV_HOST = "BREAKER_SYNC_API_HOST"
        const val ENV_PORT = "BREAKER_SYNC_API_PORT"
        const val ENV_DB = "BREAKER_SYNC_API_DB"
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8080

        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535

        // Pure on purpose: the caller passes the environment in, so a test needs no process state.
        fun fromEnv(env: Map<String, String>): SyncApiConfig {
            val host = parseHost(env[ENV_HOST])
            val port = parsePort(env[ENV_PORT])
            val dbPath = parseDbPath(env[ENV_DB])
            return SyncApiConfig(host, port, dbPath)
        }

        private fun parseHost(raw: String?): String {
            if (raw == null) return DEFAULT_HOST
            // A set-but-blank value is a mistake, not a request for the default.
            require(raw.isNotBlank()) { "sync-api: $ENV_HOST is set but blank" }
            return raw
        }

        private fun parsePort(raw: String?): Int {
            if (raw == null) return DEFAULT_PORT
            // Explicit ASCII digits only: toIntOrNull alone would accept "+80" and "-1",
            // and Char.isDigit would accept non-ASCII digits.
            val plainDecimal = raw.isNotEmpty() && raw.all { it in '0'..'9' }
            // toIntOrNull returns null on overflow, which covers very long digit strings.
            val value: Int? = if (plainDecimal) raw.toIntOrNull() else null
            if (value == null || value < MIN_PORT || value > MAX_PORT) {
                throw IllegalArgumentException("sync-api: $ENV_PORT must be a whole number from $MIN_PORT to $MAX_PORT")
            }
            return value
        }

        private fun parseDbPath(raw: String?): String {
            if (raw == null || raw.isBlank()) {
                throw IllegalArgumentException("sync-api: $ENV_DB is required (path of the SQLite file)")
            }
            return raw
        }
    }
}
