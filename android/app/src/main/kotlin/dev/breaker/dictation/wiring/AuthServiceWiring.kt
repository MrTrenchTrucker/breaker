package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AuthHttp
import dev.breaker.dictation.auth.client.ServerAuthService
import dev.breaker.dictation.core.port.KeyDerivation
import dev.breaker.dictation.crypto.KdfImpl
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.JdkHttpTransport
import java.io.File
import javax.crypto.SecretKey

/**
 * The auth service wiring: the one app file that names the transport, the key derivation and the
 * session file. Each default is a plain function, so the composition root can hand it over as a
 * factory and build nothing until the auth service is first used. Plain JVM-testable.
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

/** The real key derivation. */
internal fun defaultKeyDerivation(): KeyDerivation = KdfImpl()

/** The key the session file is sealed to, from the platform keystore; it may throw. */
internal fun defaultSessionKey(): SecretKey = KeystoreSessionKey.get()

/** The file that holds the sealed session, inside the app files folder. */
internal fun authSessionFile(filesDir: File): File = File(filesDir, "session.vault")

/**
 * Builds the auth service over the given address provider, session file, key supplier, transport
 * and key derivation. The address provider is read by the service on every call.
 */
internal fun buildAuthService(
    baseUrl: () -> String,
    sessionFile: File,
    keySupplier: () -> SecretKey,
    http: AuthHttp,
    keyDerivation: KeyDerivation,
): ServerAuthService = ServerAuthService(
    baseUrl,
    http,
    keyDerivation,
    FileSessionVault(sessionFile, AesSessionSeal(keySupplier)),
)
