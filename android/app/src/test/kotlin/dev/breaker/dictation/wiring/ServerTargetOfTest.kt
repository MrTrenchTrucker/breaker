package dev.breaker.dictation.wiring

import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.auth.client.AuthHttp
import dev.breaker.dictation.auth.client.AuthHttpReply
import dev.breaker.dictation.auth.client.AuthHttpRequest
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.AuthVerifier
import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.core.port.KeyDerivation
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The engine's address and key of one request, taken together from one addressed session: a session
 * at the saved address gives its base and its bearer, and anything else gives the saved address and
 * no key. The provider it reads is called once per request.
 */
class ServerTargetOfTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bearerText = "bearer-text-for-the-target-test"

    private fun addressed(base: String): AddressedSession =
        AddressedSession(base, AuthSession("id-1", "user", bearerText, UserRole.USER, setOf(TokenScope.TRANSCRIBE)))

    @Test
    fun `a session at the saved address gives its base and its bearer`() {
        val target = serverTargetOf({ addressed("HTTPS://First.Test/") }, { "https://never-read.test" })

        assertEquals("app: the base should be the one the session came with", "HTTPS://First.Test/", target.base)
        assertEquals("app: the key should be the bearer of the session", bearerText, target.key)
    }

    @Test
    fun `no session gives the saved address and no key`() {
        val target = serverTargetOf({ null }, { "https://saved.test" })

        assertEquals("app: the base should be the saved address read now", "https://saved.test", target.base)
        assertNull("app: no session means no key", target.key)
    }

    @Test
    fun `the addressed session is read exactly once in the branch with a session`() {
        var calls = 0
        serverTargetOf({ calls += 1; addressed("https://first.test") }, { "https://saved.test" })

        assertEquals("app: the session branch should read the addressed session once", 1, calls)
    }

    @Test
    fun `the addressed session is read exactly once in the branch without a session`() {
        var calls = 0
        serverTargetOf({ calls += 1; null }, { "https://saved.test" })

        assertEquals("app: the empty branch should read the addressed session once", 1, calls)
    }

    @Test
    fun `the saved address is not read when a session is given`() {
        var reads = 0
        serverTargetOf({ addressed("https://first.test") }, { reads += 1; "https://saved.test" })

        assertEquals("app: the saved address should not be read when the session carries its base", 0, reads)
    }

    @Test
    fun `the text of a target holds neither the bearer nor the base`() {
        val text = serverTargetOf({ addressed("https://first.test") }, { "https://saved.test" }).toString()

        assertFalse("app: the target text must not hold the bearer, got $text", text.contains(bearerText))
        assertFalse("app: the target text must not hold the base, got $text", text.contains("first.test"))
    }

    @Test
    fun `a sign in through the root gives its bearer and a changed saved address gives no key`() {
        val root = BreakerCompositionRoot(
            filesDir = tmp.root,
            historyStore = RecordingHistoryStore(),
            serviceController = DictationServiceController(FakePermission(), FakeLauncher()),
            committer = FakeCommitter(),
            authHttp = { _ -> TargetPathHttp() },
            keyDerivation = { TargetPathKeys() },
            keySupplier = { SecretKeySpec(ByteArray(32) { 3 }, "AES") },
        )
        val saved: () -> String = { root.settingsStore.load().serverUrl }
        root.settingsStore.save(AppSettings().copy(serverUrl = "https://first.test"))
        root.authService.login("user", "a-very-long-password".toCharArray())

        val signedIn = serverTargetOf(root.addressedSession, saved)
        assertEquals("app: the base should be the saved address", "https://first.test", signedIn.base)
        assertEquals("app: the key should be the bearer the login reply held", "tok-7", signedIn.key)

        root.settingsStore.save(AppSettings().copy(serverUrl = "https://second.test"))
        val moved = serverTargetOf(root.addressedSession, saved)
        assertEquals("app: the base should be the new saved address", "https://second.test", moved.base)
        assertNull("app: a session made for another server must give no key", moved.key)
    }
}

/** A login transport that answers the salt call and then a login reply with a fixed bearer. */
private class TargetPathHttp : AuthHttp {
    override fun send(request: AuthHttpRequest): AuthHttpReply {
        val salt = Base64.getEncoder().encodeToString(ByteArray(16))
        val saltBody = """{"salt":"$salt","kdf_params":""" +
            """{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1}"""
        val loginBody = """{"token":"tok-7","role":"admin","key_version":0}"""
        return if (request.url.contains("/v1/auth/salt")) AuthHttpReply(200, saltBody.toByteArray())
        else AuthHttpReply(200, loginBody.toByteArray())
    }
}

/** A key derivation that returns fixed keys. */
private class TargetPathKeys : KeyDerivation {
    override fun deriveKeys(password: CharArray, salt: ByteArray, kdfParams: KdfParams): DerivedKeys =
        DerivedKeys(KeyEncryptionKey(ByteArray(32) { 1 }), AuthVerifier(ByteArray(32) { 2 }))
}
