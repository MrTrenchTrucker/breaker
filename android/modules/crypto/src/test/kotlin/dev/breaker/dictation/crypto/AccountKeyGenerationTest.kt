package dev.breaker.dictation.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the key generation and the public key check of [AccountKeyServiceImpl].
 *
 * Most tests feed the service known bytes through the entropy function, so every value can be
 * checked against the shared X25519 vectors. Only the test of the public constructor reads the
 * real random source, and it asserts nothing about the random bytes beyond their length and that
 * two keys differ.
 */
class AccountKeyGenerationTest : CryptoServiceTestFixture() {
    /** Hands out the given arrays in order. It keeps what was asked and the arrays it handed out. */
    private class Entropy(vararg values: ByteArray) {
        private val answers = values.map { it.copyOf() }
        val sizesAsked = mutableListOf<Int>()
        val handedOut = mutableListOf<ByteArray>()

        fun next(size: Int): ByteArray {
            sizesAsked.add(size)
            val bytes = answers[sizesAsked.size - 1].copyOf()
            handedOut.add(bytes)
            return bytes
        }
    }

    private fun serviceFor(entropy: Entropy) = AccountKeyServiceImpl(CountingNonces(nonceOne), entropy::next)

    private fun allZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

    private fun x25519Named(name: String): X25519Vector = CryptoVectorData.x25519.single { it.name == name }

    /** A failure means the data key is not the 32 bytes asked for, or the temporary array is left. */
    @Test
    fun `generateDataKey asks once for 32 bytes and keeps them and zeroes the temporary array`() {
        val entropy = Entropy(patterned(0x40, 32))

        val key = serviceFor(entropy).generateDataKey()

        assertEquals("crypto: entropy must be asked once for 32", listOf(32), entropy.sizesAsked)
        assertArrayEquals("crypto: the data key is not the entropy bytes", patterned(0x40, 32), key.bytes)
        assertNotSame("crypto: the key shares the temporary array", entropy.handedOut[0], key.bytes)
        assertTrue("crypto: the temporary array was left in memory", allZero(entropy.handedOut[0]))
    }

    /** A failure means a wrong-length answer was accepted, or left in memory. */
    @Test
    fun `generateDataKey refuses an answer of 31 or 33 bytes and zeroes it`() {
        for (size in intArrayOf(31, 33)) {
            val entropy = Entropy(patterned(0x40, size))

            refused("crypto: a data key answer of $size bytes was accepted") {
                serviceFor(entropy).generateDataKey()
            }

            assertTrue("crypto: the refused $size byte answer was left", allZero(entropy.handedOut[0]))
        }
    }

    /** A failure means the pair is not made from the 32 bytes asked for, or the temporary is left. */
    @Test
    fun `generateBoxKeyPair asks once for 32 bytes and keeps them and zeroes the temporary array`() {
        val entropy = Entropy(patterned(0x40, 32))

        val pair = serviceFor(entropy).generateBoxKeyPair()

        assertEquals("crypto: entropy must be asked once for 32", listOf(32), entropy.sizesAsked)
        val privateBytes = pair.privateKey.copyBytes()
        assertArrayEquals("crypto: private key is not the entropy bytes", patterned(0x40, 32), privateBytes)
        assertEquals("crypto: the public key must be 32 bytes", 32, pair.copyPublicKey().size)
        assertTrue("crypto: the temporary array was left in memory", allZero(entropy.handedOut[0]))
    }

    /** A failure means a wrong-length answer was accepted for the box key, or left in memory. */
    @Test
    fun `generateBoxKeyPair refuses an answer of 31 or 33 bytes and zeroes it`() {
        for (size in intArrayOf(31, 33)) {
            val entropy = Entropy(patterned(0x40, size))

            refused("crypto: a box key answer of $size bytes was accepted") {
                serviceFor(entropy).generateBoxKeyPair()
            }

            assertTrue("crypto: the refused $size byte answer was left", allZero(entropy.handedOut[0]))
        }
    }

    /** A failure means the public key of a shared private key is not the shared public key. */
    @Test
    fun `generateBoxKeyPair gives the shared public key for every x25519 vector`() {
        for (v in CryptoVectorData.x25519) {
            val entropy = Entropy(hex(v.privateHex))

            val pair = serviceFor(entropy).generateBoxKeyPair()

            val privateBytes = pair.privateKey.copyBytes()
            assertArrayEquals("crypto vector ${v.name}: public key", hex(v.publicHex), pair.copyPublicKey())
            assertArrayEquals("crypto vector ${v.name}: private key", hex(v.privateHex), privateBytes)
            assertEquals("crypto vector ${v.name}: entropy calls", listOf(32), entropy.sizesAsked)
            assertTrue("crypto vector ${v.name}: temporary left", allZero(entropy.handedOut[0]))
        }
    }

    /** A failure means the check rejects a matching pair of a shared vector. */
    @Test
    fun `verifyBoxPublicKey accepts the matching pair of every x25519 vector`() {
        val service = serviceFor(Entropy())
        for (v in CryptoVectorData.x25519) {
            val pair = serviceFor(Entropy(hex(v.privateHex))).generateBoxKeyPair()

            service.verifyBoxPublicKey(pair.privateKey, hex(v.publicHex))
        }
    }

    /** A failure means a changed, short, long or foreign public key was accepted. */
    @Test
    fun `verifyBoxPublicKey refuses a flipped bit and 31 and 33 bytes and the public key of another`() {
        val service = serviceFor(Entropy())
        val alice = x25519Named("rfc7748-6.1-alice")
        val bob = x25519Named("rfc7748-6.1-bob")
        val alicePrivate = serviceFor(Entropy(hex(alice.privateHex))).generateBoxKeyPair().privateKey
        val alicePublic = hex(alice.publicHex)

        service.verifyBoxPublicKey(alicePrivate, alicePublic)

        refused("crypto: a public key with one flipped bit was accepted") {
            service.verifyBoxPublicKey(alicePrivate, flipOneBit(alicePublic))
        }
        refused("crypto: a public key of 31 bytes was accepted") {
            service.verifyBoxPublicKey(alicePrivate, alicePublic.copyOf(31))
        }
        refused("crypto: a public key of 33 bytes was accepted") {
            service.verifyBoxPublicKey(alicePrivate, alicePublic + byteArrayOf(0))
        }
        refused("crypto: the public key of another private key was accepted") {
            service.verifyBoxPublicKey(alicePrivate, hex(bob.publicHex))
        }
    }

    /** A failure means the public constructor does not make working keys from the real source. */
    @Test
    fun `the default service makes two different 32 byte data keys and a pair that verifies`() {
        val service = AccountKeyServiceImpl()

        val first = service.generateDataKey()
        val second = service.generateDataKey()
        val pair = service.generateBoxKeyPair()

        assertEquals("crypto: the first data key must be 32 bytes", 32, first.bytes.size)
        assertEquals("crypto: the second data key must be 32 bytes", 32, second.bytes.size)
        assertFalse("crypto: two data keys were equal", first.bytes.contentEquals(second.bytes))
        service.verifyBoxPublicKey(pair.privateKey, pair.copyPublicKey())
    }
}
