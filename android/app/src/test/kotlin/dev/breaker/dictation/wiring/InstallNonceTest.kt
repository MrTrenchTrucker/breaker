package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallNonceTest {

    @Test
    fun `a generated nonce is thirty two characters from sixteen random bytes`() {
        assertEquals(32, InstallNonce.generate().length)
    }

    @Test
    fun `every generated nonce uses only lowercase hex digits so it is URL safe`() {
        val nonce: String = InstallNonce.generate()
        assertTrue(nonce.all { ch: Char -> ch in '0'..'9' || ch in 'a'..'f' })
    }

    @Test
    fun `generated nonces are distinct across a bounded run`() {
        val seen: MutableSet<String> = HashSet()
        repeat(200) { seen.add(InstallNonce.generate()) }
        assertEquals(200, seen.size)
    }

    @Test
    fun `an equal nonce compares true`() {
        val first: String = InstallNonce.generate()
        val copy: String = String(first.toCharArray())
        assertTrue(InstallNonce.matches(first, copy))
    }

    @Test
    fun `one changed character compares false`() {
        val first: String = InstallNonce.generate()
        val flipped: String = (if (first[0] == '0') "1" else "0") + first.substring(1)
        assertFalse(InstallNonce.matches(first, flipped))
    }

    @Test
    fun `a nonce of a different length compares false`() {
        val first: String = InstallNonce.generate()
        assertFalse(InstallNonce.matches(first, first + "0"))
    }

    @Test
    fun `a null or empty nonce compares false`() {
        val first: String = InstallNonce.generate()
        assertFalse(InstallNonce.matches(null, first))
        assertFalse(InstallNonce.matches(first, null))
        assertFalse(InstallNonce.matches("", ""))
        assertFalse(InstallNonce.matches(first, ""))
    }
}
