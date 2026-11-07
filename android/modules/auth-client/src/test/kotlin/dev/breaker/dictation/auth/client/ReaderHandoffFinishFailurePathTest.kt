package dev.breaker.dictation.auth.client

import java.io.IOException
import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.port.HandoffReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for HandoffFinish failure paths and edge cases, read from the AGENTS.md card and ADR-036. */
class ReaderHandoffFinishFailurePathTest {
    private val pkgNotOpen = "{\"token\":\"" + SESSION_TEXT_43 +
            "\",\"role\":\"user\",\"key_version\":0,\"sealed_package\":\"" +
                    "ZXhhbXBsZSBzZWFsZWQgcGFja2FnZSwgbm90IGEgcmVhbCBrZXkAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\",\"username\":\"alice\",\"" +
                    "account_id\":1}"

    @Test
    fun `onHandoff sends nothing for an over long device name`() {
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        val finish = HandoffFinish(
            { address.get() },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            ReaderFinishCredentialVault(),
            ReaderFinishSessionVault(),
            { "a".repeat(65) },
        )
        val receipt = finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals(0, http.exchangeRequests.size)
    }

    @Test
    fun `onHandoff sends nothing for a device name with a control character`() {
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        val finish = HandoffFinish(
            { address.get() },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            ReaderFinishCredentialVault(),
            ReaderFinishSessionVault(),
            { "name\u0007" },
        )
        val receipt = finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals(0, http.exchangeRequests.size)
    }

    @Test
    fun `onHandoff sends nothing for a device name with a surrogate`() {
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        val finish = HandoffFinish(
            { address.get() },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            ReaderFinishCredentialVault(),
            ReaderFinishSessionVault(),
            { "name\uD800" },
        )
        val receipt = finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals(0, http.exchangeRequests.size)
    }

    @Test
    fun `onHandoff refuses a status other than 200 and stores nothing`() {
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        http.exchangeStatus = 401
        http.exchangeBody = exchangeBody()
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
        val receipt = finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals(0, credentials.saveCalls)
        assertEquals(0, sessions.saveCalls)
    }

    @Test
    fun `onHandoff stores nothing when the exchange throws`() {
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        http.throwOnExchange = IOException("cannot reach")
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
        val receipt = finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals(0, credentials.saveCalls)
        assertEquals(0, sessions.saveCalls)
    }

    @Test
    fun `onHandoff clears nothing and sends no logout when the answer cannot be read`() {
        // "not json" has no session text, so tokenShapeIfPresent returns null and no logout is sent.
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        http.exchangeBody = "not json"
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
        val receipt = finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals(0, credentials.wipeCalls)
        assertEquals(0, sessions.clearCalls)
        assertEquals(0, http.logoutRequests.size)
    }

    @Test
    fun `onHandoff sends nothing when the address gives no exchange endpoint`() {
        val address = ReaderFinishAddressProvider().apply { base = "ftp://box.example" }
        val http = ReaderFinishAuthHttp()
        val finish = HandoffFinish(
            { address.get() },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            ReaderFinishCredentialVault(),
            ReaderFinishSessionVault(),
        )
        val receipt = finish.onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals(0, http.exchangeRequests.size)
        assertEquals(0, http.logoutRequests.size)
    }

    @Test
    fun `the address is read once also on a failure path`() {
        val address = ReaderFinishAddressProvider()
        val http = ReaderFinishAuthHttp()
        http.throwOnExchange = IOException("cannot reach")
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
    fun `the attempt is wiped on every path non-200`() {
        val http = ReaderFinishAuthHttp()
        http.exchangeStatus = 401
        http.exchangeBody = exchangeBody()
        val a = wipingAttempt()
        val finish = HandoffFinish(
            { "https://box.example" },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            ReaderFinishCredentialVault(),
            ReaderFinishSessionVault(),
        )
        finish.onHandoff(HANDOFF_CODE, a)
        assertTrue(a.isWiped)
    }

    @Test
    fun `the attempt is wiped on every path exchange throws`() {
        val http = ReaderFinishAuthHttp()
        http.throwOnExchange = IOException("cannot reach")
        val a = wipingAttempt()
        val finish = HandoffFinish(
            { "https://box.example" },
            { http.sendExchange(it) },
            { http.sendLogout(it) },
            ReaderFinishCrypto(),
            ReaderFinishCredentialVault(),
            ReaderFinishSessionVault(),
        )
        finish.onHandoff(HANDOFF_CODE, a)
        assertTrue(a.isWiped)
    }
}
