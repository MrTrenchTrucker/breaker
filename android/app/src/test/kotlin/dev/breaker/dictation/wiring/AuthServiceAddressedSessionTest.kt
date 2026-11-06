package dev.breaker.dictation.wiring

import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.auth.client.AuthHttp
import dev.breaker.dictation.auth.client.AuthHttpReply
import dev.breaker.dictation.auth.client.AuthHttpRequest
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AuthVerifier
import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.port.KeyDerivation
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission

/**
 * The root's addressed session: the stored session and the address the settings hold now, taken
 * together from the one auth service. The address is read on every call, so a changed address
 * gives no session, and a login through the root's authService is visible through it.
 */
class AuthServiceAddressedSessionTest {
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

    private val fakeHttp = FakeAuthHttp()

    private fun newRoot() = BreakerCompositionRoot(
        filesDir = tmp.root,
        historyStore = RecordingHistoryStore(),
        serviceController = DictationServiceController(FakePermission(), FakeLauncher()),
        committer = FakeCommitter(),
        authHttp = { _ -> fakeHttp },
        keyDerivation = { FakeKeyDerivation() },
        keySupplier = { SecretKeySpec(ByteArray(32) { 3 }, "AES") },
    )

    private fun BreakerCompositionRoot.useAddress(address: String) {
        settingsStore.save(AppSettings().copy(serverUrl = address))
    }

    private fun BreakerCompositionRoot.signIn() {
        authService.login("user", "a-very-long-password".toCharArray())
    }

    @Test
    fun `a login through authService is returned with the saved address and the session together`() {
        val root = newRoot()
        root.useAddress("https://first.test")
        root.signIn()

        val pair = root.addressedSession()

        assertNotNull("a session stored for the saved address must be returned", pair)
        assertEquals("base must be the saved address", "https://first.test", pair!!.base)
        assertEquals("session must hold the login reply", "tok-1", pair.session.token)
        assertEquals("session must hold the login name", "user", pair.session.username)
    }

    @Test
    fun `the base returned is the address read now and not the stored text`() {
        val root = newRoot()
        root.useAddress("https://first.test")
        root.signIn()
        root.useAddress("HTTPS://First.Test/")

        val pair = root.addressedSession()

        assertNotNull("a case and slash variant of the same server must still match", pair)
        assertEquals("base must be the text the settings hold now", "HTTPS://First.Test/", pair!!.base)
    }

    @Test
    fun `no session is returned once the saved address names another server`() {
        val root = newRoot()
        root.useAddress("https://first.test")
        root.signIn()
        root.useAddress("https://second.test")

        assertNull("a session made for another server must not be returned", root.addressedSession())
    }

    @Test
    fun `the session is returned again when the saved address is changed back`() {
        val root = newRoot()
        root.useAddress("https://first.test")
        root.signIn()
        root.useAddress("https://second.test")
        root.useAddress("https://first.test")

        val pair = root.addressedSession()

        assertNotNull("the address is read on every call so the session must come back", pair)
        assertEquals("base must be the first address again", "https://first.test", pair!!.base)
    }

    @Test
    fun `no session is returned before any login`() {
        val root = newRoot()
        root.useAddress("https://first.test")

        assertNull("nothing is stored so there is no session", root.addressedSession())
    }

    @Test
    fun `no session is returned after the sign out of authService`() {
        val root = newRoot()
        root.useAddress("https://first.test")
        root.signIn()
        root.authService.logout()

        assertNull("both must reach the one stored session", root.addressedSession())
    }

    @Test
    fun `reading the addressed session sends no request`() {
        val root = newRoot()
        root.useAddress("https://first.test")
        root.signIn()
        val sentBefore = fakeHttp.urls.size

        root.addressedSession()
        root.addressedSession()

        assertEquals("a read must not send anything", sentBefore, fakeHttp.urls.size)
    }

    @Test
    fun `authService is the same object on every read`() {
        val root = newRoot()

        assertSame("one service must serve every read", root.authService, root.authService)
    }
}
