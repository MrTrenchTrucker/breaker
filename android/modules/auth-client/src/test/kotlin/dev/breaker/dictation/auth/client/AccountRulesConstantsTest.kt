package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRulesConstantsTest {
    @Test
    fun `the account rule constants keep the values the key rules fix`() {
        assertEquals("memory in KiB", 65536, AccountRules.MEMORY_KIB)
        assertEquals("iterations", 3, AccountRules.ITERATIONS)
        assertEquals("parallelism", 1, AccountRules.PARALLELISM)
        assertEquals("kdf version", 1, AccountRules.KDF_VERSION)
        assertEquals("output length", 32, AccountRules.OUTPUT_LENGTH)
        assertEquals("salt bytes", 16, AccountRules.SALT_BYTES)
        assertEquals("minimum password characters", 12, AccountRules.MIN_PASSWORD_CHARS)
    }

    @Test
    fun `the four account paths name the register, salt, login and logout endpoints`() {
        assertEquals("/v1/auth/register", AccountRules.REGISTER_PATH)
        assertEquals("/v1/auth/salt", AccountRules.SALT_PATH)
        assertEquals("/v1/auth/login", AccountRules.LOGIN_PATH)
        assertEquals("/v1/auth/logout", AccountRules.LOGOUT_PATH)
    }

    @Test
    fun `a password of eleven characters is too short for registration`() {
        assertTrue("eleven characters was accepted", !AccountRules.isLongEnough("abcdefghijk"))
    }

    @Test
    fun `a password of twelve characters is long enough for registration`() {
        assertTrue("twelve characters was refused", AccountRules.isLongEnough("abcdefghijkl"))
    }

    @Test
    fun `the length rule counts code points so a character outside the basic plane counts once`() {
        // Ten ASCII characters and one surrogate pair: 12 UTF-16 units, 11 code points.
        val tenPlusOne = "abcdefghij\uD83D\uDE00"
        // Eleven ASCII characters and one surrogate pair: 13 UTF-16 units, 12 code points.
        val elevenPlusOne = "abcdefghijk\uD83D\uDE00"
        assertTrue("ten characters and one outside the basic plane was accepted", !AccountRules.isLongEnough(tenPlusOne))
        assertTrue("eleven characters and one outside the basic plane was refused", AccountRules.isLongEnough(elevenPlusOne))
    }
}
