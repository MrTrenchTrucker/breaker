package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.BoxKeyPair
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.EncryptedText
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.SealedAgentResult
import dev.breaker.dictation.core.model.SealedSignInPackage
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.model.WrappedBoxPrivateKey
import dev.breaker.dictation.core.model.WrappedDek
import dev.breaker.dictation.core.model.WrappedPreviousDek
import dev.breaker.dictation.core.port.AccountKeyService
import dev.breaker.dictation.core.port.CryptoService
import dev.breaker.dictation.core.port.KeyDerivation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows

/** The keys endpoint of the test server. */
internal const val KEY_KEYS_URL = BASE_URL + "/v1/auth/keys"

/** The mask the fake data key wrap uses. */
internal const val KEY_DEK_MASK = 0x5A

/** The mask the fake box key wrap uses. */
internal const val KEY_BOX_MASK = 0x3C

/** The three optional members of a login reply, all null. */
internal const val KEY_NULL_EXTRAS = ",\"prev_wrapped_dek\":null,\"reset_at\":null,\"rekeyed_at\":null"

/** [size] bytes counting up from [start], one byte each. */
internal fun keyPattern(size: Int, start: Int): ByteArray = ByteArray(size) { (start + it).toByte() }

/**
 * The fake wrap: 60 bytes. The first 32 are the secret xor the key xor [mask]; the last 28 are the
 * key xor [mask] xor 0xFF, so a wrong key or a changed tail byte is seen when the wrap is opened.
 */
internal fun keyFakeWrap(secret: ByteArray, key: ByteArray, mask: Int): ByteArray =
    ByteArray(60) {
        if (it < 32) {
            (secret[it].toInt() xor key[it].toInt() xor mask).toByte()
        } else {
            (key[it - 32].toInt() xor mask xor 0xFF).toByte()
        }
    }

/** Opens [keyFakeWrap]. Throws [CryptoRefused] on a wrong size, a wrong key or a changed tail byte. */
internal fun keyFakeUnwrap(wrapped: ByteArray, key: ByteArray, mask: Int): ByteArray {
    if (wrapped.size != 60) throw CryptoRefused()
    for (k in 0 until 28) {
        if (wrapped[32 + k] != (key[k].toInt() xor mask xor 0xFF).toByte()) throw CryptoRefused()
    }
    return ByteArray(32) { (wrapped[it].toInt() xor key[it].toInt() xor mask).toByte() }
}

/** The wrapped data key the fake makes under the fake derivation's key-encryption key. */
internal fun keyWrappedDek(): ByteArray = keyFakeWrap(keyPattern(32, 0xA0), FAKE_KEK_BYTES, KEY_DEK_MASK)

/** The wrapped box private key the fake makes under the fake data key. */
internal fun keyWrappedBox(): ByteArray =
    keyFakeWrap(keyPattern(32, 0x30), keyPattern(32, 0xA0), KEY_BOX_MASK)

/** The box public key that matches the fake box private key. */
internal fun keyPublic(): ByteArray = keyPattern(32, 0x70)

/**
 * Test double for the account key port. Every member appends its name to the shared log. The
 * members the flow uses are deterministic and reversible; the rest throw. Every object and array it
 * handed out or was given is kept in a public list, so a test can read it after the call.
 * When [failOn] names a member, that member throws [CryptoRefused] after it logged its name.
 */
class FakeAccountKeys(private val log: MutableList<String>) : AccountKeyService {
    var failOn: String? = null
    val deks: MutableList<DataEncryptionKey> = mutableListOf()
    val pairs: MutableList<BoxKeyPair> = mutableListOf()
    val wrappedDeks: MutableList<WrappedDek> = mutableListOf()
    val wrappedBoxes: MutableList<WrappedBoxPrivateKey> = mutableListOf()
    val unwrappedBoxes: MutableList<BoxPrivateKey> = mutableListOf()
    val boxWrapsSeen: MutableList<WrappedBoxPrivateKey> = mutableListOf()
    val publicSeen: MutableList<ByteArray> = mutableListOf()

    private fun enter(name: String) {
        log += name
        if (failOn == name) throw CryptoRefused()
    }

    override fun generateDataKey(): DataEncryptionKey {
        enter("generateDataKey")
        val key = DataEncryptionKey(keyPattern(32, 0xA0))
        deks += key
        return key
    }

    override fun generateBoxKeyPair(): BoxKeyPair {
        enter("generateBoxKeyPair")
        val pair = BoxKeyPair(BoxPrivateKey(keyPattern(32, 0x30)), keyPublic())
        pairs += pair
        return pair
    }

    override fun wrapDek(dek: DataEncryptionKey, kek: KeyEncryptionKey, salt: ByteArray): WrappedDek {
        enter("wrapDek")
        val kekBytes = kek.copyBytes()
        try {
            val wrapped = WrappedDek(keyFakeWrap(dek.bytes, kekBytes, KEY_DEK_MASK), salt.copyOf())
            wrappedDeks += wrapped
            return wrapped
        } finally {
            kekBytes.fill(0)
        }
    }

    override fun wrapBoxPrivateKey(
        boxPrivateKey: BoxPrivateKey,
        dek: DataEncryptionKey,
    ): WrappedBoxPrivateKey {
        enter("wrapBoxPrivateKey")
        val boxBytes = boxPrivateKey.copyBytes()
        try {
            val wrapped = WrappedBoxPrivateKey(keyFakeWrap(boxBytes, dek.bytes, KEY_BOX_MASK))
            wrappedBoxes += wrapped
            return wrapped
        } finally {
            boxBytes.fill(0)
        }
    }

    override fun unwrapBoxPrivateKey(wrapped: WrappedBoxPrivateKey, dek: DataEncryptionKey): BoxPrivateKey {
        enter("unwrapBoxPrivateKey")
        boxWrapsSeen += wrapped
        val secret = keyFakeUnwrap(wrapped.bytes, dek.bytes, KEY_BOX_MASK)
        try {
            val key = BoxPrivateKey(secret)
            unwrappedBoxes += key
            return key
        } finally {
            secret.fill(0)
        }
    }

    override fun wrapPreviousDek(
        previousDek: DataEncryptionKey,
        newDek: DataEncryptionKey,
    ): WrappedPreviousDek {
        enter("wrapPreviousDek")
        throw UnsupportedOperationException("the fake does not wrap previous keys")
    }

    override fun unwrapPreviousDek(
        wrapped: WrappedPreviousDek,
        newDek: DataEncryptionKey,
    ): DataEncryptionKey {
        enter("unwrapPreviousDek")
        throw UnsupportedOperationException("the fake does not open previous keys")
    }

    override fun verifyBoxPublicKey(boxPrivateKey: BoxPrivateKey, publicKey: ByteArray) {
        enter("verifyBoxPublicKey")
        publicSeen += publicKey
        val opened = boxPrivateKey.copyBytes()
        val matches = opened.contentEquals(keyPattern(32, 0x30)) && publicKey.contentEquals(keyPublic())
        opened.fill(0)
        if (!matches) throw CryptoRefused()
    }

    override fun unsealAndReencrypt(
        sealed: SealedAgentResult,
        boxPrivateKey: BoxPrivateKey,
        dek: DataEncryptionKey,
        aad: ByteArray,
    ): CipherText {
        enter("unsealAndReencrypt")
        throw UnsupportedOperationException("the fake does not unseal")
    }

    override fun reencrypt(
        cipherText: CipherText,
        oldDek: DataEncryptionKey,
        oldAad: ByteArray,
        newDek: DataEncryptionKey,
        newAad: ByteArray,
    ): CipherText {
        enter("reencrypt")
        throw UnsupportedOperationException("the fake does not re-encrypt")
    }

    override fun encryptBytes(plain: ByteArray, dek: DataEncryptionKey, aad: ByteArray): CipherText {
        enter("encryptBytes")
        throw UnsupportedOperationException("the fake does not encrypt bytes")
    }

    override fun decryptBytes(cipherText: CipherText, dek: DataEncryptionKey, aad: ByteArray): ByteArray {
        enter("decryptBytes")
        throw UnsupportedOperationException("the fake does not decrypt bytes")
    }
}

/**
 * Test double for the crypto port. Only [unwrapDek] works: it opens what [FakeAccountKeys.wrapDek]
 * made. It logs its name and keeps the wrapped objects it was given and the keys it returned.
 */
class KeyFakeCrypto(private val log: MutableList<String>) : CryptoService {
    val deks: MutableList<DataEncryptionKey> = mutableListOf()
    val wrappedSeen: MutableList<WrappedDek> = mutableListOf()

    override fun unwrapDek(wrappedDek: WrappedDek, kek: KeyEncryptionKey): DataEncryptionKey {
        log += "unwrapDek"
        wrappedSeen += wrappedDek
        val kekBytes = kek.copyBytes()
        try {
            val key = DataEncryptionKey(keyFakeUnwrap(wrappedDek.bytes, kekBytes, KEY_DEK_MASK))
            deks += key
            return key
        } finally {
            kekBytes.fill(0)
        }
    }

    override fun openSignInPackage(
        sealedPackage: SealedSignInPackage,
        signInPrivateKey: SignInPrivateKey,
    ): AccountKeys = throw UnsupportedOperationException("the key fake crypto opens data keys only")

    override fun encrypt(plaintext: String, dek: DataEncryptionKey, aad: ByteArray): CipherText =
        throw UnsupportedOperationException("the key fake crypto opens data keys only")

    override fun decrypt(cipherText: CipherText, dek: DataEncryptionKey, aad: ByteArray): String =
        throw UnsupportedOperationException("the key fake crypto opens data keys only")

    override fun toEncryptedText(cipherText: CipherText): EncryptedText =
        throw UnsupportedOperationException("the key fake crypto opens data keys only")
}

/** A session vault that appends "session.save" to [log] for each save and delegates to [inner]. */
class KeyLoggingSessionVault(
    private val inner: SessionVault,
    private val log: MutableList<String>,
) : SessionVault {
    override fun save(record: String) {
        log += "session.save"
        inner.save(record)
    }

    override fun load(): String? = inner.load()

    override fun clear() {
        inner.clear()
    }
}

/** A keys reply body: only the key version. */
fun keysReplyBody(version: Int): String = "{\"key_version\":" + version + "}"

/** A login reply body whose three key members are the given text, with [extras] after the version. */
fun loginReplyWithKeyTexts(
    bearer: String,
    dekText: String,
    boxText: String,
    pubText: String,
    keyVersion: Int,
    extras: String = KEY_NULL_EXTRAS,
): String =
    "{\"token\":\"" + bearer + "\",\"role\":\"user\",\"wrapped_dek\":\"" + dekText +
        "\",\"wrapped_box_privkey\":\"" + boxText + "\",\"box_pubkey\":\"" + pubText +
        "\",\"key_version\":" + keyVersion + extras + "}"

/** A login reply body that carries the three wrapped keys as standard padded base64. */
fun loginReplyWithKeysBody(
    bearer: String,
    wrappedDek: ByteArray,
    wrappedBox: ByteArray,
    pub: ByteArray,
    keyVersion: Int,
    extras: String = KEY_NULL_EXTRAS,
): String =
    loginReplyWithKeyTexts(
        bearer, base64Of(wrappedDek), base64Of(wrappedBox), base64Of(pub), keyVersion, extras,
    )

/** The login reply of an account whose keys the fakes made, with [version] as key version. */
fun keyedLoginBody(bearer: String, version: Int): String =
    loginReplyWithKeysBody(bearer, keyWrappedDek(), keyWrappedBox(), keyPublic(), version)

/** Checks that the key store holds the fake data key and box key, with [version]. */
internal fun assertKeysStored(rig: KeyRig, version: Int) {
    val stored = rig.credentials.readKeys()
    assertNotNull("the credential vault holds no keys", stored)
    assertArrayEquals(keyPattern(32, 0xA0), stored!!.keys.dek.bytes)
    assertArrayEquals(keyPattern(32, 0x30), stored.keys.boxPrivateKey.copyBytes())
    assertEquals(version, stored.keyVersion)
}

/**
 * One service with the key ports wired to fakes. The Rig's http, derivation and session vault are
 * used as they are. [log] holds, in order, each http request, each key fake call, each key store
 * call and each session save. A test that needs the live request sets [onSend].
 */
class KeyRig {
    val rig = Rig()
    val log: MutableList<String> = mutableListOf()
    val accountKeys = FakeAccountKeys(log)
    val crypto = KeyFakeCrypto(log)
    val credentials = FakeCredentialVault()
    val ports = AccountKeyPorts(accountKeys, crypto, credentials)
    val http: FakeAuthHttp = rig.http
    val derive: FakeKeyDerivation = rig.keys
    val sessions: FakeSessionVault = rig.vault
    var onSend: (AuthHttpRequest) -> Unit = { }

    init {
        credentials.trace = { name -> log += name }
        http.beforeSend = { request ->
            log += "http " + request.method + " " + request.url
            onSend(request)
        }
    }

    /** A new service on these fakes. [using] replaces the derivation; [withPorts] false passes no ports. */
    fun service(using: KeyDerivation = derive, withPorts: Boolean = true): ServerAuthService =
        ServerAuthService(
            baseUrl = { rig.base },
            http = http,
            keyDerivation = using,
            vault = KeyLoggingSessionVault(sessions, log),
            keyPorts = if (withPorts) ports else null,
            saltSource = { rig.salt },
        )

    /** Queues a salt reply, a keyless login reply with [bearer] and a keys reply with [version]. */
    fun queueBootstrap(bearer: String = "tok-1", version: Int = 1) {
        rig.answerSaltOk()
        rig.answerLoginOk(bearer)
        http.answer(200, keysReplyBody(version))
    }

    /** Logs in as ada. */
    fun login(): AuthSession = service().login("ada", testPassword())

    /** Logs in as ada and returns the failure it must throw. */
    fun failure(): AuthFailure = assertThrows(AuthFailure::class.java) { login() }

    /** The url of every request sent, in order. */
    fun urls(): List<String> = http.requests.map { it.url }

    /** The Authorization header of every logout request sent, in order. */
    fun logoutBearers(): List<String?> =
        http.requests.filter { it.url == LOGOUT_URL }.map { headerValue(it, "Authorization") }
}
