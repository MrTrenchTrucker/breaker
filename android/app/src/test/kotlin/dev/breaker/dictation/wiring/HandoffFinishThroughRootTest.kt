package dev.breaker.dictation.wiring

import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.auth.client.AuthHttpReply
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A hand-off return through the real root: the return flow, the finish, the two vaults and the auth
 * service, over fake transports, a fake crypto service, a software key and real files.
 */
class HandoffFinishThroughRootTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val softwareKey = SecretKeySpec(ByteArray(32) { 3 }, "AES")
    private val address = "https://example.test"
    private val view = "android.intent.action.VIEW"
    private val link: String = "breaker://handoff?code=" + HR_CODE
    private val exchangePath = "/v1/auth/handoff/exchange"
    private val logoutPath = "/v1/auth/logout"

    private var exchangeStatus = 200
    private var crypto = HrFakeCrypto()
    private val exchangeHttp = HrHttp {
        AuthHttpReply(exchangeStatus, hrExchangeJson(HR_SESSION_TEXT, 3, ByteArray(40) { 9 }).toByteArray())
    }
    private val logoutHttp = HrHttp { AuthHttpReply(200, ByteArray(0)) }
    private val loginHttp = hrLoginHttp()
    private var exchangeBuilt = 0
    private var logoutBuilt = 0
    private var cryptoBuilt = 0

    private fun root(): BreakerCompositionRoot {
        val built = BreakerCompositionRoot(
            filesDir = tmp.root,
            historyStore = RecordingHistoryStore(),
            serviceController = DictationServiceController(FakePermission(), FakeLauncher()),
            committer = FakeCommitter(),
            authHttp = { _ -> loginHttp },
            keyDerivation = { HrKeyDerivation() },
            keySupplier = { softwareKey },
            exchangeHttp = { _ -> exchangeBuilt++; exchangeHttp },
            logoutHttp = { _ -> logoutBuilt++; logoutHttp },
            cryptoService = { cryptoBuilt++; crypto },
        )
        built.settingsStore.save(AppSettings().copy(serverUrl = address))
        return built
    }

    /** A root that has had one sign-in started and the website's return handed to the flow. */
    private fun returned(): BreakerCompositionRoot {
        val built = root()
        built.signInHolder.begin()
        assertTrue("wiring: handle must answer true", built.handoffReturnFlow.handle(view, link))
        return built
    }

    private fun keysFile(): File = File(tmp.root, "credentials.vault")

    private fun sessionFile(): File = File(tmp.root, "session.vault")

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        for (start in 0..haystack.size - needle.size) {
            var same = true
            for (j in needle.indices) if (haystack[start + j] != needle[j]) same = false
            if (same) return true
        }
        return false
    }

    private fun keysVault(label: String): CredentialFileVault =
        CredentialFileVault(keysFile(), AesSessionSeal(label) { softwareKey })

    @Test
    fun `a return through the flow signs in and sends one exchange without a bearer`() {
        val built = returned()
        assertEquals(
            "wiring: the return must be recorded as TAKEN",
            SignInReturnState.TAKEN,
            built.signInReturnStore.take(),
        )
        assertEquals(
            "wiring: the session text must be the one the exchange gave",
            HR_SESSION_TEXT,
            built.authService.currentSession()?.token,
        )
        assertEquals("wiring: one exchange request", 1, exchangeHttp.sent.size)
        val sent = exchangeHttp.sent[0]
        assertEquals("wiring: the exchange is a post", "POST", sent.method)
        assertEquals("wiring: the exchange goes to the saved address", address + exchangePath, sent.url)
        assertTrue(
            "wiring: the exchange carries the code",
            sent.bodyText.contains("\"code\":\"" + HR_CODE + "\""),
        )
        assertFalse(
            "wiring: the exchange carries no bearer",
            sent.headers.any { it.first == "Authorization" },
        )
        assertEquals("wiring: the package must reach the crypto service", 40, crypto.sealedSize)
        assertEquals(
            "wiring: the attempt private key must reach the crypto service",
            32,
            crypto.privateKeyBytes.size,
        )
        assertTrue("wiring: no logout after a success", logoutHttp.sent.isEmpty())
    }

    @Test
    fun `the session is seen at the saved address and at no other`() {
        val built = returned()
        val pair = built.addressedSession()
        assertNotNull("wiring: the addressed session must be seen", pair)
        assertEquals("wiring: the pair must carry the saved address", address, pair!!.base)
        assertEquals("wiring: the pair must carry the session", HR_SESSION_TEXT, pair.session.token)
        built.settingsStore.save(AppSettings().copy(serverUrl = "https://other.test"))
        assertNull("wiring: another address must not see the session", built.authService.currentSession())
        assertNull("wiring: another address must not see the pair", built.addressedSession())
        built.settingsStore.save(AppSettings().copy(serverUrl = address))
        assertNotNull("wiring: the saved address sees it again", built.authService.currentSession())
    }

    @Test
    fun `the keys file exists under its own label and holds no plain key`() {
        returned()
        assertTrue("wiring: the keys file must exist", keysFile().isFile)
        assertTrue("wiring: the session file must exist", sessionFile().isFile)
        val atRest = keysFile().readBytes()
        assertFalse("wiring: the data key must not be at rest", contains(atRest, hrDekBytes()))
        assertFalse("wiring: the box key must not be at rest", contains(atRest, hrBoxBytes()))
        val read = keysVault(CREDENTIAL_VAULT_PURPOSE).readKeys()
        assertNotNull("wiring: the keys file must open with the credentials label", read)
        assertEquals("wiring: the key version of the exchange", 3, read!!.keyVersion)
        assertArrayEquals("wiring: the data key", hrDekBytes(), read.keys.dek.bytes)
        assertArrayEquals("wiring: the box key", hrBoxBytes(), read.keys.boxPrivateKey.copyBytes())
        assertNull(
            "wiring: the session label must not open the keys file",
            keysVault(SESSION_VAULT_PURPOSE).readKeys(),
        )
        val sessionAtRest = String(sessionFile().readBytes(), Charsets.ISO_8859_1)
        assertFalse("wiring: the session text must not be at rest", sessionAtRest.contains(HR_SESSION_TEXT))
    }

    @Test
    fun `a logout through the auth service removes the keys and the session`() {
        val built = returned()
        built.authService.logout()
        assertFalse("wiring: the keys file must be gone", keysFile().exists())
        assertNull("wiring: no keys may be read", keysVault(CREDENTIAL_VAULT_PURPOSE).readKeys())
        assertNull("wiring: no session may be read", built.authService.currentSession())
        assertFalse("wiring: the session file must be gone", sessionFile().exists())
        val revoke = loginHttp.sent.last()
        assertEquals("wiring: the login transport carries the logout", address + logoutPath, revoke.url)
        assertTrue(
            "wiring: the logout carries the bearer",
            revoke.headers.contains("Authorization" to "Bearer " + HR_SESSION_TEXT),
        )
    }

    @Test
    fun `a password login after a hand-off leaves the keys`() {
        val built = returned()
        built.authService.login("user", "a-very-long-password".toCharArray())
        val read = keysVault(CREDENTIAL_VAULT_PURPOSE).readKeys()
        assertNotNull("wiring: a password login must not remove the keys", read)
        assertEquals("wiring: the key version stays", 3, read!!.keyVersion)
        assertEquals(
            "wiring: the session is now the password one",
            "tok-1",
            built.authService.currentSession()?.token,
        )
    }

    @Test
    fun `a refused exchange leaves no keys and no session and sends no logout`() {
        exchangeStatus = 401
        val built = returned()
        assertEquals("wiring: NOT_FINISHED", SignInReturnState.NOT_FINISHED, built.signInReturnStore.take())
        assertFalse("wiring: no keys file", keysFile().exists())
        assertFalse("wiring: no session file", sessionFile().exists())
        assertNull("wiring: no session", built.authService.currentSession())
        assertTrue("wiring: no logout without a session", logoutHttp.sent.isEmpty())
    }

    @Test
    fun `a package that does not open is revoked on the logout transport only`() {
        crypto = HrFakeCrypto(CryptoRefused())
        val built = returned()
        assertEquals("wiring: NOT_FINISHED", SignInReturnState.NOT_FINISHED, built.signInReturnStore.take())
        assertFalse("wiring: no keys file", keysFile().exists())
        assertFalse("wiring: no session file", sessionFile().exists())
        assertEquals("wiring: one revoke", 1, logoutHttp.sent.size)
        assertEquals(
            "wiring: the revoke goes to the logout path",
            address + logoutPath,
            logoutHttp.sent[0].url,
        )
        assertTrue(
            "wiring: the revoke carries the bearer",
            logoutHttp.sent[0].headers.contains("Authorization" to "Bearer " + HR_SESSION_TEXT),
        )
        assertTrue(
            "wiring: the exchange transport never sees the logout",
            exchangeHttp.sent.none { it.url.endsWith(logoutPath) },
        )
        assertTrue(
            "wiring: the logout transport never sees the exchange",
            logoutHttp.sent.none { it.url.endsWith(exchangePath) },
        )
        assertTrue("wiring: the login transport is not used", loginHttp.sent.isEmpty())
    }

    @Test
    fun `the finish is not built at construction nor by a return with no attempt`() {
        val built = root()
        assertEquals("wiring: no exchange transport at construction", 0, exchangeBuilt)
        assertEquals("wiring: no logout transport at construction", 0, logoutBuilt)
        assertEquals("wiring: no crypto service at construction", 0, cryptoBuilt)
        built.handoffReturnFlow.handle(view, link)
        assertEquals(
            "wiring: a return with no attempt",
            SignInReturnState.NO_ATTEMPT,
            built.signInReturnStore.take(),
        )
        assertEquals("wiring: still no exchange transport", 0, exchangeBuilt)
        assertEquals("wiring: still no crypto service", 0, cryptoBuilt)
        built.signInHolder.begin()
        built.handoffReturnFlow.handle(view, link)
        assertEquals("wiring: one exchange transport after the first return", 1, exchangeBuilt)
        assertEquals("wiring: one logout transport after the first return", 1, logoutBuilt)
        assertEquals("wiring: one crypto service after the first return", 1, cryptoBuilt)
        built.signInHolder.begin()
        built.handoffReturnFlow.handle(view, link)
        assertEquals("wiring: the exchange transport is built once", 1, exchangeBuilt)
        assertEquals("wiring: the logout transport is built once", 1, logoutBuilt)
        assertEquals("wiring: the crypto service is built once", 1, cryptoBuilt)
    }
}
