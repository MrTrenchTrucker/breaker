package dev.breaker.dictation.wiring

import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.auth.client.AuthHttp
import dev.breaker.dictation.auth.client.AuthHttpRequest
import dev.breaker.dictation.auth.client.AuthHttpReply
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.AuthVerifier
import dev.breaker.dictation.core.port.KeyDerivation
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission

/**
 * ServerAuthService built from fakes drives the login flow to success and wires
 * every part: the saved address, the supplied key derivation, and the sealed
 * session file that opens only with the supplied key.
 */
class AuthServiceWiresEachPartTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeAuthHttp : AuthHttp {
        val urls = mutableListOf<String>()
        val saltBody = """{"salt":"${Base64.getEncoder().encodeToString(ByteArray(16))}","kdf_params":""" +
            """{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1}"""
        val loginBody = """{"token":"tok-1","role":"admin","key_version":0}"""
        override fun send(request: AuthHttpRequest): AuthHttpReply {
            urls.add(request.url)
            return if (request.url.contains("/v1/auth/salt")) AuthHttpReply(200, saltBody.toByteArray())
            else AuthHttpReply(200, loginBody.toByteArray())
        }
    }

    private class FakeKeyDerivation(var calls: Int = 0) : KeyDerivation {
        override fun deriveKeys(password: CharArray, salt: ByteArray, kdfParams: KdfParams): DerivedKeys {
            calls++
            return DerivedKeys(KeyEncryptionKey(ByteArray(32) { 1 }), AuthVerifier(ByteArray(32) { 2 }))
        }
    }

    @Test
    fun `ServerAuthService from fakes posts to saved address and writes sealed session`() {
        val fakeHttp = FakeAuthHttp()
        val fakeKdf = FakeKeyDerivation()
        val softwareKey = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val savedAddress = "https://example.test"
        val root = BreakerCompositionRoot(
            filesDir = tmp.root,
            historyStore = RecordingHistoryStore(),
            serviceController = DictationServiceController(FakePermission(), FakeLauncher()),
            committer = FakeCommitter(),
            authHttp = { _ -> fakeHttp },
            keyDerivation = { fakeKdf },
            keySupplier = { softwareKey },
        )
        root.settingsStore.save(AppSettings().copy(serverUrl = savedAddress))

        root.authService.login("user", "a-very-long-password".toCharArray())

        assertTrue("http.send must have been called", fakeHttp.urls.isNotEmpty())
        assertTrue("request url must target saved address", fakeHttp.urls.any { it.startsWith(savedAddress) })
        assertTrue("key derivation must have been invoked", fakeKdf.calls > 0)

        val sessionFile = java.io.File(tmp.root, "session.vault")
        assertTrue("session.vault must exist", sessionFile.exists())
        val bytes = sessionFile.readBytes()
        assertTrue(
            "session.vault must not contain plaintext token",
            !String(bytes, Charsets.ISO_8859_1).contains("tok-1"),
        )

        val vault = FileSessionVault(sessionFile, AesSessionSeal { softwareKey })
        val record = vault.load()
        assertTrue("session.vault must open with supplied key", record != null)
        assertTrue("record must contain token", record!!.contains("tok-1"))

        val wrongKey = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        val vaultWrong = FileSessionVault(sessionFile, AesSessionSeal { wrongKey })
        assertTrue("wrong key must not open session.vault", vaultWrong.load() == null)
    }
}
