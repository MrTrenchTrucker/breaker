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

/** The key_version the answer carries is the version handed to the credential
 * vault. */
class GapHandoffFinishKeyVersionStoredTest {

    private val SESSION_TEXT = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
    private val version = 5

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
        var lastVersion = -1
        override fun saveKeys(keys: AccountKeys, keyVersion: Int) {
            lastVersion = keyVersion
        }
        override fun readKeys(): StoredAccountKeys? = null
        override fun wipeAll() {}
    }

    private class GapSessions : SessionVault {
        override fun save(record: String) {}
        override fun load(): String? = null
        override fun clear() {}
    }

    private inner class GapHttp : AuthHttp {
        override fun send(request: AuthHttpRequest): AuthHttpReply {
            return AuthHttpReply(200, bodyOf(version).toByteArray())
        }
    }

    private fun bodyOf(keyVersion: Int): String {
        return "{\"token\":\"" + SESSION_TEXT + "\"," +
            "\"role\":\"user\",\"key_version\":" + keyVersion + "," +
            "\"sealed_package\":\"aGVsbG8=\"," +
            "\"username\":\"alice\",\"account_id\":1}"
    }

    private fun wipingAttempt(): SignInAttempt {
        return SignInAttempt(SESSION_TEXT.toByteArray(),
            SignInPrivateKey(ByteArray(32) { 1 }),
            ByteArray(32) { 2 }, SESSION_TEXT)
    }

    @Test
    fun `key version five is stored as five`() {
        val http = GapHttp()
        val credentials = GapCredentials()
        val finish = HandoffFinish({ "https://box.example" }, http, http,
            GapCrypto(), credentials, GapSessions())
        finish.onHandoff("code", wipingAttempt())
        assertEquals("the vault received key version five",
            version, credentials.lastVersion)
    }

    @Test
    fun `a zero key version is stored as zero`() {
        val http = object : AuthHttp {
            override fun send(request: AuthHttpRequest): AuthHttpReply {
                return AuthHttpReply(200, bodyOf(0).toByteArray())
            }
        }
        val credentials = GapCredentials()
        val finish = HandoffFinish({ "https://box.example" }, http, http,
            GapCrypto(), credentials, GapSessions())
        finish.onHandoff("code", wipingAttempt())
        assertEquals("the vault received key version zero",
            0, credentials.lastVersion)
    }
}
