package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.port.KeyDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** One Argon2id derivation for each login or register, whatever the key step does. */
class KeyDeriveCountTest {
    /** A control, not a mutant of the service: a derivation that runs twice for each call. */
    private class TwiceDerivation(private val inner: FakeKeyDerivation) : KeyDerivation {
        override fun deriveKeys(password: CharArray, salt: ByteArray, kdfParams: KdfParams): DerivedKeys {
            inner.deriveKeys(password, salt, kdfParams).wipe()
            return inner.deriveKeys(password, salt, kdfParams)
        }
    }

    private fun assertDeriveOnce(case: String, derive: FakeKeyDerivation) {
        val observed = derive.calls.size
        assertEquals(case + ": derive count observed " + observed, 1, observed)
    }

    @Test
    fun `a keyless login derives exactly once`() {
        val kr = KeyRig()
        kr.queueBootstrap()
        kr.login()
        assertDeriveOnce("keyless login", kr.derive)
    }

    @Test
    fun `a keyed login derives exactly once`() {
        val kr = KeyRig()
        kr.rig.answerSaltOk()
        kr.http.answer(200, keyedLoginBody("tok-k", 3))
        kr.login()
        assertDeriveOnce("keyed login", kr.derive)
    }

    @Test
    fun `a register derives exactly once`() {
        val kr = KeyRig()
        kr.http.answer(201)
        kr.rig.answerLoginOk("tok-r")
        kr.http.answer(200, keysReplyBody(1))
        kr.service().register("ada", testPassword())
        assertDeriveOnce("register", kr.derive)
    }

    @Test
    fun `a login whose keys upload gets 409 and then a second login derives exactly once`() {
        val kr = KeyRig()
        kr.rig.answerSaltOk()
        kr.rig.answerLoginOk("tok-1")
        kr.http.answer(409)
        kr.http.answer(200, keyedLoginBody("tok-2", 3))
        kr.login()
        val logins = kr.urls().count { it == LOGIN_URL }
        assertEquals("login requests sent", 2, logins)
        assertDeriveOnce("409 then second login", kr.derive)
    }

    @Test
    fun `the counter reads two when a flow derives twice`() {
        val kr = KeyRig()
        kr.queueBootstrap()
        kr.service(using = TwiceDerivation(kr.derive)).login("ada", testPassword())
        assertEquals("the counter did not read the second derivation", 2, kr.derive.calls.size)
        assertThrows(AssertionError::class.java) { assertDeriveOnce("control", kr.derive) }
    }
}
