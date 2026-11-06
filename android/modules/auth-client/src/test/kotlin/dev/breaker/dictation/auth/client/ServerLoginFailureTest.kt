package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failed sign-in: each failure reaches the caller with its own reason, and nothing is sent past it. */
class ServerLoginFailureTest {
    private fun loginReason(rig: Rig, username: String = "ada"): AuthFailureReason =
        assertThrows(AuthFailure::class.java) { rig.service().login(username, testPassword()) }.reason

    @Test
    fun `a bad server address stops login before any request is sent`() {
        val rig = Rig()
        rig.base = "ftp://box.example"
        assertEquals(AuthFailureReason.BAD_SERVER_ADDRESS, loginReason(rig))
        assertEquals(0, rig.http.requests.size)
        assertEquals(0, rig.keys.calls.size)
        assertEquals(emptyList<String>(), rig.vault.calls)
    }

    @Test
    fun `a blank username is refused before any request in login`() {
        for (name in listOf("", "   ")) {
            val rig = Rig()
            assertEquals(AuthFailureReason.BAD_USERNAME, loginReason(rig, name))
            assertEquals(0, rig.http.requests.size)
        }
    }

    @Test
    fun `the address check runs before the blank username check in login`() {
        val rig = Rig()
        rig.base = "ftp://box.example"
        assertEquals(AuthFailureReason.BAD_SERVER_ADDRESS, loginReason(rig, ""))
    }

    @Test
    fun `a 404 on the salt request means no such account`() {
        val rig = Rig()
        rig.http.answer(404)
        assertEquals(AuthFailureReason.NO_SUCH_ACCOUNT, loginReason(rig))
        assertEquals(1, rig.http.requests.size)
    }

    @Test
    fun `an unreachable server on the salt request gives server unreachable`() {
        val rig = Rig()
        rig.http.failWith(IOException("fake connection refused"))
        assertEquals(AuthFailureReason.SERVER_UNREACHABLE, loginReason(rig))
    }

    @Test
    fun `a server error on the salt request gives server error`() {
        for (status in listOf(201, 500)) {
            val rig = Rig()
            rig.http.answer(status)
            assertEquals(AuthFailureReason.SERVER_ERROR, loginReason(rig))
        }
    }

    @Test
    fun `an unreadable salt reply gives unreadable reply`() {
        for (body in listOf("", "not json", "[]", "{\"kdf_version\":1}")) {
            val rig = Rig()
            rig.http.answer(200, body)
            assertEquals(AuthFailureReason.UNREADABLE_REPLY, loginReason(rig))
            assertEquals(0, rig.keys.calls.size)
        }
    }

    @Test
    fun `a salt that is not base64 gives unreadable reply`() {
        val rig = Rig()
        rig.http.answer(
            200,
            "{\"salt\":\"!!!!\",\"kdf_params\":{\"memory_kib\":65536,\"iterations\":3,\"parallelism\":1},\"kdf_version\":1}",
        )
        assertEquals(AuthFailureReason.UNREADABLE_REPLY, loginReason(rig))
        assertEquals(0, rig.keys.calls.size)
    }

    @Test
    fun `a valid base64 salt of fifteen bytes passes through as a refusal and nothing is derived`() {
        val rig = Rig()
        rig.http.answer(200, saltReplyBody(salt = ByteArray(15) { 0x03.toByte() }))
        val refusal = assertThrows(KdfRefused::class.java) { rig.service().login("ada", testPassword()) }
        assertEquals(KdfRefusal.BAD_SALT_LENGTH, refusal.reason)
        assertEquals(0, rig.keys.calls.size)
        assertEquals(1, rig.http.requests.size)
    }

    @Test
    fun `a refused guard passes through unchanged and nothing is derived`() {
        val rig = Rig()
        rig.http.answer(200, saltReplyBody(memoryKib = 1))
        val refusal = assertThrows(KdfRefused::class.java) { rig.service().login("ada", testPassword()) }
        assertEquals(KdfRefusal.MEMORY_BELOW_FLOOR, refusal.reason)
        assertEquals(0, rig.keys.calls.size)
        assertEquals(1, rig.http.requests.size)
    }

    @Test
    fun `a refused guard from the salt reply is not turned into unreadable reply`() {
        val rig = Rig()
        rig.http.answer(200, saltReplyBody(version = 2))
        val refusal = assertThrows(KdfRefused::class.java) { rig.service().login("ada", testPassword()) }
        assertEquals(KdfRefusal.UNSUPPORTED_VERSION, refusal.reason)
        assertEquals(0, rig.keys.calls.size)
    }

    @Test
    fun `a refusal from the derivation itself reaches the caller unchanged`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.keys.refusal = KdfRefused(KdfRefusal.MEMORY_ABOVE_CEILING)
        val refusal = assertThrows(KdfRefused::class.java) { rig.service().login("ada", testPassword()) }
        assertEquals(KdfRefusal.MEMORY_ABOVE_CEILING, refusal.reason)
        assertEquals(1, rig.keys.calls.size)
        assertEquals(1, rig.http.requests.size)
    }

    @Test
    fun `a 401 on login gives bad credentials`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.http.answer(401)
        assertEquals(AuthFailureReason.BAD_CREDENTIALS, loginReason(rig))
        assertEquals(2, rig.http.requests.size)
        assertEquals(emptyList<String>(), rig.vault.calls)
    }

    @Test
    fun `a login reply other than 200 or 401 gives server error`() {
        for (status in listOf(404, 500)) {
            val rig = Rig()
            rig.answerSaltOk()
            rig.http.answer(status)
            assertEquals(AuthFailureReason.SERVER_ERROR, loginReason(rig))
        }
    }

    @Test
    fun `an unreachable server on login gives server unreachable`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.http.failWith(IOException("fake connection refused"))
        assertEquals(AuthFailureReason.SERVER_UNREACHABLE, loginReason(rig))
    }

    @Test
    fun `an unreadable login reply gives unreadable reply`() {
        for (body in listOf("not json", "{\"role\":\"user\",\"key_version\":0}")) {
            val rig = Rig()
            rig.answerSaltOk()
            rig.http.answer(200, body)
            assertEquals(AuthFailureReason.UNREADABLE_REPLY, loginReason(rig))
            assertEquals(emptyList<String>(), rig.vault.calls)
        }
    }

    @Test
    fun `a login reply with a blank token gives unreadable reply`() {
        for (blank in listOf("", "   ")) {
            val rig = Rig()
            rig.answerSaltOk()
            rig.answerLoginOk(bearer = blank)
            assertEquals(AuthFailureReason.UNREADABLE_REPLY, loginReason(rig))
            assertEquals(emptyList<String>(), rig.vault.calls)
        }
    }

    @Test
    fun `an unknown role text gives unreadable reply`() {
        for (role in listOf("root", "Admin")) {
            val rig = Rig()
            rig.answerSaltOk()
            rig.answerLoginOk(role = role)
            assertEquals(AuthFailureReason.UNREADABLE_REPLY, loginReason(rig))
            assertEquals(emptyList<String>(), rig.vault.calls)
        }
    }

    @Test
    fun `the derived keys are wiped after every failure once the derivation has run`() {
        val scripts = listOf<(Rig) -> Unit>(
            { rig -> rig.answerSaltOk(); rig.http.answer(401) },
            { rig -> rig.answerSaltOk(); rig.http.answer(200, "not json") },
            { rig -> rig.answerSaltOk(); rig.answerLoginOk(role = "root") },
            { rig -> rig.answerSaltOk(); rig.http.failWith(IOException("fake connection refused")) },
            { rig -> rig.answerSaltOk(); rig.answerLoginOk(); rig.vault.saveFailure = FakeVaultRefusal() },
        )
        for (script in scripts) {
            val rig = Rig()
            script(rig)
            val thrown = runCatching { rig.service().login("ada", testPassword()) }.exceptionOrNull()
            assertTrue("the login did not fail", thrown != null)
            assertWiped(rig.keys)
        }
    }
}
