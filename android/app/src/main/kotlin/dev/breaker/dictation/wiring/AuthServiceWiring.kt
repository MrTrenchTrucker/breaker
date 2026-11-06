package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AuthHttp
import dev.breaker.dictation.auth.client.ServerAuthService
import dev.breaker.dictation.auth.client.SessionVault
import dev.breaker.dictation.core.port.CryptoService
import dev.breaker.dictation.core.port.KeyDerivation
import dev.breaker.dictation.crypto.CryptoServiceImpl
import dev.breaker.dictation.crypto.KdfImpl
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.JdkHttpTransport
import java.io.File
import javax.crypto.SecretKey

/**
 * The auth service wiring: the one app file that names the transport, the crypto service, the key
 * derivation and the vault file names. Each default is a plain function, so the composition root
 * can hand it over as a factory and build nothing until the auth service is first used. Plain
 * JVM-testable.
 */

/**
 * The login transport: the platform client, with the three login limits of [AuthHttpAdapter].
 * Plain http is allowed for the saved address, which [configuredServer] returns; the transport reads it
 * on every request and never keeps it. An https address is allowed for any host.
 */
internal fun defaultAuthHttp(configuredServer: () -> String): AuthHttp =
    AuthHttpAdapter(
        JdkHttpTransport(configuredServer),
        CallLimits(AUTH_CONNECT_TIMEOUT_MS, AUTH_READ_TIMEOUT_MS, AUTH_MAX_REPLY_BYTES),
    )

// The hand-off exchange takes its own limits: the reply holds a token and a sealed key package, so
// 4 KiB is ample, and the wait is up to 10 s to connect and 15 s to read. The logout that undoes a
// failed hand-off keeps small limits of its own, because nothing waits on it.
internal const val EXCHANGE_CONNECT_TIMEOUT_MS = 10_000
internal const val EXCHANGE_READ_TIMEOUT_MS = 15_000
internal const val EXCHANGE_MAX_REPLY_BYTES = 4096L
internal const val LOGOUT_CONNECT_TIMEOUT_MS = 5_000
internal const val LOGOUT_READ_TIMEOUT_MS = 10_000
internal const val LOGOUT_MAX_REPLY_BYTES = 1_024L

/**
 * The hand-off exchange transport: the platform client with the exchange limits. The saved address
 * is read from [configuredServer] on every request, as for the login transport.
 */
internal fun defaultExchangeHttp(configuredServer: () -> String): AuthHttp =
    AuthHttpAdapter(
        JdkHttpTransport(configuredServer),
        CallLimits(EXCHANGE_CONNECT_TIMEOUT_MS, EXCHANGE_READ_TIMEOUT_MS, EXCHANGE_MAX_REPLY_BYTES),
    )

/** The logout transport of the hand-off finish: a separate instance with the small logout limits. */
internal fun defaultLogoutHttp(configuredServer: () -> String): AuthHttp =
    AuthHttpAdapter(
        JdkHttpTransport(configuredServer),
        CallLimits(LOGOUT_CONNECT_TIMEOUT_MS, LOGOUT_READ_TIMEOUT_MS, LOGOUT_MAX_REPLY_BYTES),
    )

/** The real crypto service. */
internal fun defaultCryptoService(): CryptoService = CryptoServiceImpl()

/** The real key derivation. */
internal fun defaultKeyDerivation(): KeyDerivation = KdfImpl()

/** The key the session file is sealed to, from the platform keystore; it may throw. */
internal fun defaultSessionKey(): SecretKey = KeystoreSessionKey.get()

/** The file that holds the sealed session, inside the app files folder. */
internal fun authSessionFile(filesDir: File): File = File(filesDir, "session.vault")

/** The file that holds the sealed account keys, inside the app files folder. */
internal fun credentialsFile(filesDir: File): File = File(filesDir, "credentials.vault")

/**
 * Builds the auth service over the given address provider, session vault, transport and key
 * derivation. The address provider is read by the service on every call.
 */
internal fun buildAuthService(
    baseUrl: () -> String,
    vault: SessionVault,
    http: AuthHttp,
    keyDerivation: KeyDerivation,
): ServerAuthService = ServerAuthService(
    baseUrl,
    http,
    keyDerivation,
    vault,
)
