package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.EncryptedText
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.SealedSignInPackage
import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.model.StoredAccountKeys
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.core.model.WrappedDek
import dev.breaker.dictation.core.port.CredentialVault
import dev.breaker.dictation.core.port.CryptoService
import dev.breaker.dictation.core.port.HandoffReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/** The exchange endpoint of the test server. */
internal const val EXCHANGE_URL = "https://box.example/v1/auth/handoff/exchange"

/** The code the website hands back in the tests. */
internal const val CODE_TEXT = "code-from-the-site"

/** A session text of exactly 43 URL-safe characters, as the exchange answers with. */
internal const val GOOD_SESSION_TEXT = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"

/** A session text of exactly 43 URL-safe characters with hyphens, underscores and digits in it. */
internal const val URL_SAFE_SESSION_TEXT = "Zk-9_Qw7-Lm_3Xn-Pr_5Ts-Vu_8Yg-Hj_2Rt-Mn_4Bv"

/** The username the good exchange answer carries. */
internal const val USERNAME_TEXT = "lena.k"

/** The key version the good exchange answer carries. */
internal const val GOOD_KEY_VERSION = 3

/** The 43 characters of the secret of a test attempt. */
internal fun attemptSecretText(): String = "k".repeat(43)

/** The 32 bytes of the private key of a test attempt. A new array on every call. */
internal fun attemptPrivateBytes(): ByteArray = ByteArray(32) { 9.toByte() }

/** The data key bytes the fake crypto opens. A new array on every call. */
internal fun fakeDekBytes(): ByteArray = ByteArray(32) { 0x31.toByte() }

/** The box key bytes the fake crypto opens. A new array on every call. */
internal fun fakeBoxBytes(): ByteArray = ByteArray(32) { 0x32.toByte() }

/** The sealed package bytes the good exchange answer carries. A new array on every call. */
internal fun sealedPackageBytes(): ByteArray = ByteArray(48) { (it + 1).toByte() }

/** True when every byte is zero. */
internal fun allZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

/** The text in double quotes, with no escaping. */
internal fun quoted(text: String): String = "\"" + text + "\""

/** The six members of a good exchange answer, as member name to JSON value text, in wire order. */
internal fun goodMembers(): MutableMap<String, String> = linkedMapOf(
    "token" to quoted(GOOD_SESSION_TEXT),
    "role" to quoted("user"),
    "key_version" to GOOD_KEY_VERSION.toString(),
    "sealed_package" to quoted(base64Of(sealedPackageBytes())),
    "username" to quoted(USERNAME_TEXT),
    "account_id" to "7",
)

/** One JSON object made from member names and ready-made value text, in the order given. */
internal fun bodyOf(members: Map<String, String>): String =
    members.entries.joinToString(",", "{", "}") { quoted(it.key) + ":" + it.value }

/** The session the good exchange answer stands for. */
internal fun goodSession(): AuthSession =
    AuthSession(USERNAME_TEXT, USERNAME_TEXT, GOOD_SESSION_TEXT, UserRole.USER, setOf(TokenScope.TRANSCRIBE))

/** The request body the finish must send for [code] and [deviceName], with nothing to escape. */
internal fun expectedExchangeBody(code: String, deviceName: String?): String =
    "{\"code\":\"" + code + "\",\"secret\":\"" + attemptSecretText() + "\"" +
        (if (deviceName != null) ",\"device_name\":\"" + deviceName + "\"" else "") + "}"

/** A sign-in attempt with a canonical 43 character secret and the keys and challenge it needs. */
fun makeAttempt(): SignInAttempt =
    SignInAttempt(
        attemptSecretText().toByteArray(Charsets.US_ASCII),
        SignInPrivateKey(attemptPrivateBytes()),
        ByteArray(32) { 7.toByte() },
        "c".repeat(43),
    )

/**
 * Test double for the crypto port. It opens sign-in packages only. It keeps copies of the sealed
 * bytes and of the private key bytes it was given, the package objects themselves (so a test can
 * see that they were wiped) and the keys it handed out. When [failure] is set, each open throws it.
 */
class FakeCrypto : CryptoService {
    /** A copy of the bytes of each sealed package given to an open call. */
    val sealedSeen: MutableList<ByteArray> = mutableListOf()

    /** A copy of the bytes of each private key given to an open call. */
    val privateSeen: MutableList<ByteArray> = mutableListOf()

    /** The package objects given to open calls. */
    val sealedObjects: MutableList<SealedSignInPackage> = mutableListOf()

    /** The keys returned by open calls. */
    val handedOut: MutableList<AccountKeys> = mutableListOf()

    /** When set, each open call records what it saw and then throws this. */
    var failure: Exception? = null

    override fun openSignInPackage(
        sealedPackage: SealedSignInPackage,
        signInPrivateKey: SignInPrivateKey,
    ): AccountKeys {
        sealedObjects += sealedPackage
        sealedSeen += sealedPackage.copyBytes()
        privateSeen += signInPrivateKey.copyBytes()
        failure?.let { throw it }
        val keys = AccountKeys(DataEncryptionKey(fakeDekBytes()), BoxPrivateKey(fakeBoxBytes()))
        handedOut += keys
        return keys
    }

    override fun unwrapDek(wrappedDek: WrappedDek, kek: KeyEncryptionKey): DataEncryptionKey =
        throw UnsupportedOperationException("the fake crypto opens sign-in packages only")

    override fun encrypt(plaintext: String, dek: DataEncryptionKey, aad: ByteArray): CipherText =
        throw UnsupportedOperationException("the fake crypto opens sign-in packages only")

    override fun decrypt(cipherText: CipherText, dek: DataEncryptionKey, aad: ByteArray): String =
        throw UnsupportedOperationException("the fake crypto opens sign-in packages only")

    override fun toEncryptedText(cipherText: CipherText): EncryptedText =
        throw UnsupportedOperationException("the fake crypto opens sign-in packages only")
}

/**
 * Test double for the key store port. [calls] holds "saveKeys" and "wipeAll" in call order, and
 * [trace] is told a longer name for each call so a test can order it against other fakes. It copies
 * the keys in and hands a new holder out, as the port says. A failure switch makes the call throw
 * after it has been recorded, and nothing is stored or removed then.
 */
class FakeCredentialVault : CredentialVault {
    val calls: MutableList<String> = mutableListOf()
    var saveFailure: RuntimeException? = null
    var wipeFailure: RuntimeException? = null
    var trace: (String) -> Unit = { }
    private var dek: ByteArray? = null
    private var box: ByteArray? = null
    private var version: Int = -1

    override fun saveKeys(keys: AccountKeys, keyVersion: Int) {
        calls += "saveKeys"
        trace("keys.saveKeys")
        saveFailure?.let { throw it }
        dek = keys.dek.bytes.copyOf()
        box = keys.boxPrivateKey.copyBytes()
        version = keyVersion
    }

    override fun readKeys(): StoredAccountKeys? {
        val heldDek = dek
        val heldBox = box
        if (heldDek == null || heldBox == null) return null
        return StoredAccountKeys(
            AccountKeys(DataEncryptionKey(heldDek.copyOf()), BoxPrivateKey(heldBox.copyOf())),
            version,
        )
    }

    override fun wipeAll() {
        calls += "wipeAll"
        trace("keys.wipeAll")
        wipeFailure?.let { throw it }
        dek?.fill(0)
        box?.fill(0)
        dek = null
        box = null
        version = -1
    }

    override fun toString(): String = "FakeCredentialVault"
}

/** A session vault that writes the name of each call to [log] and then delegates to [inner]. */
class OrderedSessionVault(
    private val inner: SessionVault,
    private val log: MutableList<String>,
) : SessionVault {
    override fun save(record: String) {
        log += "sessions.save"
        inner.save(record)
    }

    override fun load(): String? {
        log += "sessions.load"
        return inner.load()
    }

    override fun clear() {
        log += "sessions.clear"
        inner.clear()
    }
}

/**
 * One hand-off finish wired to its fakes: two scripted HTTP ports (exchange and logout), a fake
 * crypto, a fake key store and the fake session vault. [order] lists the key store calls, the
 * session vault calls and each logout send, in the order they happened. The address and the device
 * name are read from the properties at call time, and [addressFailure] and [nameFailure] make the
 * providers throw.
 */
class HandoffRig {
    val exchange = FakeAuthHttp()
    val logout = FakeAuthHttp()
    val crypto = FakeCrypto()
    val credentials = FakeCredentialVault()
    val sessions = FakeSessionVault()
    val failing = FailingVault(sessions)
    val order: MutableList<String> = mutableListOf()
    var base: String = BASE_URL
    var baseReads: Int = 0
    var deviceName: String? = null
    var addressFailure: RuntimeException? = null
    var nameFailure: RuntimeException? = null
    val finish: HandoffFinish

    init {
        credentials.trace = { entry -> order += entry }
        logout.beforeSend = { order += "logout" }
        finish = HandoffFinish(
            serverAddress = {
                baseReads += 1
                addressFailure?.let { throw it }
                base
            },
            exchangeHttp = exchange,
            logoutHttp = logout,
            crypto = crypto,
            credentials = credentials,
            sessions = OrderedSessionVault(failing, order),
            deviceName = {
                nameFailure?.let { throw it }
                deviceName
            },
        )
    }

    /** Queues a 200 exchange answer with [body], by default the good answer. */
    fun answerExchange(body: String = bodyOf(goodMembers())) {
        exchange.answer(200, body)
    }

    /** Hands the code and [attempt] to the finish. */
    fun receive(attempt: SignInAttempt, code: String = CODE_TEXT): HandoffReceipt =
        finish.onHandoff(code, attempt)
}

/** Checks that the key store and the session vault were never touched and hold nothing. */
fun assertNothingStored(rig: HandoffRig) {
    assertEquals(emptyList<String>(), rig.credentials.calls)
    assertEquals(emptyList<String>(), rig.sessions.calls)
    assertNull(rig.credentials.readKeys())
    assertNull(rig.sessions.stored)
}
