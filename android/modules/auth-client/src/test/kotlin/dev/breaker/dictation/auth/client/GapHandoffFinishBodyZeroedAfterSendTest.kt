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
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request body is zeroed after the send: the http fake keeps the ARRAY
 * REFERENCE (not a copy), so HandoffFinish's body.fill(0) zeroes it too; a copy
 * taken inside send still held the real body containing the code text. */
class GapHandoffFinishBodyZeroedAfterSendTest {

    private val SESSION_TEXT = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
    private val CODE = "the-one-time-code"

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
        override fun saveKeys(keys: AccountKeys, keyVersion: Int) {}
        override fun readKeys(): StoredAccountKeys? = null
        override fun wipeAll() {}
    }

    private class GapSessions : SessionVault {
        override fun save(record: String) {}
        override fun load(): String? = null
        override fun clear() {}
    }

    private inner class GapHttp : AuthHttp {
        // Keeps the array reference HandoffFinish handed over: after send returns
        // the finish zeroes that same array, so this reference reads all zeros
        // after onHandoff.
        var keptBody: ByteArray? = null
        // A copy taken inside send while the body still held the real bytes.
        var realCopy: ByteArray? = null
        override fun send(request: AuthHttpRequest): AuthHttpReply {
            keptBody = request.body
            realCopy = request.body?.copyOf()
            return AuthHttpReply(200, exchangeAnswerBody().toByteArray())
        }
    }

    private fun exchangeAnswerBody(): String {
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

    @Test
    fun `the hand-off request body is zeroed after the exchange send`() {
        val http = GapHttp()
        val finish = HandoffFinish(
            { "https://box.example" }, http, http, GapCrypto(), GapCredentials(),
            GapSessions())
        val receipt = finish.onHandoff(CODE, wipingAttempt())
        assertEquals("the send completed", HandoffReceipt.TAKEN, receipt)
        val real = http.realCopy
        if (real == null) throw AssertionError("the send never saw a body")
        assertTrue("the copy taken inside send held the real body",
            real.any { it != 0.toByte() })
        assertTrue("the copy taken inside send contains the code text",
            String(real).contains(CODE))
        val kept = http.keptBody
        if (kept == null) throw AssertionError("the send never kept a body")
        assertTrue("every byte of the kept body is zero after the send",
            kept.all { it == 0.toByte() })
    }
}
