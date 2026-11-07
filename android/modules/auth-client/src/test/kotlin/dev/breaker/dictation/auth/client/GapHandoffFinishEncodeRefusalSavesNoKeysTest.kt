package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.EncryptedText
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.SealedSignInPackage
import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.model.StoredAccountKeys
import dev.breaker.dictation.core.model.WrappedDek
import dev.breaker.dictation.core.port.CredentialVault
import dev.breaker.dictation.core.port.CryptoService
import dev.breaker.dictation.core.port.HandoffReceipt
import org.junit.Assert.assertEquals
import org.junit.Test

/** A session record the encoder refuses still clears both stores and sends one
 * logout; saveKeys is never called. Lever: an over-long username reaches
 * SessionRecord.encode (line 42). A valid username stores keys once each. */
class GapHandoffFinishEncodeRefusalSavesNoKeysTest {

    private val SESSION_TEXT = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"

    private class GapCrypto : CryptoService {
        override fun openSignInPackage(
            sealedPackage: SealedSignInPackage,
            signInPrivateKey: SignInPrivateKey,
        ): AccountKeys {
            return AccountKeys(
                DataEncryptionKey(ByteArray(32) { 3 }),
                BoxPrivateKey(ByteArray(32) { 4 }),
            )
        }
        override fun unwrapDek(wrappedDek: WrappedDek, kek: KeyEncryptionKey):
            DataEncryptionKey {
            throw UnsupportedOperationException("not reached")
        }
        override fun encrypt(plaintext: String, dek: DataEncryptionKey,
            aad: ByteArray): CipherText {
            throw UnsupportedOperationException("not reached")
        }
        override fun decrypt(cipherText: CipherText, dek: DataEncryptionKey,
            aad: ByteArray): String {
            throw UnsupportedOperationException("not reached")
        }
        override fun toEncryptedText(cipherText: CipherText): EncryptedText {
            throw UnsupportedOperationException("not reached")
        }
    }

    private class GapCredentials : CredentialVault {
        var saveCalls = 0
        override fun saveKeys(keys: AccountKeys, keyVersion: Int) {
            saveCalls += 1
        }
        override fun readKeys(): StoredAccountKeys? = null
        override fun wipeAll() {}
    }

    private class GapSessions : SessionVault {
        var saveCalls = 0
        var clearCalls = 0
        override fun save(record: String) {
            saveCalls += 1
        }
        override fun load(): String? = null
        override fun clear() {
            clearCalls += 1
        }
    }

    private inner class GapHttp(val body: String) : AuthHttp {
        var exchangeRequests = 0
        var logoutRequests = 0
        override fun send(request: AuthHttpRequest): AuthHttpReply {
            if (request.url.contains("/v1/auth/logout")) logoutRequests += 1 else exchangeRequests += 1
            return AuthHttpReply(200, body.toByteArray())
        }
    }

    private val longBody = bodyOf("a".repeat(30000))
    private val validBody = bodyOf("alice")

    private fun bodyOf(username: String): String {
        return "{\"token\":\"" + SESSION_TEXT + "\"," +
            "\"role\":\"user\",\"key_version\":0," +
            "\"sealed_package\":\"aGVsbG8=\"," +
            "\"username\":\"" + username + "\"," +
            "\"account_id\":1}"
    }

    private fun wipingAttempt(): SignInAttempt {
        return SignInAttempt(SESSION_TEXT.toByteArray(),
            SignInPrivateKey(ByteArray(32) { 1 }),
            ByteArray(32) { 2 }, SESSION_TEXT)
    }

    @Test
    fun `an over long username saves no keys and sends one logout`() {
        val http = GapHttp(longBody)
        val credentials = GapCredentials()
        val sessions = GapSessions()
        val finish = HandoffFinish(
            { "https://box.example" }, http, http, GapCrypto(), credentials,
            sessions)
        val receipt = finish.onHandoff("code", wipingAttempt())
        assertEquals("the refused record still means not finished",
            HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals("saveKeys is never called", 0, credentials.saveCalls)
        assertEquals("one logout is sent", 1, http.logoutRequests)
        assertEquals("the exchange went out once", 1, http.exchangeRequests)
    }

    @Test
    fun `a valid length username stores keys and session once each`() {
        val http = GapHttp(validBody)
        val credentials = GapCredentials()
        val sessions = GapSessions()
        val finish = HandoffFinish(
            { "https://box.example" }, http, http, GapCrypto(), credentials,
            sessions)
        val receipt = finish.onHandoff("code", wipingAttempt())
        assertEquals("a valid record means taken", HandoffReceipt.TAKEN, receipt)
        assertEquals("keys are stored once", 1, credentials.saveCalls)
        assertEquals("the session is stored once", 1, sessions.saveCalls)
    }
}
