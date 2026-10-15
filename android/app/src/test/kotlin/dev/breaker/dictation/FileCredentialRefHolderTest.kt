package dev.breaker.dictation

import java.io.File
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    /**
     * The folder holds exactly one entry after each write — the live file, never its `<name>.tmp`.
     */
    private fun namesInFolderOf(file: File): List<String> =
        file.parentFile!!.list()!!.toList().sorted()

    // --- (a) mover refuses: old ref byte-identical, no temp left behind ---

    /**
     * A mover that fails must leave the live file's bytes unchanged and leave no `<name>.tmp`
     * in its folder — the write went to the temp, which is cleaned up before the move attempt.
     */
    @Test
    fun `a mover that refuses leaves the old ref byte-identical and no temp behind`() {
        val file = File(tmp.root, "credential-ref")
        file.writeText("old-ref\n")
        val oldBytes = file.readBytes()

        val holder =
            FileCredentialRefHolder(
                file,
                { t, r -> t.writeText(r + "\n") },
                { _, _ -> throw IOException("mover refused") },
            )

        try {
            holder.setActiveRef("new-ref")
            org.junit.Assert.fail("an IOException was expected from the mover")
        } catch (expected: IOException) {
            // expected: the move failed, so nothing should have changed on disk.
        }

        assertArrayEquals(
            "a refused move must leave the old ref byte-identical",
            oldBytes,
            file.readBytes(),
        )
        assertEquals(
            "a refused move must not leave a temp file behind",
            listOf(file.name),
            namesInFolderOf(file),
        )
    }

    // --- (b) writer fails: target never truncated, old ref byte-identical ---

    /**
     * A writer that fails must leave the live file's bytes unchanged and leave no `<name>.tmp` —
     * a partial write to the temp is cleaned up before it ever reaches the live file.
     */
    @Test
    fun `a writer that fails leaves the old ref byte-identical and no temp behind`() {
        val file = File(tmp.root, "credential-ref")
        file.writeText("old-ref\n")
        val oldBytes = file.readBytes()

        val holder =
            FileCredentialRefHolder(
                file,
                { _, _ -> throw IOException("writer failed") },
                { from, to -> Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) },
            )

        try {
            holder.setActiveRef("new-ref")
            org.junit.Assert.fail("an IOException was expected from the writer")
        } catch (expected: IOException) {
            // expected: the write failed, so nothing should have changed on disk.
        }

        assertArrayEquals(
            "a failed write must leave the old ref byte-identical",
            oldBytes,
            file.readBytes(),
        )
        assertEquals(
            "a failed write must not leave a temp file behind",
            listOf(file.name),
            namesInFolderOf(file),
        )
    }

    // --- (c) happy path + restart read-back exact ---

    /**
     * A successful write round-trips: the second holder reads back exactly what the first wrote.
     */
    @Test
    fun `a written ref is read back exactly by a fresh holder`() {
        val file = File(tmp.root, "credential-ref")

        FileCredentialRefHolder(file).apply { setActiveRef("ref-one") }

        assertEquals(
            "a fresh holder reads back the exact written ref",
            "ref-one",
            FileCredentialRefHolder(file).activeRef(),
        )
        assertEquals(
            "the live file holds exactly one trimmed line plus its newline",
            "ref-one\n",
            file.readText(),
        )
        assertEquals(
            "a successful write leaves only the live file in the folder",
            listOf(file.name),
            namesInFolderOf(file),
        )
    }

    // --- (d) stale temp is inert: overwritten, not read, not left behind ---

    /**
     * A pre-existing `<name>.tmp` next to the live file must be overwritten by the next write —
     * never read back as the active ref, and never left behind. This is the open question the fix resolves.
     */
    @Test
    fun `a stale temp next to the file is replaced not read and not left behind`() {
        val file = File(tmp.root, "credential-ref")

        FileCredentialRefHolder(file).apply { setActiveRef("old-ref") }

        // A leftover temp from an earlier crash sits beside the live file.
        val stale = File(file.parentFile, file.name + ".tmp")
        stale.writeText("stale-ref\n")

        FileCredentialRefHolder(file).apply { setActiveRef("new-ref") }

        assertEquals(
            "a fresh holder reads the live ref, never the stale temp",
            "new-ref",
            FileCredentialRefHolder(file).activeRef(),
        )
        assertFalse(
            "the stale temp must be replaced, not left behind",
            stale.exists(),
        )
        assertEquals(
            "a successful write leaves only the live file in the folder",
            listOf(file.name),
            namesInFolderOf(file),
        )
    }
}
