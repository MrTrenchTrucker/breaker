package dev.breaker.dictation.wiring

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.auth.client.AuthHttp
import dev.breaker.dictation.auth.client.AuthHttpRequest
import dev.breaker.dictation.auth.client.AuthHttpReply
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.crypto.KdfImpl
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission

/**
 * The root's authService is lazy: constructing the root must not call the transport factory, the
 * key derivation factory or the key supplier, and must not send any network request. The first
 * call of the addressed session accessor builds the service once.
 */
class AuthServiceNotTouchedAtConstructionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class CountingHttp : AuthHttp {
        var sends = 0
        override fun send(request: AuthHttpRequest): AuthHttpReply {
            sends++
            return AuthHttpReply(200, byteArrayOf())
        }
    }

    private val softwareKey = SecretKeySpec(ByteArray(32) { 3 }, "AES")
    private val http = CountingHttp()
    private var httpFactoryCalls = 0
    private val providersGiven = mutableListOf<() -> String>()
    private var derivationFactoryCalls = 0
    private var supplierCalls = 0

    private fun countingRoot() = BreakerCompositionRoot(
        filesDir = tmp.root,
        historyStore = RecordingHistoryStore(),
        serviceController = DictationServiceController(FakePermission(), FakeLauncher()),
        committer = FakeCommitter(),
        authHttp = { provider -> httpFactoryCalls++; providersGiven.add(provider); http },
        keyDerivation = { derivationFactoryCalls++; KdfImpl() },
        keySupplier = { supplierCalls++; softwareKey },
    )

    @Test
    fun `root built with counting supplier and http exposes authService without invoking either`() {
        val root = countingRoot()
        // Nothing has been read yet: no factory ran and no request was sent.
        assertEquals("http factory must not be called at construction", 0, httpFactoryCalls)
        assertEquals("key derivation factory must not be called at construction", 0, derivationFactoryCalls)
        assertEquals("key supplier must not be invoked at construction", 0, supplierCalls)
        // Read the lazy property: must not invoke the supplier or send a request.
        val service = root.authService
        assertNotNull(service)
        assertEquals("key supplier must not be invoked at construction", 0, supplierCalls)
        assertEquals("http.send must not be called at construction", 0, http.sends)
    }

    @Test
    fun `reading the addressed session accessor builds nothing until it is called`() {
        val root = countingRoot()
        val accessor = root.addressedSession
        assertNotNull(accessor)
        assertEquals("http factory must not run on reading the accessor", 0, httpFactoryCalls)
        assertEquals("key derivation factory must not run on reading the accessor", 0, derivationFactoryCalls)
        assertEquals("key supplier must not run on reading the accessor", 0, supplierCalls)
        assertEquals("no request on reading the accessor", 0, http.sends)
    }

    @Test
    fun `calling the accessor builds each factory once and a second call builds nothing more`() {
        val root = countingRoot()
        root.settingsStore.save(AppSettings().copy(serverUrl = "https://first.test"))

        root.addressedSession()
        assertEquals("first call must run the http factory once", 1, httpFactoryCalls)
        // The factory gets the root's stored address provider: it returns the saved address, read live.
        assertEquals("the factory must receive one provider", 1, providersGiven.size)
        assertEquals("the provider must return the saved address", "https://first.test", providersGiven[0]())
        root.settingsStore.save(AppSettings().copy(serverUrl = "https://second.test"))
        assertEquals(
            "the provider must read the address on every call",
            "https://second.test",
            providersGiven[0](),
        )
        root.settingsStore.save(AppSettings().copy(serverUrl = "https://first.test"))
        assertEquals("first call must run the key derivation factory once", 1, derivationFactoryCalls)
        // No session file exists, so the vault never asks for the key and no request is sent.
        assertEquals("no stored session so the key supplier stays unused", 0, supplierCalls)
        assertEquals("no request on a read", 0, http.sends)

        root.addressedSession()
        assertEquals("second call must not run the http factory again", 1, httpFactoryCalls)
        assertEquals("second call must not run the key derivation factory again", 1, derivationFactoryCalls)
    }

    @Test
    fun `calling the accessor with a stored session asks the key supplier for the key`() {
        val record = "{\"user_id\":\"ada\",\"username\":\"ada\",\"token\":\"tok-out\",\"role\":\"user\"," +
            "\"scopes\":[\"transcribe\"],\"server\":\"https://first.test\"}"
        FileSessionVault(authSessionFile(tmp.root), AesSessionSeal { softwareKey }).save(record)
        val root = countingRoot()
        root.settingsStore.save(AppSettings().copy(serverUrl = "https://first.test"))
        assertEquals("key supplier must not run before the first call", 0, supplierCalls)

        val pair = root.addressedSession()

        assertNotNull("a stored session for the saved address must be returned", pair)
        assertEquals("the saved address must be the base", "https://first.test", pair!!.base)
        assertEquals("the key supplier must be asked once for one read", 1, supplierCalls)
    }
}
