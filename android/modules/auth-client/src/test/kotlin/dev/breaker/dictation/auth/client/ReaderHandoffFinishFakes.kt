package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.EncryptedText
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.model.SealedSignInPackage
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.model.WrappedDek
import dev.breaker.dictation.core.port.CredentialVault
import dev.breaker.dictation.core.port.CryptoService
import java.io.IOException

/** A fake CryptoService that keeps the package and key it was given. */
class ReaderFinishCrypto : CryptoService {
    var openedPackage: SealedSignInPackage? = null
    var openedKey: SignInPrivateKey? = null
    var accountKeys: AccountKeys = AccountKeys(
        DataEncryptionKey(ByteArray(32) { 3 }),
        BoxPrivateKey(ByteArray(32) { 4 }),
    )
    var throwOnOpen: Boolean = false

    override fun unwrapDek(wrappedDek: WrappedDek, kek: KeyEncryptionKey): DataEncryptionKey {
        return DataEncryptionKey(ByteArray(32) { 5 })
    }

    override fun openSignInPackage(
        sealedPackage: SealedSignInPackage,
        signInPrivateKey: SignInPrivateKey,
    ): AccountKeys {
        openedPackage = sealedPackage
        openedKey = signInPrivateKey
        if (throwOnOpen) throw dev.breaker.dictation.core.model.CryptoRefused()
        return accountKeys
    }

    override fun encrypt(plaintext: String, dek: DataEncryptionKey, aad: ByteArray): CipherText {
        return CipherText(plaintext.toByteArray(Charsets.UTF_8), ByteArray(12), ByteArray(16))
    }

    override fun decrypt(cipherText: CipherText, dek: DataEncryptionKey, aad: ByteArray): String {
        return String(cipherText.ciphertext, Charsets.UTF_8)
    }

    override fun toEncryptedText(cipherText: CipherText): EncryptedText {
        return EncryptedText(String(cipherText.ciphertext, Charsets.UTF_8), "nonce", "tag")
    }
}

/** A fake CredentialVault that records call order and can refuse. */
class ReaderFinishCredentialVault : CredentialVault {
    var saveCalls = 0
    var wipeCalls = 0
    var saveFailure: Exception? = null
    var wipeFailure: Exception? = null

    override fun saveKeys(keys: AccountKeys, keyVersion: Int) {
        saveCalls += 1
        saveFailure?.let { throw it }
    }

    override fun readKeys(): dev.breaker.dictation.core.model.StoredAccountKeys? = null

    override fun wipeAll() {
        wipeCalls += 1
        wipeFailure?.let { throw it }
    }
}

/** A fake SessionVault that records call order and stored text. */
class ReaderFinishSessionVault : SessionVault {
    var saveCalls = 0
    var clearCalls = 0
    var stored: String? = null
    var saveFailure: Exception? = null

    override fun save(record: String) {
        saveCalls += 1
        saveFailure?.let { throw it }
        stored = record
    }

    override fun load(): String? = stored

    override fun clear() {
        clearCalls += 1
    }
}

/**
 * A fake AuthHttp that records requests and can answer or throw. It copies the body, so the
 * zeroing in HandoffFinish does not erase the recorded copy.
 */
class ReaderFinishAuthHttp {
    val exchangeRequests = mutableListOf<AuthHttpRequest>()
    val logoutRequests = mutableListOf<AuthHttpRequest>()
    var exchangeStatus = 200
    var exchangeBody = "{}"
    var throwOnExchange: Exception? = null
    var throwOnLogout: Exception? = null

    fun sendExchange(request: AuthHttpRequest): AuthHttpReply {
        exchangeRequests += AuthHttpRequest(
            request.method,
            request.url,
            request.headers,
            request.body?.copyOf(),
        )
        throwOnExchange?.let { throw it }
        return AuthHttpReply(exchangeStatus, exchangeBody.toByteArray(Charsets.UTF_8))
    }

    fun sendLogout(request: AuthHttpRequest): AuthHttpReply {
        logoutRequests += AuthHttpRequest(request.method, request.url, request.headers, null)
        throwOnLogout?.let { throw it }
        return AuthHttpReply(200, ByteArray(0))
    }
}

/** Address provider that counts reads. */
class ReaderFinishAddressProvider {
    var base: String = "https://box.example"
    var reads = 0
    fun get(): String {
        reads += 1
        return base
    }
}

private fun attempt(
    secret: String = VERIFIER_43,
    key: ByteArray = ByteArray(32) { 1 }
): SignInAttempt {
    val secretBytes = secret.toByteArray(Charsets.US_ASCII)
    val privateKey = SignInPrivateKey(key)
    val publicKey = ByteArray(32) { 2 }
    return SignInAttempt(secretBytes, privateKey, publicKey, CHALLENGE_43)
}

/** Builds an exchange answer body carrying the given token. */
fun exchangeBody(token: String = SESSION_TEXT_43): String {
    return "{\"token\":\"" + token +
            "\",\"role\":\"user\",\"key_version\":0,\"sealed_package\":\"" +
                    "ZXhhbXBsZSBzZWFsZWQgcGFja2FnZSwgbm90IGEgcmVhbCBrZXkAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\",\"username\":\"alice\",\"" +
                    "account_id\":1}"
}

/** The code used by every HandoffFinish test. */
val HANDOFF_CODE = "code-1234567890123456789012345678901234567890123"

/** A SignInAttempt whose wipe can be observed. */
fun wipingAttempt(): SignInAttempt = attempt()

const val VERIFIER_43 = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
const val CHALLENGE_43 = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
const val SESSION_TEXT_43 = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
