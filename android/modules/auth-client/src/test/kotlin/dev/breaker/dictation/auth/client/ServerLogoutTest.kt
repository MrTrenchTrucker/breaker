package dev.breaker.dictation.auth.client

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sign-out: the vault is cleared first, a best-effort logout follows, and the network never blocks it. */
class ServerLogoutTest {
    @Test
    fun `logout clears the vault before it sends the logout request`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        var storedAtSend: String? = ADA_RECORD
        rig.http.beforeSend = { storedAtSend = rig.vault.stored }
        rig.http.answer(200)
        rig.service().logout()
        assertNull(storedAtSend)
        assertEquals("clear", rig.vault.calls.last())
        assertEquals(1, rig.http.requests.size)
    }

    @Test
    fun `logout sends only the bearer header with the stored token`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.answer(200)
        rig.service().logout()
        val request = rig.http.requests.single()
        assertEquals("POST", request.method)
        assertEquals(LOGOUT_URL, request.url)
        assertEquals(listOf("Authorization" to "Bearer tok-out"), request.headers)
    }

    @Test
    fun `logout never throws because of the network`() {
        val scripts = listOf<(Rig) -> Unit>(
            { rig -> rig.http.failWith(IOException("fake connection refused")) },
            { rig -> rig.http.answer(500) },
            { rig -> rig.http.answer(401) },
        )
        for (script in scripts) {
            val rig = Rig()
            rig.vault.stored = ADA_RECORD
            script(rig)
            rig.service().logout()
            assertNull(rig.vault.stored)
        }
    }

    @Test
    fun `logout with nothing stored clears and sends no request`() {
        val rig = Rig()
        rig.service().logout()
        assertEquals(0, rig.http.requests.size)
        assertTrue(rig.vault.calls.contains("clear"))
    }

    @Test
    fun `logout with a bad server address clears the vault and sends nothing`() {
        val rig = Rig()
        rig.base = "ftp://box.example"
        rig.vault.stored = ADA_RECORD
        rig.service().logout()
        assertNull(rig.vault.stored)
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `a vault clear that throws in logout sends no request`() {
        val inner = FakeSessionVault()
        inner.stored = ADA_RECORD
        val vault = FailingVault(inner)
        val failure = IllegalStateException("fake clear refused")
        vault.failClear = failure
        val rig = Rig()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { rig.service(store = vault).logout() })
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `logout with a corrupt stored record clears it and sends no request`() {
        val rig = Rig()
        rig.vault.stored = "not a record"
        rig.service().logout()
        assertNull(rig.vault.stored)
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `logout never throws because of the port whatever it throws`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.service().logout()
        assertEquals(1, rig.http.requests.size)
        assertNull(rig.vault.stored)
    }

    @Test
    fun `logout reads the base address once`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.http.answer(200)
        rig.service().logout()
        assertEquals(1, rig.baseReads)
    }

    @Test
    fun `when the vault read throws logout still clears and sends nothing and rethrows that exception`() {
        val inner = FakeSessionVault()
        inner.stored = ADA_RECORD
        val vault = FailingVault(inner)
        val failure = IllegalStateException("fake read refused")
        vault.failLoad = failure
        val rig = Rig()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { rig.service(store = vault).logout() })
        assertTrue(inner.calls.contains("clear"))
        assertNull(inner.stored)
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `when the vault read and the vault clear both throw logout rethrows the read exception with the clear exception suppressed`() {
        val vault = FailingVault(FakeSessionVault())
        val readFailure = IllegalStateException("fake read refused")
        val clearFailure = IllegalStateException("fake clear refused")
        vault.failLoad = readFailure
        vault.failClear = clearFailure
        val rig = Rig()
        val thrown = assertThrows(IllegalStateException::class.java) { rig.service(store = vault).logout() }
        assertSame(readFailure, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertSame(clearFailure, thrown.suppressed[0])
        assertEquals(0, rig.http.requests.size)
    }
}
