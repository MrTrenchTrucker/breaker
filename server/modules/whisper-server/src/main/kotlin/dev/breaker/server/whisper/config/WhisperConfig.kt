package dev.breaker.server.whisper.config

/**
 * Configuration for the whisper-server, read from environment variables.
 *
 * Env var names follow one scheme: BREAKER_<SERVICE>_<THING>:
 * - BREAKER_WHISPER_HOST: optional, default "127.0.0.1" (the only allowed default)
 * - BREAKER_WHISPER_PORT: required, must parse to Int in 1..65535
 * - BREAKER_WHISPER_DB: required, must be non-blank
 * - BREAKER_WHISPER_FORWARDER_URL: required, must be non-blank, http/https + host
 * - BREAKER_WHISPER_RESULT_TTL_S: optional, default 600 (seconds), accepted range 1..86400; a
 *   present but blank/negative/unparseable value refuses
 * - BREAKER_WHISPER_SYNC_API_URL: required, must be non-blank, http/https + host
 * - BREAKER_WHISPER_SYNC_API_SERVICE_KEY: required, must be non-blank
 * - BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN: optional, off when absent; a present value must
 *   parse to a whole number in 1..10000 (requests per minute per agent token); blank, zero,
 *   negative, non-number or above 10000 refuses at start naming the variable
 *
 * Parsing rules:
 * - Missing/bad BREAKER_WHISPER_PORT refuses with a message naming BREAKER_WHISPER_PORT
 * - Missing/bad BREAKER_WHISPER_DB refuses with a message naming BREAKER_WHISPER_DB
 * - Missing/bad BREAKER_WHISPER_FORWARDER_URL refuses with a message naming BREAKER_WHISPER_FORWARDER_URL
 * - Missing BREAKER_WHISPER_HOST defaults to "127.0.0.1"
 * - Missing BREAKER_WHISPER_RESULT_TTL_S defaults to 600; a present but blank/negative/unparseable
 *   value, or one outside 1..86400, refuses with a message naming BREAKER_WHISPER_RESULT_TTL_S
 * - Port out of range refuses with a message naming BREAKER_WHISPER_PORT
 * - Missing/blank BREAKER_WHISPER_SYNC_API_URL refuses with a message naming BREAKER_WHISPER_SYNC_API_URL
 * - Missing/blank BREAKER_WHISPER_SYNC_API_SERVICE_KEY refuses with a message naming
 *   BREAKER_WHISPER_SYNC_API_SERVICE_KEY
 * - Missing BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN means the rate limit is off; a present but
 *   blank/non-number/zero/negative/above-10000 value refuses with a message naming
 *   BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN
 */
data class WhisperConfig(
    val bindAddress: String,
    val port: Int,
    val databasePath: String,
    val forwarderBaseUrl: String,
    val resultTtlSeconds: Int = DEFAULT_RESULT_TTL_SECONDS,
    val agentRateLimitPerMin: Int? = null,
    val syncApiBaseUrl: String = "",
    val syncApiServiceKey: String = "",
) {
    companion object {
        const val ENV_HOST = "BREAKER_WHISPER_HOST"
        const val ENV_PORT = "BREAKER_WHISPER_PORT"
        const val ENV_DB = "BREAKER_WHISPER_DB"
        const val ENV_FORWARDER_URL = "BREAKER_WHISPER_FORWARDER_URL"
        const val ENV_RESULT_TTL = "BREAKER_WHISPER_RESULT_TTL_S"
        const val ENV_SYNC_API_URL = "BREAKER_WHISPER_SYNC_API_URL"
        const val ENV_SYNC_API_SERVICE_KEY = "BREAKER_WHISPER_SYNC_API_SERVICE_KEY"
        const val ENV_AGENT_RATE_LIMIT = "BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN"
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_RESULT_TTL_SECONDS = 600
        private const val MIN_RESULT_TTL_SECONDS = 1
        private const val MAX_RESULT_TTL_SECONDS = 86_400
        private const val MIN_AGENT_RATE_LIMIT = 1
        private const val MAX_AGENT_RATE_LIMIT = 10_000

        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535

        /**
         * Reads the configuration from the environment.
         * Throws IllegalStateException with a message naming the offending variable on any problem.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): WhisperConfig {
            val bindAddress = env[ENV_HOST]?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST

            val portStr = env[ENV_PORT]
                ?: throw IllegalStateException("$ENV_PORT is not set")
            val port = portStr.toIntOrNull()
                ?: throw IllegalStateException("$ENV_PORT is not a number: $portStr")
            if (port !in MIN_PORT..MAX_PORT) {
                throw IllegalStateException("$ENV_PORT is out of range ($MIN_PORT..$MAX_PORT): $port")
            }

            val databasePath = env[ENV_DB]
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("$ENV_DB is not set")

            val forwarderUrl = env[ENV_FORWARDER_URL]
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("$ENV_FORWARDER_URL is not set")
            val uri = try {
                java.net.URI(forwarderUrl)
            } catch (e: java.net.URISyntaxException) {
                throw IllegalStateException("$ENV_FORWARDER_URL is not a valid URL")
            }
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                throw IllegalStateException("$ENV_FORWARDER_URL must have an http or https scheme and a host, got scheme: ${uri.scheme}")
            }
            // A userinfo component (user:pass@) is refused at config time: it can carry
            // credentials into the downstream URL and hide a misconfigured proxy. The scheme
            // and host checks above are unchanged.
            if (uri.userInfo != null) {
                throw IllegalStateException("$ENV_FORWARDER_URL must not carry userinfo (user:pass@)")
            }

            val resultTtlRaw = env[ENV_RESULT_TTL]
            val resultTtlSeconds = if (resultTtlRaw == null) {
                DEFAULT_RESULT_TTL_SECONDS
            } else {
                val parsed = resultTtlRaw.toIntOrNull()
                    ?: throw IllegalStateException("$ENV_RESULT_TTL is not a number: $resultTtlRaw")
                if (parsed < MIN_RESULT_TTL_SECONDS) {
                    throw IllegalStateException("$ENV_RESULT_TTL must be at least $MIN_RESULT_TTL_SECONDS seconds: $resultTtlRaw")
                }
                if (parsed > MAX_RESULT_TTL_SECONDS) {
                    throw IllegalStateException("$ENV_RESULT_TTL must be at most $MAX_RESULT_TTL_SECONDS seconds: $resultTtlRaw")
                }
                parsed
            }

            val agentRateLimitRaw = env[ENV_AGENT_RATE_LIMIT]
            val agentRateLimitPerMin = if (agentRateLimitRaw == null) {
                null
            } else {
                val parsed = agentRateLimitRaw.toIntOrNull()
                    ?: throw IllegalStateException(
                "$ENV_AGENT_RATE_LIMIT is not a number: $agentRateLimitRaw"
            )
                if (parsed < MIN_AGENT_RATE_LIMIT) {
                    throw IllegalStateException(
                        "$ENV_AGENT_RATE_LIMIT must be at least $MIN_AGENT_RATE_LIMIT: $agentRateLimitRaw"
                    )
                }
                if (parsed > MAX_AGENT_RATE_LIMIT) {
                    throw IllegalStateException(
                        "$ENV_AGENT_RATE_LIMIT must be at most $MAX_AGENT_RATE_LIMIT: $agentRateLimitRaw"
                    )
                }
                parsed
            }

            val syncApiUrl = env[ENV_SYNC_API_URL]
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("$ENV_SYNC_API_URL is not set")
            val syncApiUri = try {
                java.net.URI(syncApiUrl)
            } catch (e: java.net.URISyntaxException) {
                throw IllegalStateException("$ENV_SYNC_API_URL is not a valid URL")
            }
            if (syncApiUri.scheme !in setOf("http", "https") || syncApiUri.host.isNullOrBlank()) {
                throw IllegalStateException("$ENV_SYNC_API_URL must have an http or https scheme and a host, got scheme: ${syncApiUri.scheme}")
            }

            val syncApiServiceKey = env[ENV_SYNC_API_SERVICE_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("$ENV_SYNC_API_SERVICE_KEY is not set")

            return WhisperConfig(
                bindAddress,
                port,
                databasePath,
                forwarderUrl,
                resultTtlSeconds,
                agentRateLimitPerMin = agentRateLimitPerMin,
                syncApiBaseUrl = syncApiUrl,
                syncApiServiceKey = syncApiServiceKey,
            )
        }
    }
}
