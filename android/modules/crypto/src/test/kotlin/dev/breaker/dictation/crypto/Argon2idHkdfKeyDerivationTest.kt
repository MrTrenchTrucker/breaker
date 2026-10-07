package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The key derivation checks the settings first, turns the password into NFC UTF-8
 * bytes, runs Argon2id, splits the tag into the key and the verifier, and zeroes
 * every intermediate array on every path.
 *
 * Almost every test uses a recording stand-in for the Argon2id function, so it costs a
 * few KiB. Only the last two tests run the real Argon2id (about 64 MiB each).
 * Expected values come from independent implementations: the split values from an
 * independent HKDF, the full-cost values from two independent Argon2id libraries
 * (libsodium and the reference Argon2 library) plus an independent HKDF.
 */
class Argon2idHkdfKeyDerivationTest {

    /** Records what it is given and hands back a fresh copy of [tag] on every call. */
    private class SpyHash(private val tag: ByteArray = ByteArray(32) { 0x42.toByte() }) : Argon2idFunction {
        var calls = 0
        var seenMemoryKib = -1
        var seenIterations = -1
        var seenParallelism = -1
        var seenOutputLength = -1
        lateinit var seenPasswordCopy: ByteArray
        lateinit var seenPasswordReference: ByteArray
        lateinit var seenSaltCopy: ByteArray
        lateinit var returnedAtCallTime: ByteArray
        lateinit var returnedReference: ByteArray

        override fun hash(
            password: ByteArray,
            salt: ByteArray,
            memoryKib: Int,
            iterations: Int,
            parallelism: Int,
            outputLength: Int,
        ): ByteArray {
            calls++
            seenPasswordReference = password
            seenPasswordCopy = password.copyOf()
            seenSaltCopy = salt.copyOf()
            seenMemoryKib = memoryKib
            seenIterations = iterations
            seenParallelism = parallelism
            seenOutputLength = outputLength
            returnedReference = tag.copyOf()
            returnedAtCallTime = returnedReference.copyOf()
            return returnedReference
        }
    }

    /** Remembers the password array it was given, then fails. */
    private class ThrowingHash(val failure: IllegalStateException) : Argon2idFunction {
        var calls = 0
        lateinit var seenPasswordReference: ByteArray
        lateinit var seenPasswordCopy: ByteArray

        override fun hash(
            password: ByteArray,
            salt: ByteArray,
            memoryKib: Int,
            iterations: Int,
            parallelism: Int,
            outputLength: Int,
        ): ByteArray {
            calls++
            seenPasswordReference = password
            seenPasswordCopy = password.copyOf()
            throw failure
        }
    }

    private val validParams = KdfParams(65536, 3, 1, 32, 1)
    private val countingTag = ByteArray(32) { it.toByte() }
    private val countingKek = "64ab835ac3c4c27e630723be2516fd9fca90ec58ade981222aa0219812915995"
    private val countingVerifier = "69503b3190a0704fca87cd50ee5819dc592fe5ab476416aff2e9408cff83a4e8"

    private fun salt(first: Int = 0) = ByteArray(16) { (first + it).toByte() }

    private fun ByteArray.isAllZero() = all { it == 0.toByte() }

    private fun DerivedKeys.kekHex() = kek.copyBytes().toHex()

    private fun DerivedKeys.verifierHex() = authVerifier.copyBytes().toHex()

    private fun assertCountingKeys(message: String, keys: DerivedKeys) {
        assertEquals("$message: key-encryption key", countingKek, keys.kekHex())
        assertEquals("$message: verifier", countingVerifier, keys.verifierHex())
    }

    /** A failure means a setting was changed, fixed to a constant, or not passed on to the hash function. */
    @Test
    fun `every setting the caller gave reaches the hash function unchanged`() {
        val spy = SpyHash()
        val given = salt(0x20)
        Argon2idHkdfKeyDerivation(spy).deriveKeys("plain ASCII 12345", given, KdfParams(65600, 4, 2, 32, 1))
        assertEquals("number of hash calls", 1, spy.calls)
        assertEquals("memory", 65600, spy.seenMemoryKib)
        assertEquals("iterations", 4, spy.seenIterations)
        assertEquals("parallelism", 2, spy.seenParallelism)
        assertEquals("output length", 32, spy.seenOutputLength)
        assertArrayEquals("salt", given, spy.seenSaltCopy)
        assertArrayEquals("password bytes", "plain ASCII 12345".toByteArray(Charsets.US_ASCII), spy.seenPasswordCopy)
    }

    /** A failure means the lowest accepted settings were refused or changed on the way. */
    @Test
    fun `the lowest accepted settings are passed on unchanged`() {
        val spy = SpyHash()
        Argon2idHkdfKeyDerivation(spy).deriveKeys("pw", salt(), KdfParams(65536, 3, 1, 32, 1))
        assertEquals("number of hash calls", 1, spy.calls)
        assertEquals("memory", 65536, spy.seenMemoryKib)
        assertEquals("iterations", 3, spy.seenIterations)
        assertEquals("parallelism", 1, spy.seenParallelism)
        assertEquals("output length", 32, spy.seenOutputLength)
    }

    /** A failure means the highest accepted settings were refused or changed on the way. */
    @Test
    fun `the highest accepted settings are passed on unchanged`() {
        val spy = SpyHash()
        Argon2idHkdfKeyDerivation(spy).deriveKeys("pw", salt(), KdfParams(262144, 10, 4, 32, 1))
        assertEquals("number of hash calls", 1, spy.calls)
        assertEquals("memory", 262144, spy.seenMemoryKib)
        assertEquals("iterations", 10, spy.seenIterations)
        assertEquals("parallelism", 4, spy.seenParallelism)
        assertEquals("output length", 32, spy.seenOutputLength)
    }

    /** A failure means a refused input still reached the hash function, or was refused for another reason. */
    @Test
    fun `each refusal reason is raised before the hash function is called`() {
        val offending = mapOf(
            KdfRefusal.MEMORY_BELOW_FLOOR to Pair(KdfParams(65535, 3, 1, 32, 1), salt()),
            KdfRefusal.MEMORY_ABOVE_CEILING to Pair(KdfParams(262145, 3, 1, 32, 1), salt()),
            KdfRefusal.ITERATIONS_BELOW_FLOOR to Pair(KdfParams(65536, 2, 1, 32, 1), salt()),
            KdfRefusal.ITERATIONS_ABOVE_CEILING to Pair(KdfParams(65536, 11, 1, 32, 1), salt()),
            KdfRefusal.PARALLELISM_BELOW_FLOOR to Pair(KdfParams(65536, 3, 0, 32, 1), salt()),
            KdfRefusal.PARALLELISM_ABOVE_CEILING to Pair(KdfParams(65536, 3, 5, 32, 1), salt()),
            KdfRefusal.OUTPUT_LENGTH_NOT_32 to Pair(KdfParams(65536, 3, 1, 31, 1), salt()),
            KdfRefusal.BAD_SALT_LENGTH to Pair(validParams, ByteArray(15)),
            KdfRefusal.UNSUPPORTED_VERSION to Pair(KdfParams(65536, 3, 1, 32, 2), salt()),
        )
        assertEquals("every refusal reason has a case", KdfRefusal.values().toSet(), offending.keys)
        for ((reason, input) in offending) {
            val spy = SpyHash()
            try {
                Argon2idHkdfKeyDerivation(spy).deriveKeys("pw", input.second, input.first)
                fail("$reason: the offending input was accepted")
            } catch (e: KdfRefused) {
                assertEquals("$reason: refusal reason", reason, e.reason)
            }
            assertEquals("$reason: hash calls after a refusal", 0, spy.calls)
        }
    }

    /** A failure means the labels or the split are not wired into the class. */
    @Test
    fun `a known tag gives the known key and verifier`() {
        val spy = SpyHash(countingTag)
        val keys = Argon2idHkdfKeyDerivation(spy).deriveKeys("pw", salt(), validParams)
        assertCountingKeys("counting tag", keys)
    }

    /** A failure means the password reached the hash function without being normalised. */
    @Test
    fun `decomposed and composed passwords reach the hash as the same composed bytes`() {
        val composedBytes = "636166c3a920686f727365206261747465727920737461706c65"
        val decomposedSpy = SpyHash(countingTag)
        val composedSpy = SpyHash(countingTag)
        val fromDecomposed = Argon2idHkdfKeyDerivation(decomposedSpy)
            .deriveKeys("cafe\u0301 horse battery staple", salt(), validParams)
        val fromComposed = Argon2idHkdfKeyDerivation(composedSpy)
            .deriveKeys("caf\u00E9 horse battery staple", salt(), validParams)
        assertEquals("bytes seen for the decomposed spelling", composedBytes, decomposedSpy.seenPasswordCopy.toHex())
        assertEquals("bytes seen for the composed spelling", composedBytes, composedSpy.seenPasswordCopy.toHex())
        assertEquals("key for the two spellings", fromComposed.kekHex(), fromDecomposed.kekHex())
        assertEquals("verifier for the two spellings", fromComposed.verifierHex(), fromDecomposed.verifierHex())
    }

    /** A failure means a compatibility mapping (NFKC) was applied: the ligature would reach the hash as 66 69 78. */
    @Test
    fun `a ligature reaches the hash unchanged`() {
        val spy = SpyHash()
        Argon2idHkdfKeyDerivation(spy).deriveKeys("\uFB01x", salt(), validParams)
        assertEquals("bytes seen", "efac8178", spy.seenPasswordCopy.toHex())
    }

    /** A failure means the trailing space of the password was trimmed before hashing. */
    @Test
    fun `a trailing space reaches the hash`() {
        val spy = SpyHash()
        Argon2idHkdfKeyDerivation(spy).deriveKeys("abc ", salt(), validParams)
        assertEquals("bytes seen", "61626320", spy.seenPasswordCopy.toHex())
    }

    /** A failure means the password bytes or the tag stay in memory, or the keys are views of the tag. */
    @Test
    fun `password bytes and tag are zeroed and the keys are independent copies`() {
        val spy = SpyHash(countingTag)
        val keys = Argon2idHkdfKeyDerivation(spy).deriveKeys("a password with content", salt(), validParams)
        assertFalse("test is vacuous: the password was already zero when hashed", spy.seenPasswordCopy.isAllZero())
        assertFalse("test is vacuous: the tag was already zero when returned", spy.returnedAtCallTime.isAllZero())
        assertTrue("the tag array was not zeroed", spy.returnedReference.isAllZero())
        assertTrue("the password byte array was not zeroed", spy.seenPasswordReference.isAllZero())
        assertCountingKeys("after the intermediates were zeroed", keys)
    }

    /** A failure means the hash failure was changed or swallowed, or the password bytes stay in memory. */
    @Test
    fun `a failing hash function propagates unchanged and the password bytes are zeroed`() {
        val failure = IllegalStateException("boom")
        val thrower = ThrowingHash(failure)
        try {
            Argon2idHkdfKeyDerivation(thrower).deriveKeys("a password with content", salt(), validParams)
            fail("the failure of the hash function was swallowed")
        } catch (e: IllegalStateException) {
            assertSame("the exception was replaced", failure, e)
            assertEquals("message", "boom", e.message)
        }
        assertEquals("hash calls", 1, thrower.calls)
        assertFalse("test is vacuous: the password was already zero", thrower.seenPasswordCopy.isAllZero())
        assertTrue("the password byte array was not zeroed", thrower.seenPasswordReference.isAllZero())
    }

    /** A failure means the caller's salt array was changed. */
    @Test
    fun `the callers salt array is unchanged`() {
        val given = salt(0x30)
        Argon2idHkdfKeyDerivation(SpyHash(countingTag)).deriveKeys("pw", given, validParams)
        assertArrayEquals("the salt array was modified", salt(0x30), given)
    }

    /** A failure means wiping one result affects another call, so two derivations share key material. */
    @Test
    fun `wiping the first result does not affect a second derivation`() {
        val derivation = Argon2idHkdfKeyDerivation(SpyHash(countingTag))
        val first = derivation.deriveKeys("pw", salt(), validParams)
        assertCountingKeys("first call", first)
        first.wipe()
        assertTrue("wipe left key bytes in the first result", first.kek.copyBytes().isAllZero())
        assertTrue("wipe left verifier bytes in the first result", first.authVerifier.copyBytes().isAllZero())
        assertCountingKeys("second call after wiping the first", derivation.deriveKeys("pw", salt(), validParams))
    }

    /**
     * Runs one real Argon2id derivation of about 64 MiB. Expected values were computed
     * with two independent Argon2id libraries (libsodium and the reference Argon2
     * library) and an independent HKDF. The password has a decomposed letter, so a
     * missing normalisation gives other keys.
     */
    @Test
    fun `full cost derivation of a decomposed passphrase gives the independent values`() {
        val keys = Argon2idHkdfKeyDerivation().deriveKeys("cafe\u0301 horse battery staple", salt(), validParams)
        assertEquals(
            "key-encryption key",
            "21ec2740d090eaedf9cbd0a2749f0de4114aa8e6ccf12f85569a1939abbcd920",
            keys.kekHex(),
        )
        assertEquals(
            "verifier",
            "bc85c89cb528396087c35f7680360caaa4261ac49cdd3b3640421017e11ea25e",
            keys.verifierHex(),
        )
        assertNotEquals("key equals verifier", keys.kekHex(), keys.verifierHex())
    }

    /**
     * Runs one real Argon2id derivation of about 64 MiB, with 2 lanes and 4 passes.
     * Expected values were computed with the reference Argon2 library and an
     * independent HKDF.
     */
    @Test
    fun `full cost derivation with two lanes gives the independent values`() {
        val keys = Argon2idHkdfKeyDerivation()
            .deriveKeys("a second passphrase for the high vector", salt(0x10), KdfParams(65544, 4, 2, 32, 1))
        assertEquals(
            "key-encryption key",
            "0fbee4ea721e5d28362f5bc7a9ea3fef04da4728effa8db8db28bcbdba930736",
            keys.kekHex(),
        )
        assertEquals(
            "verifier",
            "fdfb1b430d30152f31ea8d1fe13ebace91d443e67a1df81f2f0140fac8a13480",
            keys.verifierHex(),
        )
    }
}
