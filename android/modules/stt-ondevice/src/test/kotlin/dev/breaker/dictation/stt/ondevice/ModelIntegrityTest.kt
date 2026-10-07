package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- sha256(bytes) known-answer vectors ---

    @Test
    fun `sha256 of empty byte array matches published known-answer vector`() {
        assertEquals(Fixtures.EMPTY_SHA256, ModelIntegrity.sha256(ByteArray(0)))
    }

    @Test
    fun `sha256 of abc matches published known-answer vector`() {
        assertEquals(Fixtures.ABC_SHA256, ModelIntegrity.sha256("abc".toByteArray(Charsets.US_ASCII)))
    }

    // --- sha256(file) ---

    @Test
    fun `sha256 of file returns null for non-existent file`() {
        val file = File(tmp.root, "nonexistent.bin")
        assertNull(ModelIntegrity.sha256(file))
    }

    @Test
    fun `sha256 of file returns correct digest for small file`() {
        val content = "hello world".toByteArray()
        val file = Fixtures.writeBytes(File(tmp.root, "small.bin"), content)
        val expected = Fixtures.independentSha256(content)
        assertEquals(expected, ModelIntegrity.sha256(file))
    }

    // --- sha256(stream) ---

    @Test
    fun `sha256 of stream returns correct digest`() {
        val content = "stream content".toByteArray()
        val expected = Fixtures.independentSha256(content)
        val stream = content.inputStream()
        assertEquals(expected, ModelIntegrity.sha256(stream))
    }

    // --- verify: FILE_MISSING ---

    @Test
    fun `verify returns FILE_MISSING when file does not exist`() {
        val file = File(tmp.root, "missing.bin")
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val verdict = ModelIntegrity.verify(file, Fixtures.VALID_PIN, checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Refused)
        assertEquals(ModelIntegrity.Refusal.FILE_MISSING, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    // --- verify: NOT_A_READABLE_FILE ---

    @Test
    fun `verify returns NOT_A_READABLE_FILE when path is a directory`() {
        val dir = tmp.newFolder("adir")
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val verdict = ModelIntegrity.verify(dir, Fixtures.VALID_PIN, checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Refused)
        assertEquals(ModelIntegrity.Refusal.NOT_A_READABLE_FILE, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    // --- verify: FILE_EMPTY ---

    @Test
    fun `verify returns FILE_EMPTY when file is zero bytes`() {
        val file = Fixtures.writeBytes(File(tmp.root, "empty.bin"), ByteArray(0))
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val verdict = ModelIntegrity.verify(file, Fixtures.VALID_PIN, checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Refused)
        assertEquals(ModelIntegrity.Refusal.FILE_EMPTY, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    // --- verify: PIN_MALFORMED ---

    @Test
    fun `verify returns PIN_MALFORMED when pin is too short`() {
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), "data".toByteArray())
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val verdict = ModelIntegrity.verify(file, "tooshort", checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Refused)
        assertEquals(ModelIntegrity.Refusal.PIN_MALFORMED, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    @Test
    fun `verify returns PIN_MALFORMED when pin has non-hex characters`() {
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), "data".toByteArray())
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val badPin = "g1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
        val verdict = ModelIntegrity.verify(file, badPin, checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Refused)
        assertEquals(ModelIntegrity.Refusal.PIN_MALFORMED, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    @Test
    fun `verify returns PIN_MALFORMED when pin is empty`() {
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), "data".toByteArray())
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val verdict = ModelIntegrity.verify(file, "", checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Refused)
        assertEquals(ModelIntegrity.Refusal.PIN_MALFORMED, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    // --- verify: PIN_NOT_IN_UPSTREAM ---

    @Test
    fun `verify returns PIN_NOT_IN_UPSTREAM when pin is not in checksums`() {
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), "data".toByteArray())
        val checksums = Fixtures.checksumsOf("model" to Fixtures.OTHER_PIN)
        val verdict = ModelIntegrity.verify(file, Fixtures.VALID_PIN, checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Refused)
        assertEquals(ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    // --- verify: PIN_MISMATCH ---

    @Test
    fun `verify returns PIN_MISMATCH when computed digest differs from pin`() {
        val content = "actual content".toByteArray()
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), content)
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val verdict = ModelIntegrity.verify(file, Fixtures.VALID_PIN, checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Refused)
        assertEquals(ModelIntegrity.Refusal.PIN_MISMATCH, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    // --- verify: Verified ---

    @Test
    fun `verify returns Verified when digest matches pin`() {
        val content = "trusted model".toByteArray()
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), content)
        val digest = Fixtures.independentSha256(content)
        val checksums = Fixtures.checksumsOf("model" to digest)
        val verdict = ModelIntegrity.verify(file, digest, checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Verified)
        assertEquals(digest, (verdict as ModelIntegrity.Verdict.Verified).digest)
    }

    @Test
    fun `verify returns Verified when pin is uppercase but digest matches`() {
        val content = "case test".toByteArray()
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), content)
        val digest = Fixtures.independentSha256(content)
        val checksums = Fixtures.checksumsOf("model" to digest)
        val verdict = ModelIntegrity.verify(file, digest.uppercase(), checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Verified)
    }

    // --- verify: order of checks ---

    @Test
    fun `verify checks FILE_MISSING before NOT_A_READABLE_FILE`() {
        val file = File(tmp.root, "nonexistent")
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val verdict = ModelIntegrity.verify(file, Fixtures.VALID_PIN, checksums)
        assertEquals(ModelIntegrity.Refusal.FILE_MISSING, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    @Test
    fun `verify checks NOT_A_READABLE_FILE before FILE_EMPTY`() {
        val dir = tmp.newFolder("adir")
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val verdict = ModelIntegrity.verify(dir, Fixtures.VALID_PIN, checksums)
        assertEquals(ModelIntegrity.Refusal.NOT_A_READABLE_FILE, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    @Test
    fun `verify checks FILE_EMPTY before PIN_MALFORMED`() {
        val file = Fixtures.writeBytes(File(tmp.root, "empty.bin"), ByteArray(0))
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val verdict = ModelIntegrity.verify(file, "badpin", checksums)
        assertEquals(ModelIntegrity.Refusal.FILE_EMPTY, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    @Test
    fun `verify checks PIN_MALFORMED before PIN_NOT_IN_UPSTREAM`() {
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), "data".toByteArray())
        val checksums = Fixtures.checksumsOf("model" to Fixtures.OTHER_PIN)
        val verdict = ModelIntegrity.verify(file, "badpin", checksums)
        assertEquals(ModelIntegrity.Refusal.PIN_MALFORMED, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    @Test
    fun `verify checks PIN_NOT_IN_UPSTREAM before PIN_MISMATCH`() {
        val content = "data".toByteArray()
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), content)
        val checksums = Fixtures.checksumsOf("model" to Fixtures.OTHER_PIN)
        val verdict = ModelIntegrity.verify(file, Fixtures.VALID_PIN, checksums)
        assertEquals(ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    // --- large file independent digest test ---

    @Test
    fun `sha256 of file larger than four buffers matches independent digest`() {
        val size = 4L * ModelIntegrity.BUFFER_BYTES + 1L
        val file = Fixtures.createFileOfSize(File(tmp.root, "large.bin"), size)
        val bytes = file.readBytes()
        val expected = Fixtures.independentSha256(bytes)
        val actual = ModelIntegrity.sha256(file)
        assertNotNull(actual)
        assertEquals(expected, actual)
    }

    // --- isVerified property ---

    @Test
    fun `isVerified is true for Verified verdict`() {
        val content = "test".toByteArray()
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), content)
        val digest = Fixtures.independentSha256(content)
        val checksums = Fixtures.checksumsOf("model" to digest)
        val verdict = ModelIntegrity.verify(file, digest, checksums)
        assertTrue(verdict.isVerified)
    }

    @Test
    fun `isVerified is false for Refused verdict`() {
        val file = File(tmp.root, "missing.bin")
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val verdict = ModelIntegrity.verify(file, Fixtures.VALID_PIN, checksums)
        assertFalse(verdict.isVerified)
    }

    // --- An empty file is refused as FILE_EMPTY, not as a digest mismatch ---

    @Test
    fun `verify returns FILE_EMPTY for empty file even when pin matches empty digest`() {
        val file = Fixtures.writeBytes(File(tmp.root, "empty.bin"), ByteArray(0))
        val checksums = Fixtures.checksumsOf("model" to Fixtures.EMPTY_SHA256)
        val verdict = ModelIntegrity.verify(file, Fixtures.EMPTY_SHA256, checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Refused)
        assertEquals(ModelIntegrity.Refusal.FILE_EMPTY, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }

    @Test
    fun `verify returns PIN_NOT_IN_UPSTREAM when pin matches file digest but is absent from checksums`() {
        val content = "trusted model bytes".toByteArray()
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), content)
        val digest = Fixtures.independentSha256(content)
        val checksums = Fixtures.checksumsOf("model" to Fixtures.OTHER_PIN)
        val verdict = ModelIntegrity.verify(file, digest, checksums)
        assertTrue(verdict is ModelIntegrity.Verdict.Refused)
        assertEquals(ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM, (verdict as ModelIntegrity.Verdict.Refused).refusal)
    }
}
