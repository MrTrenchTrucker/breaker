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
 * The saved address is read on every call: changing it in settings between two
 * logins sends the second login to the new address.
 */
class AuthServiceAddressReadPerCallTest {
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

    private class FakeKeyDerivation : KeyDerivation {
        override fun deriveKeys(password: CharArray, salt: ByteArray, kdfParams: KdfParams): DerivedKeys =
            DerivedKeys(KeyEncryptionKey(ByteArray(32) { 1 }), AuthVerifier(ByteArray(32) { 2 }))
    }

    @Test
    fun `saved address read per call so a changed address reaches the next request`() {
        val fakeHttp = FakeAuthHttp()
        val root = BreakerCompositionRoot(
            filesDir = tmp.root,
            historyStore = RecordingHistoryStore(),
            serviceController = DictationServiceController(FakePermission(), FakeLauncher()),
            committer = FakeCommitter(),
            authHttp = { _ -> fakeHttp },
            keyDerivation = { FakeKeyDerivation() },
            keySupplier = { SecretKeySpec(ByteArray(32) { 3 }, "AES") },
        )

        root.settingsStore.save(AppSettings().copy(serverUrl = "https://first.test"))
        root.authService.login("user", "a-very-long-password".toCharArray())

        root.settingsStore.save(AppSettings().copy(serverUrl = "https://second.test"))
        root.authService.login("user", "a-very-long-password".toCharArray())

        // The second login also signs the first session out, best effort, at the first address.
        val exchange = fakeHttp.urls.filter { !it.endsWith("/v1/auth/logout") }
        assertTrue("expected four requests (salt+login per address)", exchange.size == 4)
        assertTrue(
            "previous session must be signed out at the first address",
            fakeHttp.urls.size == 5 && fakeHttp.urls[4] == "https://first.test/v1/auth/logout",
        )
        assertTrue("first salt must target first address", fakeHttp.urls[0].startsWith("https://first.test"))
        assertTrue("first login must target first address", fakeHttp.urls[1].startsWith("https://first.test"))
        assertTrue(
            "second salt must target second address",
            fakeHttp.urls[2].startsWith("https://second.test"),
        )
        assertTrue(
            "second login must target second address",
            fakeHttp.urls[3].startsWith("https://second.test"),
        )
    }
}
