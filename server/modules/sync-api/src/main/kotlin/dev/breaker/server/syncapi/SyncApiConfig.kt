package dev.breaker.server.syncapi

import java.nio.file.InvalidPathException
import java.nio.file.Paths

internal class SyncApiConfig(
    val host: String,
    val port: Int,
    val dbPath: String,
    val internalHost: String,
    val internalPort: Int,
    val serviceKeyFile: String,
) {

    companion object {
        const val ENV_HOST = "BREAKER_SYNC_API_HOST"
        const val ENV_PORT = "BREAKER_SYNC_API_PORT"
        const val ENV_DB = "BREAKER_SYNC_API_DB"
        const val ENV_INTERNAL_HOST = "BREAKER_SYNC_API_INTERNAL_HOST"
        const val ENV_INTERNAL_PORT = "BREAKER_SYNC_API_INTERNAL_PORT"
        const val ENV_SERVICE_KEY_FILE = "BREAKER_SYNC_API_SERVICE_KEY_FILE"
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8080
        const val DEFAULT_INTERNAL_HOST = "127.0.0.1"
        const val DEFAULT_INTERNAL_PORT = 8081
        const val SERVICE_KEY_FILE_NAME = "service-key"

        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535

        // Spellings that mean this machine. Two listeners on the same port with these hosts collide.
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

        // Pure on purpose: the caller passes the environment in, so a test needs no process state.
        fun fromEnv(env: Map<String, String>): SyncApiConfig {
            val host = parseHost(env[ENV_HOST])
            val port = parsePort(env[ENV_PORT])
            val internalHost = parseInternalHost(env[ENV_INTERNAL_HOST])
            val internalPort = parseInternalPort(env[ENV_INTERNAL_PORT])
            val dbPath = parseDbPath(env[ENV_DB])
            val serviceKeyFile = parseServiceKeyFile(env[ENV_SERVICE_KEY_FILE], dbPath)
            refuseSameListener(host, port, internalHost, internalPort)
            return SyncApiConfig(host, port, dbPath, internalHost, internalPort, serviceKeyFile)
        }

        private fun parseHost(raw: String?): String {
            if (raw == null) return DEFAULT_HOST
            // A set-but-blank value is a mistake, not a request for the default.
            require(raw.isNotBlank()) { "sync-api: $ENV_HOST is set but blank" }
            return raw
        }

        private fun parseInternalHost(raw: String?): String {
            if (raw == null) return DEFAULT_INTERNAL_HOST
            require(raw.isNotBlank()) { "sync-api: $ENV_INTERNAL_HOST is set but blank" }
            require(!isWildcardHost(raw)) { "sync-api: $ENV_INTERNAL_HOST must be a loopback or stack-internal address, not a wildcard" }
            return raw
        }

        // The one wildcard test. After trimming and lower-casing, the text is not empty and uses only the
        // characters 0 : . [ ]. That covers 0.0.0.0, ::, [::], ::0, 0:0:0:0:0:0:0:0, [0::0] and 0.
        // Such a host binds every address. A loopback spelling or a name always has another character.
        private fun isWildcardHost(text: String): Boolean {
            val folded = text.trim().lowercase()
            return folded.isNotEmpty() && folded.all { it in "0:.[]" }
        }

        private fun parsePort(raw: String?): Int = parsePortVariable(ENV_PORT, raw, DEFAULT_PORT)

        private fun parseInternalPort(raw: String?): Int = parsePortVariable(ENV_INTERNAL_PORT, raw, DEFAULT_INTERNAL_PORT)

        // Shared by both listeners: the same whole-number rule, with the variable named in the refusal.
        private fun parsePortVariable(variable: String, raw: String?, fallback: Int): Int {
            if (raw == null) return fallback
            // Explicit ASCII digits only: toIntOrNull alone would accept "+80" and "-1",
            // and Char.isDigit would accept non-ASCII digits.
            val plainDecimal = raw.isNotEmpty() && raw.all { it in '0'..'9' }
            // toIntOrNull returns null on overflow, which covers very long digit strings.
            val value: Int? = if (plainDecimal) raw.toIntOrNull() else null
            if (value == null || value < MIN_PORT || value > MAX_PORT) {
                throw IllegalArgumentException("sync-api: $variable must be a whole number from $MIN_PORT to $MAX_PORT")
            }
            return value
        }

        private fun parseDbPath(raw: String?): String {
            if (raw == null || raw.isBlank()) {
                throw IllegalArgumentException("sync-api: $ENV_DB is required (path of the SQLite file)")
            }
            refuseInvalidPathText(ENV_DB, raw)
            return raw
        }

        // The default key file sits next to the database file. A bare database name has no folder,
        // so the default is the bare key file name, in the current folder.
        private fun parseServiceKeyFile(raw: String?, dbPath: String): String {
            if (raw == null) return Paths.get(dbPath).resolveSibling(SERVICE_KEY_FILE_NAME).toString()
            require(raw.isNotBlank()) { "sync-api: $ENV_SERVICE_KEY_FILE is set but blank" }
            refuseInvalidPathText(ENV_SERVICE_KEY_FILE, raw)
            return raw
        }

        // A path text the file system rejects (a NUL character, for example) is refused with a fresh message.
        // The JDK message repeats the text, so this refusal never copies the JDK message or the input.
        private fun refuseInvalidPathText(variable: String, text: String) {
            try {
                Paths.get(text)
            } catch (_: InvalidPathException) {
                throw IllegalArgumentException("sync-api: $variable is not a valid file path")
            }
        }

        // Refused when the two listeners would both take one port on the same address: the hosts are
        // equal after trimming and lower-casing, or the public host is a wildcard, or both hosts are
        // loopback spellings. Different ports never collide.
        private fun refuseSameListener(host: String, port: Int, internalHost: String, internalPort: Int) {
            if (port != internalPort) return
            val publicName = host.trim().lowercase()
            val internalName = internalHost.trim().lowercase()
            val sameHost = publicName == internalName
            val publicWildcard = isWildcardHost(host)
            val bothLoopback = publicName in LOOPBACK_HOSTS && internalName in LOOPBACK_HOSTS
            require(!(sameHost || publicWildcard || bothLoopback)) { "sync-api: $ENV_INTERNAL_PORT and $ENV_PORT name the same listener; use different ports" }
        }
    }
}
