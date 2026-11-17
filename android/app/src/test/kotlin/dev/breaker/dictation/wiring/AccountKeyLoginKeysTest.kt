package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AccountKeyPorts
import dev.breaker.dictation.auth.client.AuthFailure
import dev.breaker.dictation.auth.client.AuthFailureReason
import dev.breaker.dictation.auth.client.AuthHttp
import dev.breaker.dictation.auth.client.AuthHttpRequest
import dev.breaker.dictation.auth.client.AuthHttpReply
import dev.breaker.dictation.auth.client.ServerAuthService
import dev.breaker.dictation.auth.client.SessionVault
import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.BoxKeyPair
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.StoredAccountKeys
import dev.breaker.dictation.core.port.AccountKeyService
import dev.breaker.dictation.core.port.CredentialVault
import dev.breaker.dictation.crypto.AccountKeyServiceImpl
import dev.breaker.dictation.crypto.CryptoServiceImpl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/**
 * The account-key step of a password login, end to end through [buildAuthService] over the real
 * [accountKeyPorts] factory: a failed upload stores nothing and the next login retries the whole
 * setup, the password reaches neither the vault nor the stored file in clear, and a keyed account
 * is opened and stored rather than generated and uploaded again.
 */
class AccountKeyLoginKeysTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val softwareKey = SecretKeySpec(ByteArray(32) { 3 }, "AES")
    private val address: String = "https://keys.test"
    private val passwordText: String = "a-very-long-password"

    /** A vault that records every byte array it is handed and every text, over the real file vault. */
    private class RecordingVault(private val inner: CredentialVault) : CredentialVault {
        val keyArrays: MutableList<ByteArray> = mutableListOf()

        /** The vault port carries no text, so nothing is ever recorded here; the test holds it to say so. */
        val texts: MutableList<String> = mutableListOf()
        val versions: MutableList<Int> = mutableListOf()

        override fun saveKeys(keys: AccountKeys, keyVersion: Int) {
            keyArrays.add(keys.dek.bytes.copyOf())
            keyArrays.add(keys.boxPrivateKey.copyBytes())
            versions.add(keyVersion)
            inner.saveKeys(keys, keyVersion)
        }

        override fun readKeys(): StoredAccountKeys? = inner.readKeys()

        override fun wipeAll() = inner.wipeAll()
    }

    /** A login transport that answers salt and login, counts the keys route and answers it with [keysStatus]. */
    private class KeyLoginHttp(
        private val saltBody: String,
        private val loginBody: String,
        private val keysStatus: () -> Int,
    ) : AuthHttp {
        val urls: MutableList<String> = mutableListOf()
        var keysRequests: Int = 0
            private set

        override fun send(request: AuthHttpRequest): AuthHttpReply {
            urls.add(request.url)
            return when {
                request.url.contains("/v1/auth/salt") -> AuthHttpReply(200, saltBody.toByteArray())
                request.url.contains("/v1/auth/login") -> AuthHttpReply(200, loginBody.toByteArray())
                request.url.contains("/v1/auth/keys") -> {
                    keysRequests += 1
                    val status: Int = keysStatus()
                    if (status == 200) AuthHttpReply(200, """{"key_version":1}""".toByteArray())
                    else AuthHttpReply(status, ByteArray(0))
                }
                else -> AuthHttpReply(200, ByteArray(0))
            }
        }
    }

    /** A spy on the account key service: it counts the two generators and delegates everything else to [inner]. */
    private class CountingAccountKeys(private val inner: AccountKeyService) : AccountKeyService by inner {
        var generated: Int = 0
            private set

        override fun generateDataKey(): DataEncryptionKey {
            generated += 1
            return inner.generateDataKey()
        }

        override fun generateBoxKeyPair(): BoxKeyPair {
            generated += 1
            return inner.generateBoxKeyPair()
        }
    }

    private fun password(): CharArray = passwordText.toCharArray()

    private fun keysFile(): File = File(tmp.root, "credentials.vault")

    private fun sessionFile(): File = File(tmp.root, "session.vault")

    private fun recordingVault(): RecordingVault =
        RecordingVault(CredentialFileVault(keysFile(), AesSessionSeal(CREDENTIAL_VAULT_PURPOSE) { softwareKey }))

    private fun plainSessionVault(): SessionVault = FileSessionVault(sessionFile(), AesSessionSeal { softwareKey })

    private fun service(http: AuthHttp, sessions: SessionVault, ports: AccountKeyPorts): ServerAuthService =
        buildAuthService({ address }, sessions, http, HrKeyDerivation(), ports)

    private fun saltBodyOf(salt: ByteArray): String {
        val text: String = Base64.getEncoder().encodeToString(salt)
        return """{"salt":"$text","kdf_params":""" +
            """{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1}"""
    }

    private fun keylessLoginBody(token: String): String =
        """{"token":"$token","role":"admin","key_version":0}"""

    private fun keyedLoginBody(
        token: String,
        dek: ByteArray,
        box: ByteArray,
        pub: ByteArray,
        version: Int,
    ): String =
        """{"token":"$token","role":"admin","wrapped_dek":"${base64(dek)}",""" +
            """"wrapped_box_privkey":"${base64(box)}","box_pubkey":"${base64(pub)}","key_version":$version}"""

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun keyBytes(start: Int): ByteArray = ByteArray(32) { (start + it).toByte() }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        for (start in 0..haystack.size - needle.size) {
            var same = true
            for (j in needle.indices) if (haystack[start + j] != needle[j]) same = false
            if (same) return true
        }
        return false
    }

    @Test
    fun `a failed keys upload throws and stores nothing and the next login retries the whole setup`() {
        val vault = recordingVault()
        val salt = ByteArray(16) { 7 }
        var keysStatus = 500
        val http = KeyLoginHttp(saltBodyOf(salt), keylessLoginBody("tok-1")) { keysStatus }
        val service = service(http, plainSessionVault(), accountKeyPorts(vault, CryptoServiceImpl()))

        val thrown = assertThrows(AuthFailure::class.java) { service.login("user", password()) }

        assertEquals("a 500 on the keys route is a server error", AuthFailureReason.SERVER_ERROR, thrown.reason)
        assertNull("a failed upload must leave no keys", vault.readKeys())
        assertFalse("a failed upload must store no key array", vault.keyArrays.isNotEmpty())
        assertEquals("the failed login uploaded once", 1, http.keysRequests)

        keysStatus = 200
        val session = service.login("user", password())

        assertEquals("the retry returns the login session", "tok-1", session.token)
        val stored = vault.readKeys()
        assertNotNull("the retry must store the keys", stored)
        assertEquals("the retry stores the server's version", 1, stored!!.keyVersion)
        assertEquals("the retry uploaded once more", 2, http.keysRequests)
    }

    @Test
    fun `the password reaches neither the vault nor the stored file in clear`() {
        val vault = recordingVault()
        val salt = ByteArray(16) { 7 }
        val passwordBytes = passwordText.toByteArray(Charsets.UTF_8)
        val http = KeyLoginHttp(saltBodyOf(salt), keylessLoginBody("tok-1")) { 200 }
        val service = service(http, plainSessionVault(), accountKeyPorts(vault, CryptoServiceImpl()))

        service.login("user", password())

        assertNotNull("the login must store keys for the password to be checked against", vault.readKeys())
        for (array in vault.keyArrays) {
            assertFalse("a stored byte array holds the password", contains(array, passwordBytes))
        }
        assertTrue("the vault port carries no text, so none was recorded", vault.texts.isEmpty())
        val atRest = keysFile().readBytes()
        assertFalse("the stored file holds the password text", String(atRest, Charsets.ISO_8859_1).contains(passwordText))
        assertFalse("the stored file holds the password bytes", contains(atRest, passwordBytes))
    }

    @Test
    fun `a keyed account is opened and stored and never generated nor uploaded again`() {
        val vault = recordingVault()
        val salt = ByteArray(16) { 7 }
        val realPorts = accountKeyPorts(vault, CryptoServiceImpl())
        val spy = CountingAccountKeys(realPorts.accountKeys)
        val ports = AccountKeyPorts(spy, realPorts.crypto, realPorts.credentials)

        val dek = DataEncryptionKey(keyBytes(0xA0))
        val made = AccountKeyServiceImpl().generateBoxKeyPair()
        val kek = KeyEncryptionKey(ByteArray(32) { 1 })
        val wrappedDek = realPorts.accountKeys.wrapDek(dek, kek, salt)
        val wrappedBox = realPorts.accountKeys.wrapBoxPrivateKey(made.privateKey, dek)
        val body = keyedLoginBody("tok-keyed", wrappedDek.bytes, wrappedBox.bytes, made.copyPublicKey(), 1)
        val http = KeyLoginHttp(saltBodyOf(salt), body) { 200 }
        val service = service(http, plainSessionVault(), ports)

        val session = service.login("user", password())

        assertEquals("the keyed login returns the reply's session", "tok-keyed", session.token)
        assertEquals("a keyed account must not generate a single key", 0, spy.generated)
        assertEquals("a keyed account must not upload keys", 0, http.keysRequests)
        assertEquals(
            "a keyed login sends only the salt and the login",
            listOf(address + "/v1/auth/salt", address + "/v1/auth/login"),
            http.urls,
        )
        val stored = vault.readKeys()
        assertNotNull("a keyed login must store the unwrapped keys", stored)
        assertEquals("the stored version is the reply's", 1, stored!!.keyVersion)
        assertArrayEquals("the stored data key is the unwrapped one", dek.bytes, stored.keys.dek.bytes)
        assertArrayEquals(
            "the stored box key is the unwrapped one",
            made.privateKey.copyBytes(),
            stored.keys.boxPrivateKey.copyBytes(),
        )
        made.wipe()
        dek.wipe()
        wrappedDek.bytes.fill(0)
        wrappedBox.bytes.fill(0)
    }

    @Test
    fun `a clear of the app session vault wipes the stored keys`() {
        val vault = recordingVault()
        val salt = ByteArray(16) { 7 }
        val sessions = KeysClearingSessionVault(plainSessionVault(), vault)
        val http = KeyLoginHttp(saltBodyOf(salt), keylessLoginBody("tok-1")) { 200 }
        val service = service(http, sessions, accountKeyPorts(vault, CryptoServiceImpl()))

        service.login("user", password())
        assertNotNull("the login must store keys before the clear", vault.readKeys())

        service.logout()

        assertNull("the app clear must wipe the keys", vault.readKeys())
        assertNull("the app clear must wipe the session", service.currentSession())
    }

    @Test
    fun `a logout over a plain session vault keeps the stored keys`() {
        val vault = recordingVault()
        val salt = ByteArray(16) { 7 }
        val http = KeyLoginHttp(saltBodyOf(salt), keylessLoginBody("tok-1")) { 200 }
        val service = service(http, plainSessionVault(), accountKeyPorts(vault, CryptoServiceImpl()))

        service.login("user", password())
        service.logout()

        assertNull("the session is gone", service.currentSession())
        assertNotNull("the account keys are not the session and survive a plain logout", vault.readKeys())
    }
}
