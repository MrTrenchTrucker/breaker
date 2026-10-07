package dev.breaker.dictation.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The raw Argon2id primitive against the published Argon2id test vector in
 * RFC 9106 section 5.3: version 0x13, 32 KiB of memory, 3 passes, 4 lanes, a
 * 32 byte tag; password 32 bytes of 0x01, salt 16 bytes of 0x02, secret 8 bytes
 * of 0x03 and associated data 12 bytes of 0x04.
 *
 * The remaining tests start from those same inputs and change one thing at a
 * time, so a primitive that quietly ignores an input is named by the test that
 * fails. Every case uses at most 64 KiB of memory.
 */
class Argon2idRfc9106Test {

    private val rfcPassword = ByteArray(32) { 0x01 }
    private val rfcSalt = ByteArray(16) { 0x02 }
    private val rfcSecret = ByteArray(8) { 0x03 }
    private val rfcAssociatedData = ByteArray(12) { 0x04 }

    /** One call with the RFC inputs, any one of which a test may replace. */
    private fun rfcRun(
        password: ByteArray = rfcPassword,
        salt: ByteArray = rfcSalt,
        secret: ByteArray = rfcSecret,
        associatedData: ByteArray = rfcAssociatedData,
        memoryKib: Int = 32,
        iterations: Int = 3,
        parallelism: Int = 4,
        outputLength: Int = 32,
    ): ByteArray = argon2id(
        password, salt, memoryKib, iterations, parallelism, outputLength, secret, associatedData,
    )

    private fun ByteArray.withLastByteFlipped(): ByteArray =
        copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }

    private fun assertChangesOutput(what: String, actual: ByteArray) {
        assertEquals("output length when $what changed", 32, actual.size)
        assertNotEquals(
            "the output still equals the RFC tag after $what changed, so $what is ignored",
            RFC_TAG,
            actual.toHex(),
        )
    }

    /**
     * A failure means the result is not Argon2id version 0x13 for these inputs:
     * the variant or version is wrong, a parameter is in the wrong position or
     * unit, or the secret or the associated data is dropped.
     */
    @Test
    fun `RFC 9106 section 5-3 vector gives the published tag`() {
        assertEquals("tag for the RFC 9106 section 5-3 inputs", RFC_TAG, rfcRun().toHex())
    }

    /** A failure means one password byte does not reach the result. */
    @Test
    fun `one changed password byte changes the output`() {
        assertChangesOutput("one password byte", rfcRun(password = rfcPassword.withLastByteFlipped()))
    }

    /** A failure means one salt byte does not reach the result. */
    @Test
    fun `one changed salt byte changes the output`() {
        assertChangesOutput("one salt byte", rfcRun(salt = rfcSalt.withLastByteFlipped()))
    }

    /** A failure means one secret byte does not reach the result. */
    @Test
    fun `one changed secret byte changes the output`() {
        assertChangesOutput("one secret byte", rfcRun(secret = rfcSecret.withLastByteFlipped()))
    }

    /** A failure means one associated data byte does not reach the result. */
    @Test
    fun `one changed associated data byte changes the output`() {
        assertChangesOutput(
            "one associated data byte",
            rfcRun(associatedData = rfcAssociatedData.withLastByteFlipped()),
        )
    }

    /** A failure means the memory size argument is ignored. */
    @Test
    fun `memory of 64 KiB instead of 32 KiB changes the output`() {
        assertChangesOutput("the memory size", rfcRun(memoryKib = 64))
    }

    /** A failure means the pass count argument is ignored. */
    @Test
    fun `2 passes instead of 3 changes the output`() {
        assertChangesOutput("the pass count", rfcRun(iterations = 2))
    }

    /** A failure means the lane count argument is ignored. */
    @Test
    fun `2 lanes instead of 4 changes the output`() {
        assertChangesOutput("the lane count", rfcRun(parallelism = 2))
    }

    /** A failure means the requested output length is not honoured. */
    @Test
    fun `output length of 16 and of 64 bytes gives exactly that many bytes`() {
        assertEquals("size of a 16 byte request", 16, rfcRun(outputLength = 16).size)
        assertEquals("size of a 64 byte request", 64, rfcRun(outputLength = 64).size)
    }

    /**
     * A failure means leaving out the secret and the associated data is not the
     * same as passing an empty array for each.
     */
    @Test
    fun `omitting secret and associated data equals passing empty arrays`() {
        val password = "a password for the defaults".toByteArray()
        val salt = "sixteen byte salt".toByteArray()

        val omitted = argon2id(password, salt, 64, 1, 1, 32)
        val explicit = argon2id(password, salt, 64, 1, 1, 32, ByteArray(0), ByteArray(0))

        assertEquals("omitted versus empty secret and associated data", explicit.toHex(), omitted.toHex())
    }

    /** A failure means the primitive modified an array that the caller owns. */
    @Test
    fun `the four input arrays are unchanged after a call`() {
        val password = rfcPassword.copyOf()
        val salt = rfcSalt.copyOf()
        val secret = rfcSecret.copyOf()
        val associatedData = rfcAssociatedData.copyOf()

        rfcRun(password = password, salt = salt, secret = secret, associatedData = associatedData)

        assertEquals("password after the call", rfcPassword.toHex(), password.toHex())
        assertEquals("salt after the call", rfcSalt.toHex(), salt.toHex())
        assertEquals("secret after the call", rfcSecret.toHex(), secret.toHex())
        assertEquals("associated data after the call", rfcAssociatedData.toHex(), associatedData.toHex())
    }

    private companion object {
        /** The Tag at the end of RFC 9106 section 5.3. */
        const val RFC_TAG = "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"
    }
}
