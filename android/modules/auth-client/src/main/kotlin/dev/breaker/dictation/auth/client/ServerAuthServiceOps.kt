package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused
import dev.breaker.dictation.core.port.KeyDerivation
import dev.breaker.shared.api.LoginRequest
import dev.breaker.shared.api.RegisterRequest
import dev.breaker.shared.api.SaltRequest
import dev.breaker.shared.api.SaltResponse
import java.io.IOException
import java.util.Base64

internal class ServerAuthServiceOps(
    private val baseUrl: () -> String,
    private val http: AuthHttp,
    private val keyDerivation: KeyDerivation,
    private val vault: SessionVault,
    private val saltSource: () -> ByteArray,
    private val jsonHeaders: List<Pair<String, String>>,
    private val registerParams: KdfParams,
) {

    internal fun doRegister(username: String, password: CharArray): AuthSession {
        val base = baseUrl()
        val registerUrl = resolve(base, AccountRules.REGISTER_PATH)
        requireUsername(username)
        if (!AccountRules.isLongEnough(password)) throw AuthFailure(AuthFailureReason.WEAK_PASSWORD)
        val salt = saltSource()
        if (salt.size != AccountRules.SALT_BYTES) throw KdfRefused(KdfRefusal.BAD_SALT_LENGTH)
        val session = withVerifier(password, salt, registerParams) { verifier ->
            postRegister(registerUrl, username, salt, verifier)
            loginWith(base, username, verifier)
        }
        revokePrevious()
        store(base, session)
        return session
    }

    internal fun doLogin(username: String, password: CharArray): AuthSession {
        val base = baseUrl()
        val saltUrl = resolve(base, AccountRules.SALT_PATH)
        requireUsername(username)
        val reply = postSalt(saltUrl, username)
        val salt = Base64.getDecoder().decode(reply.salt)
        val params = KdfParams(
            reply.kdfParams.memoryKib,
            reply.kdfParams.iterations,
            reply.kdfParams.parallelism,
            AccountRules.OUTPUT_LENGTH,
            reply.kdfVersion,
        )
        val session = withVerifier(password, salt, params) { verifier -> loginWith(base, username, verifier) }
        revokePrevious()
        store(base, session)
        return session
    }

    internal fun doLogout() {
        val stored = readThenClear()
        val base = baseUrl()
        val record = stored?.let { SessionRecord.decode(it) } ?: return
        if (!ServerAddressMatch.same(record.server, base)) return
        val url = AccountRules.endpoint(base, AccountRules.LOGOUT_PATH) ?: return
        bestEffortLogout(url, record.session.token)
    }

    /**
     * The device list read. The record is loaded and the address is read once, inside the lock;
     * the request is sent only when a readable record exists and the address read now is the same
     * server as the stored one. Nothing is stored and the vault is never cleared.
     */
    internal fun doDevices(): List<DeviceRow>? {
        val stored = loadOrNull() ?: return null
        val record = SessionRecord.decode(stored) ?: return null
        val base = baseUrl()
        if (!ServerAddressMatch.same(record.server, base)) return null
        val url = AccountRules.endpoint(base, AccountRules.DEVICES_PATH) ?: return null
        val reply = bearerGet(url, record.session.token)
        return ServerAuthReplies.devices(reply)
    }

    /** The stored record text, or null when the vault is empty or cannot be read. */
    internal fun loadOrNull(): String? =
        try {
            vault.load()
        } catch (unreadable: Exception) {
            null
        }

    /**
     * Revokes, best effort, the session the vault already holds. The request goes to that record's
     * own server with that record's own bearer, never to the address read now. A missing, unreadable
     * or undecodable record, or a record whose server gives no endpoint, sends nothing.
     */
    private fun revokePrevious() {
        val record = loadOrNull()?.let { SessionRecord.decode(it) } ?: return
        val url = AccountRules.endpoint(record.server, AccountRules.LOGOUT_PATH) ?: return
        bestEffortLogout(url, record.session.token)
    }

    /** Reads the stored record, then clears the vault. A failing read still clears, and sends nothing. */
    private fun readThenClear(): String? {
        val loaded = try {
            vault.load()
        } catch (loadFailure: Exception) {
            try {
                vault.clear()
            } catch (clearFailure: Exception) {
                loadFailure.addSuppressed(clearFailure)
            }
            throw loadFailure
        }
        vault.clear()
        return loaded
    }

    /** Derives the keys once, runs [block] with the base64 text of the verifier, then wipes the keys. */
    private fun <T> withVerifier(password: CharArray, salt: ByteArray, params: KdfParams, block: (String) -> T): T {
        val keys = keyDerivation.deriveKeys(password, salt, params)
        return try {
            val raw = keys.authVerifier.copyBytes()
            try {
                block(Base64.getEncoder().encodeToString(raw))
            } finally {
                raw.fill(0)
            }
        } finally {
            keys.wipe()
        }
    }

    private fun loginWith(base: String, username: String, verifier: String): AuthSession {
        val url = resolve(base, AccountRules.LOGIN_PATH)
        val body = RequestEncoder.encodeLogin(LoginRequest(username, verifier))
        return ServerAuthReplies.session(username, ServerAuthReplies.login(jsonPost(url, body)))
    }

    private fun postSalt(url: String, username: String): SaltResponse {
        val body = RequestEncoder.encodeSalt(SaltRequest(username))
        return ServerAuthReplies.salt(jsonPost(url, body))
    }

    private fun postRegister(url: String, username: String, salt: ByteArray, verifier: String) {
        val sharedParams = dev.breaker.shared.api.KdfParams(
            AccountRules.MEMORY_KIB, AccountRules.ITERATIONS, AccountRules.PARALLELISM,
        )
        val request = RegisterRequest(
            username, Base64.getEncoder().encodeToString(salt), sharedParams, AccountRules.KDF_VERSION, verifier,
        )
        val reply = jsonPost(url, RequestEncoder.encodeRegister(request))
        when (reply.status) {
            201 -> Unit
            409 -> throw AuthFailure(AuthFailureReason.USERNAME_TAKEN)
            429 -> throw AuthFailure(AuthFailureReason.RATE_LIMITED, ServerAuthReplies.retryAfterSeconds(reply.headers))
            else -> throw AuthFailure(AuthFailureReason.SERVER_ERROR)
        }
    }

    /**
     * The device list request: a GET with no body, the bearer the only header. The reply body is
     * not zeroed here; it is decoded into rows and not kept.
     */
    private fun bearerGet(url: String, bearer: String): AuthHttpReply {
        val headers: List<Pair<String, String>> = listOf("Authorization" to "Bearer $bearer")
        return try {
            http.send(AuthHttpRequest("GET", url, headers, null))
        } catch (unreachable: IOException) {
            throw AuthFailure(AuthFailureReason.SERVER_UNREACHABLE)
        } catch (portFailure: RuntimeException) {
            throw AuthFailure(AuthFailureReason.SERVER_ERROR)
        }
    }

    /** Sends the body, then zeroes it: the array holds the auth verifier and is not read again. */
    private fun jsonPost(url: String, body: ByteArray): AuthHttpReply =
        try {
            http.send(AuthHttpRequest("POST", url, jsonHeaders, body))
        } catch (unreachable: IOException) {
            throw AuthFailure(AuthFailureReason.SERVER_UNREACHABLE)
        } catch (portFailure: RuntimeException) {
            throw AuthFailure(AuthFailureReason.SERVER_ERROR)
        } finally {
            body.fill(0)
        }

    /** The logout request is best effort: its reply is not read, and a failure of the port is ignored. */
    private fun bestEffortLogout(url: String, bearer: String) {
        try {
            http.send(AuthHttpRequest("POST", url, listOf("Authorization" to "Bearer $bearer"), null))
        } catch (offline: Exception) {
            // a failure of the port does not undo the local sign-out
        }
    }

    /**
     * Stores the session. If the save fails, the vault is cleared on a best-effort basis, the token
     * is revoked on a best-effort basis, then the failure is rethrown.
     */
    private fun store(base: String, session: AuthSession) {
        try {
            vault.save(SessionRecord.encode(session, base))
        } catch (saveFailure: Exception) {
            try {
                vault.clear()
            } catch (clearFailure: Exception) {
                saveFailure.addSuppressed(clearFailure)
            }
            bestEffortLogout(resolve(base, AccountRules.LOGOUT_PATH), session.token)
            throw saveFailure
        }
    }

    private fun resolve(base: String, path: String): String =
        AccountRules.endpoint(base, path) ?: throw AuthFailure(AuthFailureReason.BAD_SERVER_ADDRESS)

    private fun requireUsername(username: String) {
        if (!AccountRules.isValidUsername(username)) throw AuthFailure(AuthFailureReason.BAD_USERNAME)
    }
}
