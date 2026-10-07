package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.accounts.AccountFixtures.fixturePassword
import dev.breaker.server.syncapi.accounts.AccountFixtures.fixtureSalt
import dev.breaker.server.syncapi.accounts.AccountFixtures.flipBit
import dev.breaker.server.syncapi.accounts.AccountFixtures.hex
import dev.breaker.server.syncapi.db.TestDatabases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The expected hex strings were computed by Python's hashlib.pbkdf2_hmac, not by the code under test.
internal class Pbkdf2Test {

    private val password = "password".toByteArray(Charsets.US_ASCII)
    private val salt = "salt".toByteArray(Charsets.US_ASCII)

    @Test
    fun `pbkdf2 gives the known answer for one iteration`() {
        assertEquals(
            "one iteration must equal a single HMAC over salt and block index 1",
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(pbkdf2HmacSha256(password, salt, 1)),
        )
    }

    @Test
    fun `pbkdf2 gives the known answer for two iterations`() {
        assertEquals(
            "the second iteration must be XORed into the result",
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(pbkdf2HmacSha256(password, salt, 2)),
        )
    }

    @Test
    fun `pbkdf2 gives the known answer for 4096 iterations`() {
        assertEquals(
            "every iteration must chain from the previous HMAC output",
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(pbkdf2HmacSha256(password, salt, 4096)),
        )
    }

    @Test
    fun `pbkdf2 gives the known answer for binary input with 1000 iterations`() {
        assertEquals(
            "a password of bytes 0..31 and a salt of bytes 16..31 must give the independent answer",
            AccountFixtures.FIXTURE_HASH_HEX,
            hex(pbkdf2HmacSha256(fixturePassword(), fixtureSalt(), 1000)),
        )
    }

    @Test
    fun `pbkdf2 refuses zero iterations`() {
        val failure = TestDatabases.expectFailure<IllegalArgumentException>("zero iterations") {
            pbkdf2HmacSha256(password, salt, 0)
        }
        assertTrue("the refusal must carry the module prefix: ${failure.message}", failure.message.orEmpty().startsWith("sync-api:"))
    }

    @Test
    fun `pbkdf2 refuses an empty password`() {
        val failure = TestDatabases.expectFailure<IllegalArgumentException>("empty password") {
            pbkdf2HmacSha256(ByteArray(0), salt, 1)
        }
        assertTrue("the refusal must carry the module prefix: ${failure.message}", failure.message.orEmpty().startsWith("sync-api:"))
    }

    @Test
    fun `pbkdf2 returns exactly 32 bytes`() {
        assertEquals("output length", 32, pbkdf2HmacSha256(password, salt, 3).size)
    }

    @Test
    fun `pbkdf2 changes when one bit of the salt changes`() {
        val base = pbkdf2HmacSha256(fixturePassword(), fixtureSalt(), 10)
        val changed = pbkdf2HmacSha256(fixturePassword(), flipBit(fixtureSalt(), 15), 10)
        assertNotEquals("the salt must take part in the derivation", hex(base), hex(changed))
    }
}
