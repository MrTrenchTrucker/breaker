package dev.breaker.dictation.stt.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The target value prints fixed text only, and the guarded provider reads the key only for a usable base. */
class ServerTargetTest {

    private companion object {
        const val BASE = "https://example.test:8443"
        const val KEY = "canary key 7741"
    }

    @Test
    fun `the text of a target is the fixed text and holds neither the base nor the key`() {
        val target = ServerTarget(BASE, KEY)
        assertEquals("ServerTarget", target.toString())
        val templated = "$target"
        assertTrue("the template text holds no key", !templated.contains(KEY))
        assertTrue("the template text holds no base", !templated.contains("example.test"))
    }

    @Test
    fun `the two fields give back what was passed`() {
        val withKey = ServerTarget(BASE, KEY)
        assertEquals(BASE, withKey.base)
        assertEquals(KEY, withKey.key)
        val withoutKey = ServerTarget(BASE, null)
        assertEquals(BASE, withoutKey.base)
        assertNull(withoutKey.key)
    }

    @Test
    fun `the guarded provider never calls the key provider for an empty base`() {
        assertKeyNotRead("")
    }

    @Test
    fun `the guarded provider never calls the key provider for a base of another scheme`() {
        assertKeyNotRead("ftp://example.test")
    }

    @Test
    fun `the guarded provider never calls the key provider for a base with a space`() {
        assertKeyNotRead("http://example.test /x")
    }

    @Test
    fun `the guarded provider never calls the key provider for a base with user information`() {
        assertKeyNotRead("http://someone@example.test")
    }

    @Test
    fun `the guarded provider calls the key provider once for a usable base and passes both values on`() {
        var keyReads = 0
        val provider = guardedTarget({ BASE }, { keyReads++; KEY })
        assertEquals("the key is not read before the provider is called", 0, keyReads)
        val target = provider()
        assertEquals("the key provider is called once for a usable base", 1, keyReads)
        assertEquals(BASE, target.base)
        assertEquals(KEY, target.key)
    }

    @Test
    fun `the guarded provider reads both providers again on every call`() {
        var baseReads = 0
        var keyReads = 0
        val provider = guardedTarget({ baseReads++; BASE }, { keyReads++; KEY })
        provider()
        provider()
        assertEquals("the base is read on each call", 2, baseReads)
        assertEquals("the key is read on each call", 2, keyReads)
    }

    private fun assertKeyNotRead(base: String) {
        var keyReads = 0
        val target = guardedTarget({ base }, { keyReads++; KEY })()
        assertEquals("the key provider is not called for the base: $base", 0, keyReads)
        assertEquals(base, target.base)
        assertNull("the key is null for the base: $base", target.key)
    }
}
