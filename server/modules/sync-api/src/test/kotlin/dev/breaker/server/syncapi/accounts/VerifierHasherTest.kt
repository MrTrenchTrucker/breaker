package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.accounts.AccountFixtures.FAST_ITERATIONS
import dev.breaker.server.syncapi.accounts.AccountFixtures.fastHasher
import dev.breaker.server.syncapi.accounts.AccountFixtures.fixtureSalt
import dev.breaker.server.syncapi.accounts.AccountFixtures.flipBit
import dev.breaker.server.syncapi.accounts.AccountFixtures.hex
import dev.breaker.server.syncapi.accounts.AccountFixtures.verifier
import dev.breaker.server.syncapi.accounts.AccountFixtures.verifierBytes
import dev.breaker.server.syncapi.db.TestDatabases
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class VerifierHasherTest {

    private val hasher = fastHasher()
    private val subject = verifier(1)

    private fun withSalt(stored: StoredVerifier, salt: ByteArray) =
        StoredVerifier(stored.algo, stored.iterations, salt, stored.hash)

    private fun withHash(stored: StoredVerifier, hash: ByteArray) =
        StoredVerifier(stored.algo, stored.iterations, stored.salt, hash)

    @Test
    fun `the hasher constants are the agreed ones`() {
        assertEquals("algo", "pbkdf2-hmac-sha256", VerifierHasher.ALGO)
        assertEquals("default iterations", 600_000, VerifierHasher.DEFAULT_ITERATIONS)
        assertEquals("salt bytes", 16, VerifierHasher.SALT_BYTES)
        assertEquals("hash bytes", 32, VerifierHasher.HASH_BYTES)
    }

    @Test
    fun `hashNew reports the algorithm, this hasher's cost and the right sizes`() {
        val stored = hasher.hashNew(subject)
        assertEquals("algo", VerifierHasher.ALGO, stored.algo)
        assertEquals("iterations", FAST_ITERATIONS, stored.iterations)
        assertEquals("salt size", 16, stored.salt.size)
        assertEquals("hash size", 32, stored.hash.size)
    }

    @Test
    fun `hashNew stores the pbkdf2 of the verifier under the stored salt`() {
        val stored = hasher.hashNew(subject)
        val expected = pbkdf2HmacSha256(verifierBytes(1), stored.salt, FAST_ITERATIONS)
        assertArrayEquals("the stored hash must be the derivation with the stored salt and cost", expected, stored.hash)
    }

    @Test
    fun `a hasher built with the default constructor reports 600000 iterations`() {
        val stored = VerifierHasher().hashNew(subject)
        assertEquals("default cost", 600_000, stored.iterations)
    }

    @Test
    fun `two hashNew calls on the same verifier differ in salt and in hash`() {
        val first = hasher.hashNew(subject)
        val second = hasher.hashNew(subject)
        assertNotEquals("the salts must be fresh", hex(first.salt), hex(second.salt))
        assertNotEquals("the hashes must differ with the salts", hex(first.hash), hex(second.hash))
    }

    @Test
    fun `matches accepts the verifier that was hashed`() {
        val stored = hasher.hashNew(subject)
        assertTrue("the same verifier must match", hasher.matches(subject, stored))
    }

    @Test
    fun `matches refuses a verifier with a bit flipped in the first or the last byte`() {
        val stored = hasher.hashNew(subject)
        for (index in listOf(0, 31)) {
            val other = checkNotNull(AuthVerifier.fromBytes(flipBit(verifierBytes(1), index)))
            assertFalse("a flip in byte $index must not match", hasher.matches(other, stored))
        }
    }

    @Test
    fun `matches refuses a stored salt with one bit changed`() {
        val stored = hasher.hashNew(subject)
        assertFalse("flipped salt bit", hasher.matches(subject, withSalt(stored, flipBit(stored.salt, 0))))
    }

    @Test
    fun `matches refuses a stored hash with one bit changed in the first or the last byte`() {
        val stored = hasher.hashNew(subject)
        for (index in listOf(0, 31)) {
            val damaged = withHash(stored, flipBit(stored.hash, index))
            assertFalse("a flip in hash byte $index must not match", hasher.matches(subject, damaged))
        }
    }

    @Test
    fun `matches uses the stored cost, not the cost of the hasher asked`() {
        val stored = hasher.hashNew(subject)
        assertTrue("a hasher with cost 1 must still match", VerifierHasher(iterations = 1).matches(subject, stored))
        assertTrue("the default hasher must still match", VerifierHasher().matches(subject, stored))
    }

    @Test
    fun `matches refuses an unknown algorithm even when the hash bytes are right`() {
        val stored = hasher.hashNew(subject)
        val relabelled = StoredVerifier("pbkdf2-hmac-sha512", stored.iterations, stored.salt, stored.hash)
        assertFalse("an unknown algo must not match", hasher.matches(subject, relabelled))
    }

    @Test
    fun `matches agrees with an independently computed stored verifier`() {
        val stored = StoredVerifier(
            VerifierHasher.ALGO,
            1000,
            fixtureSalt(),
            AccountFixtures.fromHex(AccountFixtures.FIXTURE_HASH_HEX),
        )
        val password = checkNotNull(AuthVerifier.fromBytes(AccountFixtures.fixturePassword()))
        assertTrue("bytes 0..31 with the known answer must match", hasher.matches(password, stored))
        assertFalse("another verifier must not match the known answer", hasher.matches(subject, stored))
    }

    @Test
    fun `a hasher with zero iterations is refused`() {
        val failure = TestDatabases.expectFailure<IllegalArgumentException>("zero iterations") {
            VerifierHasher(iterations = 0)
        }
        assertTrue("the refusal must carry the module prefix: ${failure.message}", failure.message.orEmpty().startsWith("sync-api:"))
    }
}
