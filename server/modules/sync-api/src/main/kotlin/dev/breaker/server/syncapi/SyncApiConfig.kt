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
    val manifestFile: String,
    val gitTag: String,
    val whisperHealthUrl: String,
    val installCodeFile: String,
) {

    companion object {
        const val ENV_HOST = "BREAKER_SYNC_API_HOST"
        const val ENV_PORT = "BREAKER_SYNC_API_PORT"
        const val ENV_DB = "BREAKER_SYNC_API_DB"
        const val ENV_INTERNAL_HOST = "BREAKER_SYNC_API_INTERNAL_HOST"
        const val ENV_INTERNAL_PORT = "BREAKER_SYNC_API_INTERNAL_PORT"
        const val ENV_SERVICE_KEY_FILE = "BREAKER_SYNC_API_SERVICE_KEY_FILE"
        const val ENV_MANIFEST_FILE = "BREAKER_SYNC_API_MANIFEST_FILE"
        const val ENV_GIT_TAG = "BREAKER_SYNC_API_GIT_TAG"
        const val ENV_WHISPER_HEALTH_URL = "BREAKER_WHISPER_HEALTH_URL"
        const val ENV_INSTALL_CODE_FILE = "BREAKER_SYNC_API_INSTALL_CODE_FILE"
        const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 8080
        private const val DEFAULT_INTERNAL_HOST = "127.0.0.1"
        private const val DEFAULT_INTERNAL_PORT = 8081
        private const val SERVICE_KEY_FILE_NAME = "service-key"
        private const val INSTALL_CODE_FILE_NAME = "install-code"

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
            val manifestFile = parseManifestFile(env[ENV_MANIFEST_FILE])
            val gitTag = parseGitTag(env[ENV_GIT_TAG])
            val whisperHealthUrl = parseWhisperHealthUrl(env[ENV_WHISPER_HEALTH_URL])
            val installCodeFile = parseInstallCodeFile(env[ENV_INSTALL_CODE_FILE], dbPath)
            refuseSameListener(host, port, internalHost, internalPort)
            return SyncApiConfig(host, port, dbPath, internalHost, internalPort, serviceKeyFile, manifestFile, gitTag, whisperHealthUrl, installCodeFile)
        }

        private fun parseInstallCodeFile(raw: String?, dbPath: String): String {
            if (raw == null) return Paths.get(dbPath).resolveSibling(INSTALL_CODE_FILE_NAME).toString()
            require(raw.isNotBlank()) { "sync-api: $ENV_INSTALL_CODE_FILE is set but blank" }
            refuseInvalidPathText(ENV_INSTALL_CODE_FILE, raw)
            return raw
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

        // The manifest file path: unset (null) means no release yet (empty string),
        // set-but-blank is refused, an invalid path text is refused like the key file.
        private fun parseManifestFile(raw: String?): String {
            if (raw == null) return ""
            require(raw.isNotBlank()) { "sync-api: $ENV_MANIFEST_FILE is set but blank" }
            refuseInvalidPathText(ENV_MANIFEST_FILE, raw)
            return raw
        }

        // The deployed server's git tag: unset means not known ("unknown"), set-but-blank is refused,
        // and anything outside 1..64 printable ASCII without spaces or control characters is refused
        // so the value the route answers stays plain. Nothing sets it today (the deploy module is not built).
        private fun parseGitTag(raw: String?): String {
            if (raw == null) return "unknown"
            require(raw.isNotBlank()) { "sync-api: $ENV_GIT_TAG is set but blank" }
            require(raw.length in 1..64) { "sync-api: $ENV_GIT_TAG must be 1 to 64 characters" }
            require(raw.all { it in '!'..'~' }) {
                "sync-api: $ENV_GIT_TAG must be printable ASCII without spaces or control characters"
            }
            return raw
        }

        // The whisper health URL: unset means whisper-server is not listed (not
        // an error). A set-but-blank value is refused; anything that is not a
        // plain http or https URL refuses the start with a config error, no
        // silent fallback.
        private fun parseWhisperHealthUrl(raw: String?): String {
            if (raw == null) return ""
            require(raw.isNotBlank()) { "sync-api: $ENV_WHISPER_HEALTH_URL is set but blank" }
            require(isValidHttpUrl(raw)) { "sync-api: $ENV_WHISPER_HEALTH_URL must be a plain http or https URL" }
            return raw
        }

        // A plain http or https URL: scheme, authority (host[:port]), optional path,
        // query or fragment. Userinfo and a bare authority without a host are not
        // accepted here; the deploy supplies a real stack-internal address.
        private fun isValidHttpUrl(text: String): Boolean {
            if (!text.startsWith("http://", ignoreCase = true) &&
                !text.startsWith("https://", ignoreCase = true)) {
                return false
            }
            val schemeEnd = text.indexOf("://") + 3
            var authorityEnd = schemeEnd
            while (authorityEnd < text.length && text[authorityEnd] != '/' &&
                   text[authorityEnd] != '?' && text[authorityEnd] != '#') {
                authorityEnd++
            }
            val authority = text.substring(schemeEnd, authorityEnd)
            // Userinfo (user:password@host) is refused: the value is logged nowhere, but a secret has no place in a URL setting.
            if (authority.contains('@')) return false
            val hostPort = authority
            if (hostPort.isEmpty() || hostPort == "[]") return false
            val host = if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) return false
                hostPort.substring(0, close + 1)
            } else {
                val colon = hostPort.indexOf(':')
                if (colon < 0) hostPort else hostPort.substring(0, colon)
            }
            return host.isNotEmpty()
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
