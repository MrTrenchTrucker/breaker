package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AuthHttp
import dev.breaker.dictation.auth.client.AuthHttpReply
import dev.breaker.dictation.auth.client.AuthHttpRequest
import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.AuthVerifier
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.EncryptedText
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.SealedSignInPackage
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.model.WrappedDek
import dev.breaker.dictation.core.port.CryptoService
import dev.breaker.dictation.core.port.KeyDerivation
import java.util.Base64

/** The session text the fake exchange hands out: 43 URL-safe characters. */
internal val HR_SESSION_TEXT: String = "S".repeat(43)

/** The one-time code of a return link: 43 URL-safe characters. */
internal val HR_CODE: String = "Q".repeat(43)

/** What a fake transport saw of one request. The body text is copied at send time, before it is zeroed. */
internal class HrSent(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val bodyText: String,
)

/** A transport that records each request and answers it with [answer]; [answer] may throw. */
internal class HrHttp(private val answer: (String) -> AuthHttpReply) : AuthHttp {
    val sent: MutableList<HrSent> = mutableListOf()

    override fun send(request: AuthHttpRequest): AuthHttpReply {
        val bodyText: String = request.body?.let { String(it, Charsets.UTF_8) } ?: ""
        sent.add(HrSent(request.method, request.url, request.headers, bodyText))
        return answer(request.url)
    }
}

/** The data key the fake crypto service opens out of the package. */
internal fun hrDekBytes(): ByteArray = ByteArray(32) { (it + 40).toByte() }

/** The box key the fake crypto service opens out of the package. */
internal fun hrBoxBytes(): ByteArray = ByteArray(32) { (it + 140).toByte() }

/** The body of an exchange answer that the strict reader accepts. */
internal fun hrExchangeJson(sessionText: String, keyVersion: Int, packageBytes: ByteArray): String {
    val sealed: String = Base64.getEncoder().encodeToString(packageBytes)
    return """{"token":"$sessionText","role":"user","key_version":$keyVersion,""" +
        "\"sealed_package\":\"$sealed\",\"username\":\"ada\",\"account_id\":7}"
}

/** A crypto service that opens a package into the known keys, or throws [refusal]; it notes what it saw. */
internal class HrFakeCrypto(private val refusal: Exception? = null) : CryptoService {
    var opened: Int = 0
    var sealedSize: Int = -1
    var privateKeyBytes: ByteArray = ByteArray(0)

    override fun openSignInPackage(
        sealedPackage: SealedSignInPackage,
        signInPrivateKey: SignInPrivateKey,
    ): AccountKeys {
        opened++
        sealedSize = sealedPackage.copyBytes().size
        privateKeyBytes = signInPrivateKey.copyBytes()
        refusal?.let { throw it }
        return AccountKeys(DataEncryptionKey(hrDekBytes()), BoxPrivateKey(hrBoxBytes()))
    }

    override fun unwrapDek(wrappedDek: WrappedDek, kek: KeyEncryptionKey): DataEncryptionKey =
        throw UnsupportedOperationException("not used")

    override fun encrypt(plaintext: String, dek: DataEncryptionKey, aad: ByteArray): CipherText =
        throw UnsupportedOperationException("not used")

    override fun decrypt(cipherText: CipherText, dek: DataEncryptionKey, aad: ByteArray): String =
        throw UnsupportedOperationException("not used")

    override fun toEncryptedText(cipherText: CipherText): EncryptedText =
        throw UnsupportedOperationException("not used")
}

/** A key derivation that returns fixed keys, so a password sign-in needs no real derivation. */
internal class HrKeyDerivation : KeyDerivation {
    override fun deriveKeys(password: CharArray, salt: ByteArray, kdfParams: KdfParams): DerivedKeys =
        DerivedKeys(KeyEncryptionKey(ByteArray(32) { 1 }), AuthVerifier(ByteArray(32) { 2 }))
}

/** The login transport: it answers the salt call, the login call and a logout call. */
internal fun hrLoginHttp(): HrHttp {
    val salt: String = Base64.getEncoder().encodeToString(ByteArray(16))
    val saltBody = """{"salt":"$salt","kdf_params":""" +
        """{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1}"""
    val loginBody = """{"token":"tok-1","role":"admin","key_version":0}"""
    return HrHttp { url ->
        when {
            url.contains("/v1/auth/salt") -> AuthHttpReply(200, saltBody.toByteArray())
            url.contains("/v1/auth/login") -> AuthHttpReply(200, loginBody.toByteArray())
            else -> AuthHttpReply(200, ByteArray(0))
        }
    }
}
