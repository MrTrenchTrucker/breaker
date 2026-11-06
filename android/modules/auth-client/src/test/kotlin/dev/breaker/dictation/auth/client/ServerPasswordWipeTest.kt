package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The password travels as a CharArray and is wiped after every login/register result. */
class ServerPasswordWipeTest {

    @Test
    fun `after a successful login the handed-over array is all zeros`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.answerLoginOk()
        val password = testPassword()
        val size = password.size
        rig.service().login("ada", password)
        assertEquals("array size unchanged", size, password.size)
        assertFalse("array not all zeros after successful login", password.any { it != '\u0000' })
    }

    @Test
    fun `after a refused login (BAD_CREDENTIALS) the array is all zeros`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.http.answer(401)
        val password = testPassword()
        val size = password.size
        runCatching { rig.service().login("ada", password) }
        assertEquals("array size unchanged", size, password.size)
        assertFalse("array not all zeros after refused login", password.any { it != '\u0000' })
    }

    @Test
    fun `after a successful register the handed-over array is all zeros`() {
        val rig = Rig()
        rig.http.answer(201)
        rig.answerLoginOk()
        val password = testPassword()
        val size = password.size
        rig.service().register("ada", password)
        assertEquals("array size unchanged", size, password.size)
        assertFalse("array not all zeros after successful register", password.any { it != '\u0000' })
    }

    @Test
    fun `after the WEAK_PASSWORD refusal the array is all zeros`() {
        val rig = Rig()
        val short = charArrayOf('a', 'b', 'c', 'd', 'e')
        val size = short.size
        runCatching { rig.service().register("ada", short) }
        assertEquals("array size unchanged", size, short.size)
        assertFalse("array not all zeros after WEAK_PASSWORD refusal", short.any { it != '\u0000' })
    }

    @Test
    fun `after a login that fails inside the key derivation the array is all zeros`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.keys.refusal = KdfRefused(KdfRefusal.MEMORY_ABOVE_CEILING)
        val password = testPassword()
        val size = password.size
        runCatching { rig.service().login("ada", password) }
        assertEquals("array size unchanged", size, password.size)
        assertFalse("array not all zeros after derivation failure", password.any { it != '\u0000' })
    }
}
