package dev.breaker.dictation.training.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelVerifierTest {
    private val bytes = ByteArray(64) { it.toByte() }
    private val digest = referenceSha256(bytes)

    private fun reply(
        sha: String = digest,
        size: Long = bytes.size.toLong(),
        samples: Int = 12,
        body: ByteArray = bytes,
    ) = ModelReply(sha, size, samples, body)

    @Test
    fun `a model with the right size digest and samples passes`() {
        assertNull(ModelVerifier.check(reply()))
    }

    @Test
    fun `the hex digest of abc is the published value`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ModelVerifier.sha256Hex("abc".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `a wrong digest is refused as a digest mismatch`() {
        assertEquals(FetchRefusal.DIGEST_MISMATCH, ModelVerifier.check(reply(sha = "0".repeat(64))))
    }

    @Test
    fun `one flipped byte of the model is a digest mismatch`() {
        val flipped = bytes.copyOf()
        flipped[10] = (flipped[10].toInt() xor 1).toByte()
        assertEquals(FetchRefusal.DIGEST_MISMATCH, ModelVerifier.check(reply(body = flipped)))
    }

    @Test
    fun `truncated bytes under the declared size are a size mismatch`() {
        assertEquals(FetchRefusal.SIZE_MISMATCH, ModelVerifier.check(reply(body = bytes.copyOf(63))))
    }

    @Test
    fun `extra bytes over the declared size are a size mismatch`() {
        assertEquals(FetchRefusal.SIZE_MISMATCH, ModelVerifier.check(reply(body = bytes + byteArrayOf(1))))
    }

    @Test
    fun `a declared size above the cap is out of range`() {
        assertEquals(
            FetchRefusal.SIZE_OUT_OF_RANGE,
            ModelVerifier.check(reply(size = TrainingLimits.MAX_MODEL_BYTES + 1)),
        )
    }

    @Test
    fun `a declared size exactly at the cap is in range and only the byte count disagrees`() {
        // A full 32 MiB model is not built here; a size at the cap with the 64 real bytes must get past the range check.
        assertEquals(
            FetchRefusal.SIZE_MISMATCH,
            ModelVerifier.check(reply(size = TrainingLimits.MAX_MODEL_BYTES)),
        )
    }

    @Test
    fun `a declared size of zero is out of range`() {
        assertEquals(
            FetchRefusal.SIZE_OUT_OF_RANGE,
            ModelVerifier.check(reply(size = 0L, body = ByteArray(0))),
        )
    }

    @Test
    fun `a negative declared size is out of range`() {
        assertEquals(FetchRefusal.SIZE_OUT_OF_RANGE, ModelVerifier.check(reply(size = -1L)))
    }

    @Test
    fun `a declared size of one is in range`() {
        val one = byteArrayOf(7)
        assertNull(ModelVerifier.check(reply(sha = referenceSha256(one), size = 1L, body = one)))
    }

    @Test
    fun `an upper case digest is accepted`() {
        assertNull(ModelVerifier.check(reply(sha = digest.uppercase())))
    }

    @Test
    fun `a digest of 63 characters is a bad format`() {
        assertEquals(FetchRefusal.BAD_DIGEST_FORMAT, ModelVerifier.check(reply(sha = digest.take(63))))
    }

    @Test
    fun `a digest of 65 characters is a bad format`() {
        assertEquals(FetchRefusal.BAD_DIGEST_FORMAT, ModelVerifier.check(reply(sha = digest + "0")))
    }

    @Test
    fun `a digest of 64 characters with a non hex character is a bad format`() {
        assertEquals(
            FetchRefusal.BAD_DIGEST_FORMAT,
            ModelVerifier.check(reply(sha = "g" + digest.substring(1))),
        )
    }

    @Test
    fun `an empty digest is a bad format`() {
        assertEquals(FetchRefusal.BAD_DIGEST_FORMAT, ModelVerifier.check(reply(sha = "")))
    }

    @Test
    fun `zero samples used is refused`() {
        assertEquals(FetchRefusal.BAD_SAMPLES_COUNT, ModelVerifier.check(reply(samples = 0)))
    }

    @Test
    fun `negative samples used is refused`() {
        assertEquals(FetchRefusal.BAD_SAMPLES_COUNT, ModelVerifier.check(reply(samples = -3)))
    }

    @Test
    fun `one sample used is accepted`() {
        assertNull(ModelVerifier.check(reply(samples = 1)))
    }

    @Test
    fun `a reply that is oversize and has a wrong digest is out of range first`() {
        assertEquals(
            FetchRefusal.SIZE_OUT_OF_RANGE,
            ModelVerifier.check(reply(sha = "0".repeat(64), size = TrainingLimits.MAX_MODEL_BYTES + 1)),
        )
    }

    @Test
    fun `a reply with a wrong size and a wrong digest is a size mismatch first`() {
        assertEquals(
            FetchRefusal.SIZE_MISMATCH,
            ModelVerifier.check(reply(sha = "0".repeat(64), body = bytes.copyOf(63))),
        )
    }

    @Test
    fun `a reply with a bad digest format and zero samples is a bad format first`() {
        assertEquals(
            FetchRefusal.BAD_DIGEST_FORMAT,
            ModelVerifier.check(reply(sha = digest.take(63), samples = 0)),
        )
    }

    @Test
    fun `a reply with a wrong digest and zero samples is a digest mismatch first`() {
        assertEquals(
            FetchRefusal.DIGEST_MISMATCH,
            ModelVerifier.check(reply(sha = "0".repeat(64), samples = 0)),
        )
    }

    @Test
    fun `the normalised digest is lower case`() {
        assertEquals("abcdef", ModelVerifier.normalised("ABCdef"))
    }
}
