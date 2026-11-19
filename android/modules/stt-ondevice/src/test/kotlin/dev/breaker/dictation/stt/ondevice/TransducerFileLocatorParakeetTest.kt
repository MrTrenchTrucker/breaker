package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The locator on a listing shaped like the Parakeet archive: int8 files only, no plain decoder or
 * joiner, extra files beside them. Made-up file contents; no real model file is used.
 * Every mutant named here is hand traced, not run.
 */
class TransducerFileLocatorParakeetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val listing = listOf(
        "tokens.txt", "README.md", "joiner.int8.onnx", "decoder.int8.onnx", "encoder.int8.onnx", "test_wavs",
    )

    @Test
    fun `an int8 only listing gives the four int8 names through the fallback in any order`() {
        // Killer of a locator that needs a plain decoder or joiner (choose returns null) or one that ignores int8 for them.
        for (names in listOf(listing, listing.reversed(), listing.sorted())) {
            val choice = TransducerFileLocator.choose(names)
            assertNotNull("no choice for $names", choice)
            assertEquals("encoder.int8.onnx", choice!!.encoder)
            assertEquals("decoder falls back to int8", "decoder.int8.onnx", choice.decoder)
            assertEquals("joiner falls back to int8", "joiner.int8.onnx", choice.joiner)
            assertEquals("tokens.txt", choice.tokens)
        }
    }

    @Test
    fun `locate on a directory of that shape returns the four files`() {
        val dir = tmp.newFolder()
        for (name in listOf("tokens.txt", "README.md", "joiner.int8.onnx", "decoder.int8.onnx", "encoder.int8.onnx")) File(dir, name).writeText("x")
        File(dir, "test_wavs").mkdir()
        val files = TransducerFileLocator.locate(dir)
        assertNotNull(files)
        assertEquals(File(dir, "encoder.int8.onnx"), files!!.encoder)
        assertEquals(File(dir, "decoder.int8.onnx"), files.decoder)
        assertEquals(File(dir, "joiner.int8.onnx"), files.joiner)
        assertEquals(File(dir, "tokens.txt"), files.tokens)
    }

    @Test
    fun `without the token table there is no choice`() {
        assertNull(TransducerFileLocator.choose(listing.filter { it != "tokens.txt" }))
    }
}
