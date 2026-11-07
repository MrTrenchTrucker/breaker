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

/** A status other than 200 sends no logout and stores nothing. Each test owns its http fake. */
class GapHandoffFinishNon200NoLogoutTest {

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

    private inner class GapHttp : AuthHttp {
        var exchangeRequests = 0
        var logoutRequests = 0
        var exchangeStatus = 200
        override fun send(request: AuthHttpRequest): AuthHttpReply {
            exchangeRequests += 1
            if (request.url.contains("/v1/auth/logout")) logoutRequests += 1
            return AuthHttpReply(exchangeStatus, exchangeBody().toByteArray())
        }
    }

    private fun exchangeBody(): String {
        return "{\"token\":\"" + SESSION_TEXT + "\"," +
            "\"role\":\"user\",\"key_version\":0," +
            "\"sealed_package\":\"aGVsbG8=\"," +
            "\"username\":\"alice\",\"account_id\":1}"
    }

    private fun wipingAttempt(): SignInAttempt {
        return SignInAttempt(SESSION_TEXT.toByteArray(),
            SignInPrivateKey(ByteArray(32) { 1 }),
            ByteArray(32) { 2 }, SESSION_TEXT)
    }

    private data class GapFinish(val http: GapHttp, val finish: HandoffFinish,
                                 val credentials: GapCredentials, val sessions: GapSessions)

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

    private fun finishFor(status: Int): GapFinish {
        val http = GapHttp()
        http.exchangeStatus = status
        val credentials = GapCredentials()
        val sessions = GapSessions()
        return GapFinish(http,
            HandoffFinish({ "https://box.example" }, http, http, GapCrypto(),
                credentials, sessions),
            credentials, sessions)
    }

    @Test
    fun `status 401 sends no logout and stores nothing`() {
        val g = finishFor(401)
        val receipt = g.finish.onHandoff("code", wipingAttempt())
        assertEquals("a non-200 status means not finished",
            HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals("no logout is sent", 0, g.http.logoutRequests)
        assertEquals("no keys are stored", 0, g.credentials.saveCalls)
        assertEquals("no session is stored", 0, g.sessions.saveCalls)
    }

    @Test
    fun `status 500 sends no logout and stores nothing`() {
        val g = finishFor(500)
        val receipt = g.finish.onHandoff("code", wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals("no logout is sent", 0, g.http.logoutRequests)
        assertEquals("no keys are stored", 0, g.credentials.saveCalls)
        assertEquals("no session is stored", 0, g.sessions.saveCalls)
    }

    @Test
    fun `status 201 sends no logout and stores nothing`() {
        val g = finishFor(201)
        val receipt = g.finish.onHandoff("code", wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals("no logout is sent", 0, g.http.logoutRequests)
        assertEquals("no keys are stored", 0, g.credentials.saveCalls)
        assertEquals("no session is stored", 0, g.sessions.saveCalls)
    }

    @Test
    fun `status 200 is the only status that stores`() {
        val g = finishFor(200)
        val receipt = g.finish.onHandoff("code", wipingAttempt())
        assertEquals(HandoffReceipt.TAKEN, receipt)
        assertEquals("keys are stored once", 1, g.credentials.saveCalls)
        assertEquals("the session is stored once", 1, g.sessions.saveCalls)
        assertEquals("no logout on success", 0, g.http.logoutRequests)
    }
}
