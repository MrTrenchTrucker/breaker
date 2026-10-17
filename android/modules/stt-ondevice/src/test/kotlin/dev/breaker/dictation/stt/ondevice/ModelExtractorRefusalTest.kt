package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * One test per way an archive can be refused: each leaves no target, no work
 * directory, nothing in the staging directory and the archive file unchanged,
 * and each closes every stream it opened.
 */
class ModelExtractorRefusalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = TarFixtures.contentOf("payload", 24)

    private fun refused(entries: List<TarEntrySpec>, reason: ExtractionReason, label: String) =
        ExtractorRig(tmp.newFolder(), bz(entries)).expectRejected(reason, label)

    private fun refusedAsArchive(archive: ByteArray, label: String) {
        val rig = ExtractorRig(tmp.newFolder(), archive)
        val outcome = rig.run()
        assertTrue("$label: expected Rejected but got $outcome", outcome is ExtractionOutcome.Rejected)
        assertEquals("$label: fault", ExtractionFault.ARCHIVE, (outcome as ExtractionOutcome.Rejected).reason.fault)
        rig.assertNothingLeft(label)
    }

    // ---- names ----

    @Test
    fun `an entry with an absolute path is refused and leaves nothing`() {
        refused(topWith(extra = listOf(TarFixtures.file("/tmp/evil.bin", bytes))), ExtractionReason.ABSOLUTE_PATH, "after the good files")
        refused(listOf(TarFixtures.file("/top/tokens.txt", bytes)) + topWith(), ExtractionReason.ABSOLUTE_PATH, "as the first entry")
    }

    @Test
    fun `an entry with a parent segment is refused wherever the segment sits`() {
        val names = listOf("top/../evil.bin", "top/sub/../../evil.bin", "../top/evil.bin", "top/evil/..")
        for (name in names) {
            refused(topWith(extra = listOf(TarFixtures.file(name, bytes))), ExtractionReason.PARENT_SEGMENT, name)
        }
    }

    @Test
    fun `an entry with a control character a dot segment or an empty segment is refused`() {
        for (name in listOf("top/a\u0001b.bin", "top/./x.bin", "top//x.bin")) {
            refused(topWith(extra = listOf(TarFixtures.file(name, bytes))), ExtractionReason.BAD_NAME, name.replace('\u0001', '?'))
        }
    }

    @Test
    fun `a refusal detail never repeats the entry name`() {
        val marker = "marker-zq-77"
        val rejected = refused(topWith(extra = listOf(TarFixtures.file("top/../$marker", bytes))), ExtractionReason.PARENT_SEGMENT, "name in detail")
        assertFalse("the detail must not hold the entry name: ${rejected.detail}", rejected.detail.contains(marker))
    }

    // ---- types ----

    @Test
    fun `an entry that is a symbolic link is refused even when its name looks like a file`() {
        val withoutTokens = TarFixtures.TINY_FILES - "tokens.txt"
        refused(topWith(files = withoutTokens, extra = listOf(TarFixtures.symlink("top/tokens.txt", "../../etc/x"))), ExtractionReason.SYMLINK, "link named as a profile file")
        refused(topWith(extra = listOf(TarFixtures.symlink("top/skipped-link", "../../etc/x"))), ExtractionReason.SYMLINK, "link that would be skipped")
    }

    @Test
    fun `an entry that is a hard link is refused`() {
        val withoutTokens = TarFixtures.TINY_FILES - "tokens.txt"
        refused(topWith(files = withoutTokens, extra = listOf(TarFixtures.hardlink("top/tokens.txt", "top/encoder-epoch-99-avg-1.int8.onnx"))), ExtractionReason.HARDLINK, "link named as a profile file")
        refused(topWith(extra = listOf(TarFixtures.hardlink("top/skipped-link", "top/tokens.txt"))), ExtractionReason.HARDLINK, "link that would be skipped")
    }

    @Test
    fun `an entry that is a device a fifo or a sparse file is refused`() {
        val types = listOf(
            "character device" to TarFixtures.TYPE_CHAR_DEVICE,
            "block device" to TarFixtures.TYPE_BLOCK_DEVICE,
            "fifo" to TarFixtures.TYPE_FIFO,
            "sparse file" to TarFixtures.TYPE_SPARSE,
            "unknown type" to TarFixtures.TYPE_UNKNOWN,
        )
        for ((label, type) in types) {
            refused(topWith(extra = listOf(TarFixtures.special("top/odd", type))), ExtractionReason.NOT_REGULAR, label)
        }
    }

    // ---- names that collide or lie outside ----

    @Test
    fun `two entries with the same name after normalisation are refused`() {
        val again = TarFixtures.file("top/tokens.txt", bytes)
        refused(topWith(extra = listOf(again)), ExtractionReason.DUPLICATE_NAME, "same name twice")
        refused(topWith(extra = listOf(TarFixtures.file("top/Tokens.txt", bytes))), ExtractionReason.DUPLICATE_NAME, "differs only by case")
        refused(topWith(extra = listOf(TarFixtures.file("./top/tokens.txt", bytes))), ExtractionReason.DUPLICATE_NAME, "differs only by a leading dot-slash")
        refused(topWith(extra = listOf(TarFixtures.dir("top"))), ExtractionReason.DUPLICATE_NAME, "directory with and without its slash")
    }

    @Test
    fun `an entry outside the top directory is refused`() {
        refused(topWith(extra = listOf(TarFixtures.file("other/x.bin", bytes))), ExtractionReason.OUTSIDE_TOP, "second top directory")
        refused(topWith(extra = listOf(TarFixtures.dir("other/"))), ExtractionReason.OUTSIDE_TOP, "second top directory entry")
        refused(topWith(extra = listOf(TarFixtures.file("stray.txt", bytes))), ExtractionReason.OUTSIDE_TOP, "file with no directory after the top")
    }

    @Test
    fun `a file with no directory is refused`() {
        refused(listOf(TarFixtures.file("tokens.txt", bytes)) + topWith(), ExtractionReason.OUTSIDE_TOP, "first entry is a bare file")
    }

    // ---- stream trouble ----

    @Test
    fun `bytes that are not a bzip2 stream are refused`() {
        ExtractorRig(tmp.newFolder(), TarFixtures.notBzip2()).expectRejected(ExtractionReason.STREAM_ERROR, "plain text")
        refusedAsArchive(ByteArray(0), "empty file")
    }

    @Test
    fun `a corrupt bzip2 block is refused`() {
        val good = bz(topWith())
        ExtractorRig(tmp.newFolder(), TarFixtures.withByteFlipped(good, 10)).expectRejected(ExtractionReason.STREAM_ERROR, "damaged block checksum")
        refusedAsArchive(TarFixtures.withByteFlipped(good), "byte 60 flipped")
    }

    @Test
    fun `a bad tar header inside a valid bzip2 stream is refused`() {
        ExtractorRig(tmp.newFolder(), TarFixtures.badChecksumArchive()).expectRejected(ExtractionReason.STREAM_ERROR, "wrong header checksum")
        ExtractorRig(tmp.newFolder(), TarFixtures.badSizeFieldArchive()).expectRejected(ExtractionReason.STREAM_ERROR, "letters in the size field")
    }

    @Test
    fun `a bzip2 stream cut in the middle is refused`() {
        ExtractorRig(tmp.newFolder(), TarFixtures.cutInMiddle(bz(topWith()))).expectRejected(ExtractionReason.TRUNCATED, "cut in a block")
    }

    @Test
    fun `a bzip2 stream missing only its trailer is refused`() {
        ExtractorRig(tmp.newFolder(), TarFixtures.withoutTrailer(bz(topWith()))).expectRejected(ExtractionReason.TRUNCATED, "no end marker")
    }

    @Test
    fun `a tar cut in the middle of an entry body is refused`() {
        val entries = listOf(TarFixtures.dir("top/"), TarFixtures.file("top/tokens.txt", TarFixtures.contentOf("tokens.txt", 2000)))
        val cutTar = TarFixtures.cutAt(TarFixtures.archiveOf(entries, endBlocks = 0), 2 * TarFixtures.BLOCK + 700)
        ExtractorRig(tmp.newFolder(), TarFixtures.bz2(cutTar)).expectRejected(ExtractionReason.TRUNCATED, "body cut short")
    }

    @Test
    fun `bytes after the end of the bzip2 stream are refused`() {
        val padded = bz(topWith()) + byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        ExtractorRig(tmp.newFolder(), padded).expectRejected(ExtractionReason.STREAM_ERROR, "trailing bytes")
    }

    // ---- what is written ----

    @Test
    fun `files are only written inside the work directory under the profile names`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        assertTrue(rig.run() is ExtractionOutcome.Extracted)
        assertEquals(4, rig.outputs.size)
        assertTrue("every output sits in the work directory", rig.outputs.all { it.file.parentFile == rig.workDir })
        assertEquals(TarFixtures.TINY_FILES.sorted(), rig.outputs.map { it.file.name }.sorted())
    }
}
