package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The name and format rules that the other extractor tests meet only at the
 * same reason: each test here builds the one archive in which a rule applies
 * and its neighbour does not, so the reason or the result tells them apart.
 */
class ModelExtractorNamesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = TarFixtures.contentOf("payload", 24)

    private fun refused(entries: List<TarEntrySpec>, reason: ExtractionReason, label: String) =
        ExtractorRig(tmp.newFolder(), bz(entries)).expectRejected(reason, label)

    private fun extracted(rig: ExtractorRig, label: String): ExtractionOutcome.Extracted {
        val outcome = rig.run()
        assertTrue("$label: expected Extracted but got $outcome", outcome is ExtractionOutcome.Extracted)
        return outcome as ExtractionOutcome.Extracted
    }

    private fun paddedTo(record: Int, raw: ByteArray): ByteArray = raw + ByteArray((record - raw.size % record) % record)

    // ---- names ----

    @Test
    fun `the path length rule counts the leading dot-slash that the header holds`() {
        val longest = TarFixtures.TINY_FILES.maxOf { "top/$it".length }
        val limits = limitsOf(maxPathLength = longest)
        val plain = topWith()
        extracted(ExtractorRig(tmp.newFolder(), bz(plain), limits = limits), "names of exactly the limit")
        val dotted = plain.map { TarEntrySpec("./" + it.name, it.type, it.content) }
        ExtractorRig(tmp.newFolder(), bz(dotted), limits = limits).expectRejected(ExtractionReason.PATH_TOO_LONG, "two characters over the limit")
    }

    @Test
    fun `a top directory that differs only by case is outside the top`() {
        refused(topWith(extra = listOf(TarFixtures.file("Top/x.bin", bytes))), ExtractionReason.OUTSIDE_TOP, "file below a top that differs by case")
        refused(topWith(extra = listOf(TarFixtures.dir("Top/"))), ExtractionReason.OUTSIDE_TOP, "directory that differs by case")
    }

    @Test
    fun `an archive of one bare file is refused as outside the top`() {
        refused(listOf(TarFixtures.file("tokens.txt", bytes)), ExtractionReason.OUTSIDE_TOP, "only a bare file")
    }

    @Test
    fun `two files with the same name in different directories both extract`() {
        val twins = listOf(
            TarFixtures.dir("top/a/"),
            TarFixtures.file("top/a/data.bin", bytes),
            TarFixtures.dir("top/b/"),
            TarFixtures.file("top/b/data.bin", bytes),
        )
        val rig = ExtractorRig(tmp.newFolder(), bz(topWith(extra = twins)))
        val done = extracted(rig, "same base name twice")
        assertEquals("only the four profile files are written", TarFixtures.TINY_FILES.sorted(), rig.target.list()!!.sorted())
        assertEquals(4L * 40L, done.bytes)
    }

    @Test
    fun `a file below a directory named like a profile file is not written as that file`() {
        val decoy = TarFixtures.file("top/tokens.txt/inner.bin", TarFixtures.contentOf("decoy", 33))
        refused(topWith(files = TarFixtures.TINY_FILES - "tokens.txt", extra = listOf(decoy)), ExtractionReason.MISSING_FILE, "only the decoy")
        val rig = ExtractorRig(tmp.newFolder(), bz(topWith(extra = listOf(decoy))))
        val done = extracted(rig, "real file and decoy")
        assertEquals(4L * 40L, done.bytes)
        assertArrayEquals("tokens.txt is the real one", TarFixtures.contentOf("tokens.txt", 40), File(rig.target, "tokens.txt").readBytes())
    }

    @Test
    fun `a profile file name in capitals is not the profile file`() {
        val capitals = TarFixtures.file("top/TOKENS.TXT", bytes)
        refused(topWith(files = TarFixtures.TINY_FILES - "tokens.txt", extra = listOf(capitals)), ExtractionReason.MISSING_FILE, "only TOKENS.TXT")
    }

    // ---- the end of the compressed stream ----

    @Test
    fun `zero bytes after the end of the bzip2 stream are refused`() {
        val rig = ExtractorRig(tmp.newFolder(), bz(topWith()) + ByteArray(512))
        rig.expectRejected(ExtractionReason.STREAM_ERROR, "zero bytes after the end")
    }

    @Test
    fun `a second bzip2 stream after the first is refused`() {
        val twice = bz(topWith()) + TarFixtures.bz2(ByteArray(1024))
        ExtractorRig(tmp.newFolder(), twice).expectRejected(ExtractionReason.STREAM_ERROR, "a second valid stream")
    }

    @Test
    fun `a tar padded to the record size with zero bytes extracts`() {
        val raw = TarFixtures.archiveOf(topWith())
        for (record in listOf(10240, 20480)) {
            val padded = paddedTo(record, raw)
            assertTrue("fixture: padding was added for $record", padded.size > raw.size && padded.size % record == 0)
            val rig = ExtractorRig(tmp.newFolder(), TarFixtures.bz2(padded))
            val done = extracted(rig, "record $record")
            assertEquals("record $record: names", TarFixtures.TINY_FILES.sorted(), rig.target.list()!!.sorted())
            assertEquals("record $record: bytes", 4L * 40L, done.bytes)
        }
    }

    @Test
    fun `a padded tar whose bzip2 end marker is missing is refused as truncated`() {
        val padded = paddedTo(10240, TarFixtures.archiveOf(topWith()))
        val cut = TarFixtures.withoutTrailer(TarFixtures.bz2(padded))
        ExtractorRig(tmp.newFolder(), cut).expectRejected(ExtractionReason.TRUNCATED, "padded tar without the end marker")
    }
}
