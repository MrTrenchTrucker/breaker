package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.port.CredentialVault
import dev.breaker.dictation.core.port.HandoffReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

const val EXCHANGE_PATH = "/v1/auth/handoff/exchange"

/** Tests for HandoffFinish happy path and receipt semantics, read from the AGENTS.md card and ADR-036. */
class ReaderHandoffFinishHappyPathTest {
    @Test
    fun `onHandoff sends a POST to the exchange URL with code and secret`() {
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        val crypto = ReaderFinishCrypto()
        val credentials = ReaderFinishCredentialVault()
        val sessions = ReaderFinishSessionVault()
        val finish = HandoffFinish(
            { address.get() },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            crypto,
            credentials,
            sessions,
        )
        http.exchangeBody = exchangeBody()
        val receipt = finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.TAKEN, receipt)
        assertEquals(1, http.exchangeRequests.size)
        assertEquals("POST", http.exchangeRequests[0].method)
        assertEquals("https://box.example" + EXCHANGE_PATH, http.exchangeRequests[0].url)
        val body = String(http.exchangeRequests[0].body ?: ByteArray(0), Charsets.UTF_8)
        assertTrue("body contains the code", body.contains(HANDOFF_CODE))
        assertTrue("body contains the secret", body.contains(VERIFIER_43))
    }

    @Test
    fun `onHandoff sends no Authorization header`() {
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        val finish = HandoffFinish(
            { address.get() },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            ReaderFinishCredentialVault(),
            ReaderFinishSessionVault(),
        )
        http.exchangeBody = exchangeBody()
        finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        assertFalse(
            "no Authorization header",
            http.exchangeRequests[0].headers.any { it.first == "Authorization" },
        )
    }

    @Test
    fun `onHandoff includes device name when given`() {
        val http = ReaderFinishAuthHttp()
        val finish = HandoffFinish(
            { "https://box.example" },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            ReaderFinishCredentialVault(),
            ReaderFinishSessionVault(),
            { "Pixel 8" },
        )
        http.exchangeBody = exchangeBody()
        finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        val body = String(http.exchangeRequests[0].body ?: ByteArray(0), Charsets.UTF_8)
        assertTrue("body contains device_name", body.contains("device_name"))
        assertTrue("body contains Pixel 8", body.contains("Pixel 8"))
    }

    @Test
    fun `onHandoff omits device name when null`() {
        val http = ReaderFinishAuthHttp()
        val finish = HandoffFinish(
            { "https://box.example" },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            ReaderFinishCredentialVault(),
            ReaderFinishSessionVault(),
            { null },
        )
        http.exchangeBody = exchangeBody()
        finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        val body = String(http.exchangeRequests[0].body ?: ByteArray(0), Charsets.UTF_8)
        assertFalse("no device_name in body", body.contains("device_name"))
    }

    @Test
    fun `the receipt is TAKEN only on success`() {
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        http.exchangeBody = exchangeBody()
        val finish = HandoffFinish(
            { address.get() },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            ReaderFinishCredentialVault(),
            ReaderFinishSessionVault(),
        )
        assertEquals(HandoffReceipt.TAKEN, finish.onHandoff(HANDOFF_CODE, wipingAttempt()))
    }

    @Test
    fun `the address is read once per hand-off`() {
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        http.exchangeBody = exchangeBody()
        val finish = HandoffFinish(
            { address.get() },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            ReaderFinishCredentialVault(),
            ReaderFinishSessionVault(),
        )
        finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(1, address.reads)
    }

    @Test
    fun `keys are stored before the session`() {
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        http.exchangeBody = exchangeBody()
        val credentials = ReaderFinishCredentialVault()
        val sessions = ReaderFinishSessionVault()
        val saveOrder: MutableList<String> = mutableListOf()
        val finish = HandoffFinish(
            { address.get() },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            object : CredentialVault {
                override fun saveKeys(keys: dev.breaker.dictation.core.model.AccountKeys, keyVersion: Int) {
                    saveOrder += "keys"
                    credentials.saveCalls += 1
                }
                override fun readKeys(): dev.breaker.dictation.core.model.StoredAccountKeys? = null
                override fun wipeAll() {
                    credentials.wipeCalls += 1
                }
            },
            object : SessionVault {
                override fun save(record: String) {
                    saveOrder += "session"
                    sessions.saveCalls += 1
                }
                override fun load(): String? = null
                override fun clear() {
                    sessions.clearCalls += 1
                }
            },
        )
        finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(listOf("keys", "session"), saveOrder)
    }
}
