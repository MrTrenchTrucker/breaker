package dev.breaker.dictation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [FileCredentialRefHolder] that target the two branches the
 * composition-root test does not reach: a ref file that is present but blank
 * (an empty or whitespace-only file, as a truncated write leaves one), and a
 * blank reference handed to [FileCredentialRefHolder.setActiveRef] that must
 * clear the store.
 */
class FileCredentialRefHolderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a_blank_or_empty_ref_file_reads_back_null`() {
        // A crash after the write can leave the file present but empty, or
        // whitespace-only. Either way activeRef() must read back as no
        // reference, not as the blank line.
        val file = File(tmp.root, "credential-ref")

        file.writeText("")
        assertNull(
            "an empty ref file must read back as no reference",
            FileCredentialRefHolder(file).activeRef(),
        )

        file.writeText("   \n")
        assertNull(
            "a whitespace-only ref file must read back as no reference",
            FileCredentialRefHolder(file).activeRef(),
        )
    }

    @Test
    fun `a_blank_ref_clears_the_file`() {
        // A blank reference is a clear, exactly like null: it must delete the
        // file, not store the blank line.
        val file = File(tmp.root, "credential-ref")
        val holder = FileCredentialRefHolder(file)
        holder.setActiveRef("ref-old")
        assertTrue("the ref should be written before the blank clear", file.exists())

        holder.setActiveRef("   ")
        assertFalse(
            "a blank ref must clear the file (delete it)",
            file.exists(),
        )
        assertNull(
            "a blank ref must leave no reference",
            holder.activeRef(),
        )
    }
}
