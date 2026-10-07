package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Edge cases of [ModelIntegrity]: a file that looks fine to the earlier checks
 * but cannot be read, the letter case of the digest and of the pin in the
 * result, the technical text of two refusals, and closing of the stream.
 */
class ModelIntegrityEdgeCasesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A path that does not exist on disk but answers the early checks as given:
     * it exists, it is a regular file, it has 4 bytes, and it is readable or not
     * as [readable] says. Opening it fails, as a file that cannot be read would.
     */
    private class PhantomFile(path: String, private val readable: Boolean) : File(path) {
        override fun exists(): Boolean = true
        override fun isFile(): Boolean = true
        override fun canRead(): Boolean = readable
        override fun length(): Long = 4L
    }

    private class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closeCalls = 0
        override fun close() {
            closeCalls++
            super.close()
        }
    }

    private class FailingStream : InputStream() {
        var closeCalls = 0
        override fun read(): Int = throw IOException("simulated read failure")
        override fun close() {
            closeCalls++
        }
    }

    private fun phantomFile(readable: Boolean): PhantomFile =
        PhantomFile(File(tmp.root, "phantom.bin").path, readable)

    private fun refused(verdict: ModelIntegrity.Verdict): ModelIntegrity.Verdict.Refused {
        assertTrue("expected a refusal but got $verdict", verdict is ModelIntegrity.Verdict.Refused)
        return verdict as ModelIntegrity.Verdict.Refused
    }

    // --- a file that cannot be read ---

    @Test
    fun `verify reports DIGEST_UNREADABLE when a file that passes the early checks cannot be opened`() {
        val content = "data".toByteArray()
        val digest = Fixtures.independentSha256(content)
        val checksums = Fixtures.checksumsOf("model" to digest)

        // Control: a real file with the same size and digest is verified.
        val real = Fixtures.writeBytes(File(tmp.root, "real.bin"), content)
        assertEquals(digest, (ModelIntegrity.verify(real, digest, checksums) as ModelIntegrity.Verdict.Verified).digest)

        val phantom = phantomFile(readable = true)
        assertTrue("fixture: the phantom path must not exist on disk", !File(tmp.root, "phantom.bin").exists())
        val result = refused(ModelIntegrity.verify(phantom, digest, checksums))
        assertEquals(ModelIntegrity.Refusal.DIGEST_UNREADABLE, result.refusal)
        assertEquals("could not compute digest for: ${phantom.absolutePath}", result.detail)
    }

    @Test
    fun `verify refuses a file that cannot be read before it looks at the pin`() {
        val digest = Fixtures.independentSha256("data".toByteArray())
        val checksums = Fixtures.checksumsOf("model" to digest)

        // Control: the same phantom marked readable gets past this check.
        val readable = refused(ModelIntegrity.verify(phantomFile(readable = true), digest, checksums))
        assertEquals(ModelIntegrity.Refusal.DIGEST_UNREADABLE, readable.refusal)

        val unreadable = phantomFile(readable = false)
        val withValidPin = refused(ModelIntegrity.verify(unreadable, digest, checksums))
        assertEquals(ModelIntegrity.Refusal.NOT_A_READABLE_FILE, withValidPin.refusal)
        assertEquals("not a readable file: ${unreadable.absolutePath}", withValidPin.detail)

        val withBadPin = refused(ModelIntegrity.verify(unreadable, "badpin", checksums))
        assertEquals(ModelIntegrity.Refusal.NOT_A_READABLE_FILE, withBadPin.refusal)
    }

    // --- letter case in the result ---

    @Test
    fun `verify returns the computed lowercase digest when the pin is upper case`() {
        val content = "case test bytes".toByteArray()
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), content)
        val digest = Fixtures.independentSha256(content)
        val checksums = Fixtures.checksumsOf("model" to digest)

        // Control: a lower-case pin gives the same digest text.
        val lowerPin = ModelIntegrity.verify(file, digest, checksums)
        assertEquals(digest, (lowerPin as ModelIntegrity.Verdict.Verified).digest)

        val upperPin = ModelIntegrity.verify(file, digest.uppercase(), checksums)
        assertTrue("an upper-case pin that matches must verify: $upperPin", upperPin is ModelIntegrity.Verdict.Verified)
        assertEquals(digest, (upperPin as ModelIntegrity.Verdict.Verified).digest)
    }

    // --- technical text of two refusals ---

    @Test
    fun `verify names the lowercase pin when it is not in the upstream list`() {
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), "data".toByteArray())
        val checksums = Fixtures.checksumsOf("model" to Fixtures.OTHER_PIN)
        val result = refused(ModelIntegrity.verify(file, Fixtures.VALID_PIN.uppercase(), checksums))
        assertEquals(ModelIntegrity.Refusal.PIN_NOT_IN_UPSTREAM, result.refusal)
        assertEquals("pin ${Fixtures.VALID_PIN} not found in upstream checksums", result.detail)
    }

    @Test
    fun `verify names the lowercase pin and the computed digest on a mismatch`() {
        val content = "actual content".toByteArray()
        val file = Fixtures.writeBytes(File(tmp.root, "model.bin"), content)
        val checksums = Fixtures.checksumsOf("model" to Fixtures.VALID_PIN)
        val result = refused(ModelIntegrity.verify(file, Fixtures.VALID_PIN.uppercase(), checksums))
        assertEquals(ModelIntegrity.Refusal.PIN_MISMATCH, result.refusal)
        assertEquals(
            "expected ${Fixtures.VALID_PIN}, found ${Fixtures.independentSha256(content)}",
            result.detail,
        )
    }

    // --- closing the stream ---

    @Test
    fun `sha256 of a stream closes the stream after reading it`() {
        val content = "stream content".toByteArray()
        val stream = TrackingStream(content)
        assertEquals(Fixtures.independentSha256(content), ModelIntegrity.sha256(stream))
        assertTrue("the stream must be closed once it is read", stream.closeCalls >= 1)
    }

    @Test
    fun `sha256 of a stream closes the stream when reading fails`() {
        val stream = FailingStream()
        try {
            ModelIntegrity.sha256(stream)
            fail("Expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertTrue("the stream must be closed after a failed read", stream.closeCalls >= 1)
    }
}
