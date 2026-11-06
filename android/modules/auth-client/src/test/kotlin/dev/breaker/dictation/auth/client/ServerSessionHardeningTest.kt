package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A new sign-in revokes the session the vault already holds, at that session's own server. A failed
 * save clears the vault first. An unreadable vault gives no session. A failure the port throws that
 * is not a network failure is a server error. The JSON body is zeroed after the send. A read of the
 * current session waits for a sign-in in progress.
 */
class ServerSessionHardeningTest {
    private val newBase = "https://new.example"
    private val serverErrorSentence = "The server could not complete the request."

    private fun storedBearer(rig: Rig): String = SessionRecord.decode(rig.vault.stored!!)!!.session.token

    private fun storedServer(rig: Rig): String = SessionRecord.decode(rig.vault.stored!!)!!.server

    private fun keepBodies(rig: Rig, live: MutableList<ByteArray>, before: MutableList<ByteArray>) {
        rig.http.beforeSend = { request ->
            request.body?.let { body ->
                live.add(body)
                before.add(body.copyOf())
            }
        }
    }

    private fun assertZeroedAfterSend(live: List<ByteArray>, before: List<ByteArray>, expected: Int) {
        assertEquals(expected, live.size)
        assertEquals(expected, before.size)
        for (i in live.indices) {
            assertEquals("body " + i, before[i].size, live[i].size)
            assertTrue("body " + i + " held data when it was sent", before[i].any { it.toInt() != 0 })
            assertTrue("body " + i + " is not zero after the send", live[i].all { it.toInt() == 0 })
        }
    }

    @Test
    fun `a new sign-in revokes the previous session at its own server`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.base = newBase
        val seenAtRevoke = mutableListOf<String>()
        rig.http.beforeSend = { request ->
            if (request.url == LOGOUT_URL) seenAtRevoke.addAll(rig.vault.calls)
        }
        rig.answerSaltOk()
        rig.answerLoginOk(bearer = "tok-new")
        rig.http.answer(200)
        val session = rig.service().login("ada", testPassword())
        assertEquals("tok-new", session.token)
        assertEquals(3, rig.http.requests.size)
        assertEquals(newBase + "/v1/auth/salt", rig.http.requests[0].url)
        assertEquals(newBase + "/v1/auth/login", rig.http.requests[1].url)
        val revoke = rig.http.requests[2]
        assertEquals("POST", revoke.method)
        assertEquals(LOGOUT_URL, revoke.url)
        assertEquals(listOf("Authorization" to "Bearer tok-out"), revoke.headers)
        assertNull(revoke.body)
        assertTrue("the vault was not read before the revoke", seenAtRevoke.contains("load"))
        assertFalse("the new session was saved before the revoke", seenAtRevoke.contains("save"))
        assertEquals(newBase, storedServer(rig))
        assertEquals("tok-new", storedBearer(rig))
    }

    @Test
    fun `a new sign-in at the same server revokes the previous token there`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.answerSaltOk()
        rig.answerLoginOk(bearer = "tok-new")
        rig.http.answer(200)
        rig.service().login("ada", testPassword())
        assertEquals(listOf(SALT_URL, LOGIN_URL, LOGOUT_URL), rig.http.requests.map { it.url })
        assertEquals(listOf("Authorization" to "Bearer tok-out"), rig.http.requests[2].headers)
        assertEquals("tok-new", storedBearer(rig))
        assertEquals(BASE_URL, storedServer(rig))
    }

    @Test
    fun `a new registration revokes the previous session at its own server`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.base = newBase
        rig.http.answer(201)
        rig.answerLoginOk(bearer = "tok-new")
        rig.http.answer(200)
        val session = rig.service().register("ada", testPassword())
        assertEquals("tok-new", session.token)
        assertEquals(3, rig.http.requests.size)
        assertEquals(newBase + "/v1/auth/register", rig.http.requests[0].url)
        assertEquals(newBase + "/v1/auth/login", rig.http.requests[1].url)
        assertEquals(LOGOUT_URL, rig.http.requests[2].url)
        assertEquals(listOf("Authorization" to "Bearer tok-out"), rig.http.requests[2].headers)
        assertEquals(newBase, storedServer(rig))
        assertEquals("tok-new", storedBearer(rig))
    }

    @Test
    fun `no previous session means no revoke request`() {
        val noEndpoint = SessionRecord.encode(
            AuthSession("ada", "ada", "tok-ftp", UserRole.USER, setOf(TokenScope.TRANSCRIBE)),
            "ftp://old.example",
        )
        for (previous in listOf<String?>(null, "not a record", noEndpoint)) {
            val rig = Rig()
            rig.vault.stored = previous
            rig.answerSaltOk()
            rig.answerLoginOk(bearer = "tok-new")
            val session = rig.service().login("ada", testPassword())
            assertEquals("previous " + previous, "tok-new", session.token)
            val urls = rig.http.requests.map { it.url }
            assertEquals("previous " + previous, listOf(SALT_URL, LOGIN_URL), urls)
            assertEquals("previous " + previous, "tok-new", storedBearer(rig))
        }
    }

    @Test
    fun `a failing revoke never blocks the new sign-in`() {
        val failures = listOf<(Rig) -> Unit>(
            { it.http.failWith(IOException("fake connection refused")) },
            { it.http.failWith(IllegalStateException("fake port failure")) },
        )
        for (failWith in failures) {
            val rig = Rig()
            rig.vault.stored = ADA_RECORD
            rig.answerSaltOk()
            rig.answerLoginOk(bearer = "tok-new")
            failWith(rig)
            val session = rig.service().login("ada", testPassword())
            assertEquals("tok-new", session.token)
            assertEquals(3, rig.http.requests.size)
            assertEquals(LOGOUT_URL, rig.http.requests[2].url)
            assertEquals("tok-new", storedBearer(rig))
        }
    }

    @Test
    fun `an unreadable vault does not block a new sign-in`() {
        val rig = Rig()
        val vault = FailingVault(rig.vault)
        vault.failLoad = IllegalStateException("fake read refused")
        rig.answerSaltOk()
        rig.answerLoginOk(bearer = "tok-new")
        val session = rig.service(store = vault).login("ada", testPassword())
        assertEquals("tok-new", session.token)
        assertEquals(listOf(SALT_URL, LOGIN_URL), rig.http.requests.map { it.url })
        assertEquals(listOf("save"), rig.vault.calls)
        assertEquals("tok-new", storedBearer(rig))
    }

    @Test
    fun `a failed save clears the vault before the new token is revoked`() {
        val rig = Rig()
        val refusal = FakeVaultRefusal()
        rig.vault.saveFailure = refusal
        val seenAtRevoke = mutableListOf<String>()
        rig.http.beforeSend = { request ->
            if (request.url == LOGOUT_URL) seenAtRevoke.addAll(rig.vault.calls)
        }
        rig.answerSaltOk()
        rig.answerLoginOk(bearer = "tok-new")
        rig.http.answer(200)
        val thrown = assertThrows(FakeVaultRefusal::class.java) { rig.service().login("ada", testPassword()) }
        assertSame(refusal, thrown)
        assertTrue("the vault was not cleared before the revoke", seenAtRevoke.contains("clear"))
        val savedAt = seenAtRevoke.indexOf("save")
        val clearedAt = seenAtRevoke.indexOf("clear")
        assertTrue("the save did not come before the clear", savedAt >= 0 && savedAt < clearedAt)
        assertEquals(3, rig.http.requests.size)
        assertEquals(listOf("Authorization" to "Bearer tok-new"), rig.http.requests[2].headers)
        assertNull(rig.vault.stored)
    }

    @Test
    fun `a failing clear after a failed save is added as suppressed and the revoke still happens`() {
        val rig = Rig()
        val refusal = FakeVaultRefusal()
        val inner = FakeSessionVault()
        inner.saveFailure = refusal
        val vault = FailingVault(inner)
        val clearFailure = IllegalStateException("fake clear refused")
        vault.failClear = clearFailure
        rig.answerSaltOk()
        rig.answerLoginOk(bearer = "tok-new")
        rig.http.answer(200)
        val thrown = assertThrows(FakeVaultRefusal::class.java) {
            rig.service(store = vault).login("ada", testPassword())
        }
        assertSame(refusal, thrown)
        assertEquals(listOf<Throwable>(clearFailure), thrown.suppressed.toList())
        assertEquals(3, rig.http.requests.size)
        assertEquals(LOGOUT_URL, rig.http.requests[2].url)
        assertEquals(listOf("Authorization" to "Bearer tok-new"), rig.http.requests[2].headers)
    }

    @Test
    fun `an unreadable vault gives no session and sends nothing`() {
        val rig = Rig()
        val inner = FakeSessionVault()
        val vault = FailingVault(inner)
        vault.failLoad = IllegalStateException("fake read refused")
        assertNull(rig.service(store = vault).currentSession())
        assertEquals(0, rig.http.requests.size)
        assertFalse(inner.calls.contains("clear"))
    }

    @Test
    fun `a RuntimeException from the port during login is a server error`() {
        for (failAt in listOf("salt", "login")) {
            val rig = Rig()
            if (failAt == "login") rig.answerSaltOk()
            rig.http.failWith(IllegalStateException("fake port failure"))
            val failure = assertThrows(AuthFailure::class.java) { rig.service().login("ada", testPassword()) }
            assertEquals("call " + failAt, AuthFailureReason.SERVER_ERROR, failure.reason)
            assertEquals("call " + failAt, serverErrorSentence, failure.message)
            assertNull("call " + failAt, failure.cause)
            assertEquals("call " + failAt, emptyList<String>(), rig.vault.calls)
        }
    }

    @Test
    fun `a RuntimeException from the port during register is a server error`() {
        for (failAt in listOf("register", "login")) {
            val rig = Rig()
            if (failAt == "login") rig.http.answer(201)
            rig.http.failWith(IllegalStateException("fake port failure"))
            val failure = assertThrows(AuthFailure::class.java) {
                rig.service().register("ada", testPassword())
            }
            assertEquals("call " + failAt, AuthFailureReason.SERVER_ERROR, failure.reason)
            assertEquals("call " + failAt, serverErrorSentence, failure.message)
            assertNull("call " + failAt, failure.cause)
            assertEquals("call " + failAt, emptyList<String>(), rig.vault.calls)
            assertNull("call " + failAt, rig.vault.stored)
        }
    }

    @Test
    fun `an IOException from the port is still unreachable`() {
        for (failAt in listOf("salt", "login")) {
            val rig = Rig()
            if (failAt == "login") rig.answerSaltOk()
            rig.http.failWith(IOException("fake connection refused"))
            val failure = assertThrows(AuthFailure::class.java) { rig.service().login("ada", testPassword()) }
            assertEquals("call " + failAt, AuthFailureReason.SERVER_UNREACHABLE, failure.reason)
            assertEquals("call " + failAt, emptyList<String>(), rig.vault.calls)
        }
    }

    @Test
    fun `the request body is zeroed after the send`() {
        val loginRig = Rig()
        val loginLive = mutableListOf<ByteArray>()
        val loginBefore = mutableListOf<ByteArray>()
        keepBodies(loginRig, loginLive, loginBefore)
        loginRig.answerSaltOk()
        loginRig.answerLoginOk()
        loginRig.service().login("ada", testPassword())
        assertZeroedAfterSend(loginLive, loginBefore, 2)
        for (request in loginRig.http.requests) {
            assertTrue(request.body!!.any { it.toInt() != 0 })
            assertTrue(bodyText(request).contains("ada"))
        }
        val registerRig = Rig()
        val registerLive = mutableListOf<ByteArray>()
        val registerBefore = mutableListOf<ByteArray>()
        keepBodies(registerRig, registerLive, registerBefore)
        registerRig.http.answer(201)
        registerRig.answerLoginOk()
        registerRig.service().register("ada", testPassword())
        assertZeroedAfterSend(registerLive, registerBefore, 2)
        for (request in registerRig.http.requests) {
            assertTrue(request.body!!.any { it.toInt() != 0 })
            assertTrue(bodyText(request).contains("ada"))
        }
    }

    @Test
    fun `the request body is zeroed when the send throws`() {
        val failures = listOf<(Rig) -> Unit>(
            { it.http.failWith(IOException("fake connection refused")) },
            { it.http.failWith(IllegalStateException("fake port failure")) },
        )
        for (failWith in failures) {
            for (failAt in listOf("salt", "login")) {
                val rig = Rig()
                val live = mutableListOf<ByteArray>()
                val before = mutableListOf<ByteArray>()
                keepBodies(rig, live, before)
                if (failAt == "login") rig.answerSaltOk()
                failWith(rig)
                assertThrows(AuthFailure::class.java) { rig.service().login("ada", testPassword()) }
                assertZeroedAfterSend(live, before, if (failAt == "login") 2 else 1)
            }
        }
    }

    @Test
    fun `a read of the current session waits for a sign-in in progress`() {
        val rig = Rig()
        val service = rig.service()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val readResult = AtomicReference<AuthSession?>()
        val reader = Thread {
            try {
                readResult.set(service.currentSession())
            } catch (failure: Throwable) {
                errors.add(failure)
            }
        }
        reader.isDaemon = true
        val aliveDuringSignIn = mutableListOf<Boolean>()
        rig.http.beforeSend = { request ->
            if (request.url == LOGIN_URL) {
                reader.start()
                reader.join(300)
                aliveDuringSignIn.add(reader.isAlive)
            }
        }
        rig.answerSaltOk()
        rig.answerLoginOk(bearer = "tok-new")
        service.login("ada", testPassword())
        reader.join(5000)
        assertEquals("the read was not blocked by the sign-in", listOf(true), aliveDuringSignIn)
        assertFalse("the read did not finish", reader.isAlive)
        assertEquals(emptyList<Throwable>(), errors.toList())
        assertEquals("tok-new", readResult.get()?.token)
    }
}
