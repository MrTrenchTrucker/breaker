package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.port.AuthService
import dev.breaker.dictation.core.port.KeyDerivation
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The account service over the HTTP port: register, login, logout and the stored session.
 * The server address is read once per call and checked before any request is sent. A session is
 * stored with the address it was made with, kept as typed. It is only returned, and only signed
 * out at the server, when the address read now is the same server as the stored one. The password
 * never leaves the phone; only the derived auth verifier is sent. The derived keys are wiped
 * before a call returns. A new sign-in first revokes, best effort, the session the vault already
 * holds, at that session's own server. An unreadable vault gives no session. The JSON body of a
 * request is zeroed after it is sent. [currentAddressedSession] returns the address read now and
 * the session together; a caller that needs both uses it instead of pairing [currentSession] with
 * an address read at another moment.
 *
 * With [keyPorts], a login and a register also set up or open the account keys inside the one key
 * derivation, store the keys before the session, and, when a step fails after the token was
 * issued, log that token out and store nothing. A null [keyPorts] means no key handling.
 */
class ServerAuthService(
    private val baseUrl: () -> String,
    private val http: AuthHttp,
    private val keyDerivation: KeyDerivation,
    private val vault: SessionVault,
    private val keyPorts: AccountKeyPorts? = null,
    private val saltSource: () -> ByteArray = AccountRules::secureSalt,
) : AuthService {
    // the four calls block on the caller's thread and the port methods are not suspend, so a coroutines Mutex would need runBlocking at each site
    private val lock = ReentrantLock()
    private val jsonHeaders: List<Pair<String, String>> = listOf("Content-Type" to "application/json")
    private val registerParams = KdfParams(
        AccountRules.MEMORY_KIB, AccountRules.ITERATIONS, AccountRules.PARALLELISM,
        AccountRules.OUTPUT_LENGTH, AccountRules.KDF_VERSION,
    )

    private val ops = ServerAuthServiceOps(
        baseUrl, http, keyDerivation, vault, saltSource, jsonHeaders, registerParams, keyPorts,
    )

    override fun register(username: String, password: CharArray): AuthSession =
        try {
            lock.withLock { ops.doRegister(username, password) }
        } finally {
            password.fill('\u0000')
        }

    override fun login(username: String, password: CharArray): AuthSession =
        try {
            lock.withLock { ops.doLogin(username, password) }
        } finally {
            password.fill('\u0000')
        }

    override fun logout() {
        lock.withLock { ops.doLogout() }
    }

    override fun currentSession(): AuthSession? = currentAddressedSession()?.session

    /**
     * The device list of the signed-in account, or null when nothing was sent. The stored record
     * and the address are read once, inside the lock, and the request is sent only when a
     * readable record exists and the address read now is the same server as the stored one, so a
     * missing, undecodable or different-address record sends nothing. The bearer of the stored
     * session is the only header and there is no body. A 200 answer is decoded into rows; a
     * refusal of the token is [AuthFailureReason.BAD_CREDENTIALS]; any other status is a server
     * error. The vault is never cleared by this call.
     */
    fun devices(): List<DeviceRow>? =
        lock.withLock { ops.doDevices() }

    /**
     * The stored session together with the address read now, or null when there is no readable
     * record or the address read now is not the same server as the stored one. The address is read
     * once, inside the lock, only when a record was loaded and decoded, and is the one returned, so
     * the pair is always consistent. With no record the provider is not called. Nothing is
     * written and no request is sent. A throw from the address provider propagates, exactly as it
     * does from [currentSession].
     */
    fun currentAddressedSession(): AddressedSession? =
        lock.withLock {
            val stored = ops.loadOrNull()?.let { SessionRecord.decode(it) }
            if (stored == null) {
                null
            } else {
                val base = baseUrl()
                if (ServerAddressMatch.same(stored.server, base)) {
                    AddressedSession(base, stored.session)
                } else {
                    null
                }
            }
        }
}
