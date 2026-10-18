package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Each bound is probed on both sides with a small injected limit: an archive
 * that sits exactly on the bound extracts, and the same archive one step over
 * is refused with the reason of that bound. No test decompresses more than a
 * megabyte or two.
 */
class ModelExtractorBoundsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun extractsWith(entries: List<TarEntrySpec>, limits: ExtractionLimits, label: String): ExtractionOutcome.Extracted {
        val outcome = ExtractorRig(tmp.newFolder(), bz(entries), limits = limits).run()
        assertTrue("$label: expected Extracted but got $outcome", outcome is ExtractionOutcome.Extracted)
        return outcome as ExtractionOutcome.Extracted
    }

    private fun refusedWith(entries: List<TarEntrySpec>, limits: ExtractionLimits, reason: ExtractionReason, label: String) =
        ExtractorRig(tmp.newFolder(), bz(entries), limits = limits).expectRejected(reason, label)

    private fun sized(sizes: Map<String, Int>, extra: List<TarEntrySpec> = emptyList()): List<TarEntrySpec> =
        listOf(TarFixtures.dir("top/")) + TarFixtures.TINY_FILES.map {
            TarFixtures.file("top/$it", TarFixtures.contentOf(it, sizes[it] ?: 40))
        } + extra

    private fun fillers(count: Int): List<TarEntrySpec> = (1..count).map { TarFixtures.file("top/f$it", TarFixtures.contentOf("f$it", 8)) }

    @Test
    fun `an archive with exactly the maximum number of entries extracts and one more is refused`() {
        val entries = topWith(extra = fillers(3))
        assertEquals(8, entries.size)
        extractsWith(entries, limitsOf(maxEntries = 8), "eight entries, limit eight")
        refusedWith(entries, limitsOf(maxEntries = 7), ExtractionReason.TOO_MANY_ENTRIES, "eight entries, limit seven")
    }

    @Test
    fun `the entry count is checked before the entry is looked at`() {
        val entries = topWith(extra = listOf(TarFixtures.file("top/../x", ByteArray(1))))
        refusedWith(entries, limitsOf(maxEntries = 5), ExtractionReason.TOO_MANY_ENTRIES, "sixth entry is over the count and has a bad name")
        refusedWith(entries, limitsOf(maxEntries = 6), ExtractionReason.PARENT_SEGMENT, "sixth entry is within the count and has a bad name")
    }

    @Test
    fun `a long name is one entry not two`() {
        val entries = topWith(extra = listOf(TarFixtures.file("top/" + "n".repeat(106), ByteArray(4))))
        assertEquals(6, entries.size)
        extractsWith(entries, limitsOf(maxEntries = 6), "six entries, limit six")
        refusedWith(entries, limitsOf(maxEntries = 5), ExtractionReason.TOO_MANY_ENTRIES, "six entries, limit five")
    }

    @Test
    fun `a skipped entry of exactly the entry bound extracts and one byte more is refused`() {
        val limits = limitsOf(maxEntryBytes = 2000L)
        extractsWith(topWith(extra = listOf(TarFixtures.file("top/skipped.bin", ByteArray(2000)))), limits, "2000 bytes")
        refusedWith(topWith(extra = listOf(TarFixtures.file("top/skipped.bin", ByteArray(2001)))), limits, ExtractionReason.ENTRY_TOO_LARGE, "2001 bytes")
    }

    @Test
    fun `a written file of exactly the entry bound extracts and one byte more is refused`() {
        val limits = limitsOf(maxEntryBytes = 2000L)
        extractsWith(sized(mapOf("tokens.txt" to 2000)), limits, "tokens of 2000 bytes")
        refusedWith(sized(mapOf("tokens.txt" to 2001)), limits, ExtractionReason.ENTRY_TOO_LARGE, "tokens of 2001 bytes")
    }

    @Test
    fun `an entry over the entry bound is refused from its header before its data is read`() {
        val huge = TarEntrySpec("top/huge.bin", declaredSize = 8_000_000_000L)
        refusedWith(topWith(extra = listOf(huge)), limitsOf(maxEntryBytes = 1_000_000L), ExtractionReason.ENTRY_TOO_LARGE, "header declares 8 GB and no data follows")
    }

    @Test
    fun `files whose total is exactly the written bound extract and one byte less is refused and none stay`() {
        val sizes = mapOf(
            "encoder-epoch-99-avg-1.int8.onnx" to 100,
            "decoder-epoch-99-avg-1.onnx" to 200,
            "joiner-epoch-99-avg-1.onnx" to 300,
            "tokens.txt" to 400,
        )
        val done = extractsWith(sized(sizes), limitsOf(maxWrittenBytes = 1000L), "total 1000, limit 1000")
        assertEquals(1000L, done.bytes)
        val rig = ExtractorRig(tmp.newFolder(), bz(sized(sizes)), limits = limitsOf(maxWrittenBytes = 999L))
        rig.expectRejected(ExtractionReason.WRITTEN_TOO_LARGE, "total 1000, limit 999")
        assertTrue("no byte past the bound reached the disk, wrote ${rig.totalWritten}", rig.totalWritten <= 999L)
    }

    @Test
    fun `a stream of exactly the stream bound extracts and one byte less is refused`() {
        val entries = topWith()
        val tarLength = TarFixtures.archiveOf(entries).size.toLong()
        extractsWith(entries, limitsOf(maxStreamBytes = tarLength), "tar of $tarLength bytes, limit the same")
        refusedWith(entries, limitsOf(maxStreamBytes = tarLength - 1L), ExtractionReason.STREAM_TOO_LARGE, "tar of $tarLength bytes, limit one less")
    }

    @Test
    fun `decompressed bytes over the stream bound are refused even when every entry is skipped`() {
        val bomb = TarFixtures.zerosArchive(1_048_576L)
        val tight = limitsOf(maxEntryBytes = 2_000_000L, maxStreamBytes = 262_144L)
        val rig = ExtractorRig(tmp.newFolder(), bomb, limits = tight)
        rig.expectRejected(ExtractionReason.STREAM_TOO_LARGE, "1 MiB of zeros, limit 256 KiB")
        assertEquals("nothing was written", 0, rig.outputs.size)
        val loose = limitsOf(maxEntryBytes = 2_000_000L, maxStreamBytes = 4_000_000L)
        ExtractorRig(tmp.newFolder(), bomb, limits = loose).expectRejected(ExtractionReason.MISSING_FILE, "same archive, loose stream bound: only the missing files stop it")
    }

    @Test
    fun `a name of exactly the maximum length extracts and one character less is refused`() {
        val entries = topWith()
        val longest = entries.maxOf { it.name.length }
        extractsWith(entries, limitsOf(maxPathLength = longest), "longest name $longest, limit the same")
        refusedWith(entries, limitsOf(maxPathLength = longest - 1), ExtractionReason.PATH_TOO_LONG, "longest name $longest, limit one less")
    }

    @Test
    fun `a path of exactly the maximum depth extracts and one level less is refused`() {
        val entries = topWith(extra = listOf(TarFixtures.file("top/a/b.bin", ByteArray(4))))
        extractsWith(entries, limitsOf(maxDepth = 3), "depth 3, limit 3")
        refusedWith(entries, limitsOf(maxDepth = 2), ExtractionReason.TOO_DEEP, "depth 3, limit 2")
    }

    @Test
    fun `free space of exactly the written bound extracts and one byte less is refused before anything is opened`() {
        val limits = limitsOf(maxWrittenBytes = 1000L)
        val enough = ExtractorRig(tmp.newFolder(), bz(topWith()), limits = limits)
        enough.freeSpace = 1000L
        assertTrue("expected Extracted", enough.run() is ExtractionOutcome.Extracted)
        assertEquals("the space is asked at the staging directory", listOf(enough.staging), enough.spaceAskedAt)

        val short = ExtractorRig(tmp.newFolder(), bz(topWith()), limits = limits)
        short.freeSpace = 999L
        short.expectRejected(ExtractionReason.NO_SPACE, "999 free, 1000 needed")
        assertEquals("the archive was not opened", 0, short.inputs.size)
        assertEquals("nothing was opened for writing", 0, short.outputs.size)
    }
}
