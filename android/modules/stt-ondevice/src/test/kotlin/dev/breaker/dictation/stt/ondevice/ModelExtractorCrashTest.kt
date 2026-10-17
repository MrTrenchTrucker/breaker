package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Failures in the middle of an extraction. A failure arm is injected through
 * the seams of [ModelExtractor] while the process survives; a kill cannot run
 * any cleanup, so that arm is shown by state (the leftover of a killed run is
 * swept by the next run, see the stale work directory test in
 * [ModelExtractorTest]). Every arm leaves no work directory and no target, and
 * a second extraction on the same places then succeeds.
 */
class ModelExtractorCrashTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val tinyFiles = TarFixtures.TINY_FILES

    /** tokens.txt first and about 200 kB of bytes that do not compress, so the stream has several blocks. */
    private fun bigArchive(): ByteArray {
        val entries = listOf(TarFixtures.dir("top/"), TarFixtures.file("top/tokens.txt", TarFixtures.randomBytes(200_000))) +
            (tinyFiles - "tokens.txt").map { TarFixtures.file("top/$it", TarFixtures.contentOf(it, 40)) }
        return TarFixtures.bz2(TarFixtures.archiveOf(entries), blockSize = 1)
    }

    private fun assertExtractedNames(rig: ExtractorRig, outcome: ExtractionOutcome) {
        assertTrue("expected Extracted but got $outcome", outcome is ExtractionOutcome.Extracted)
        assertEquals(tinyFiles.sorted(), rig.target.list()!!.sorted())
    }

    @Test
    fun `with no failure injected the large fixture extracts and its tokens file is byte exact`() {
        val rig = ExtractorRig(tmp.newFolder(), bigArchive())
        val outcome = rig.run()
        assertExtractedNames(rig, outcome)
        assertEquals(200_000L, File(rig.target, "tokens.txt").length())
        assertEquals(1, rig.inputs.size)
    }

    @Test
    fun `a read that fails mid-archive refuses with a stream error and removes the work directory`() {
        val bytes = bigArchive()
        val rig = ExtractorRig(tmp.newFolder(), bytes)
        rig.failReadAfter = bytes.size - 1000L
        val rejected = rig.expectRejected(ExtractionReason.STREAM_ERROR, "read fails near the end")
        assertTrue("some bytes had been written before the failure (${rejected.detail})", rig.totalWritten > 0L)
    }

    @Test
    fun `a write that fails mid-file refuses with a write error and removes the work directory`() {
        val rig = ExtractorRig(tmp.newFolder(), bigArchive())
        rig.failWriteAfter = 70_000L
        rig.expectRejected(ExtractionReason.WRITE_ERROR, "write fails inside the first file")
        assertTrue("the first file was partly written", rig.totalWritten in 1L..70_000L)
    }

    @Test
    fun `opening the second output fails after the first file was complete and nothing stays`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        rig.failOpenOutputAt = 2
        rig.expectRejected(ExtractionReason.WRITE_ERROR, "second open fails")
        assertEquals("only the first file was opened", 1, rig.outputs.size)
        assertEquals("the first file had been synced", 1, rig.synced.size)
    }

    @Test
    fun `a failing fsync refuses with a write error and closes the file`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        rig.failSync = true
        rig.expectRejected(ExtractionReason.WRITE_ERROR, "sync fails")
        assertEquals("the first sync was the last", 1, rig.synced.size)
    }

    @Test
    fun `a rename that fails refuses with a commit error and removes the work directory`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        rig.renameResult = false
        rig.expectRejected(ExtractionReason.COMMIT_FAILED, "rename fails")
        assertEquals("rename calls", 1, rig.renameCalls)
        assertEquals("all four files were in the work directory before the one rename", tinyFiles.sorted(), rig.workDirNamesAtRename)
        assertEquals("the target did not exist at the rename", false, rig.targetExistedAtRename)
        assertFalse("the target parent made for the rename is removed again", rig.target.parentFile!!.exists())
    }

    @Test
    fun `a target that already exists is refused untouched before any work is done`() {
        for (existing in listOf(listOf("keep.txt"), emptyList())) {
            val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
            rig.target.mkdirs()
            existing.forEach { File(rig.target, it).writeText("kept") }
            val outcome = rig.run()
            assertTrue("expected Rejected but got $outcome", outcome is ExtractionOutcome.Rejected)
            assertEquals(ExtractionReason.TARGET_EXISTS, (outcome as ExtractionOutcome.Rejected).reason)
            assertEquals("the target is untouched", existing, rig.target.list()!!.sorted())
            existing.forEach { assertEquals("kept", File(rig.target, it).readText()) }
            assertEquals("the archive was not opened", 0, rig.inputs.size)
            assertEquals("no space was asked for", 0, rig.spaceAskedAt.size)
            assertFalse("no work directory was made", rig.workDir.exists())
        }
    }

    @Test
    fun `an archive file that cannot be opened is refused and leaves nothing`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        assertTrue(rig.archive.delete())
        val outcome = rig.run()
        assertTrue("expected Rejected but got $outcome", outcome is ExtractionOutcome.Rejected)
        assertEquals(ExtractionReason.STREAM_ERROR, (outcome as ExtractionOutcome.Rejected).reason)
        assertFalse(rig.workDir.exists())
        assertFalse(rig.target.exists())
    }

    @Test
    fun `an unexpected runtime failure in a seam is a refusal that leaves nothing`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        val extractor = ModelExtractor(usableSpace = { Long.MAX_VALUE }, openOutput = { throw IllegalStateException("injected") })
        val outcome = extractor.extract(rig.archive, rig.profile, rig.workDir, rig.target)
        assertTrue("expected Rejected but got $outcome", outcome is ExtractionOutcome.Rejected)
        assertFalse(rig.workDir.exists())
        assertFalse(rig.target.exists())
    }

    @Test
    fun `a second extraction after a failed rename succeeds on the same places`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        rig.renameResult = false
        rig.expectRejected(ExtractionReason.COMMIT_FAILED, "first run")
        rig.renameResult = true
        assertExtractedNames(rig, rig.run())
    }

    @Test
    fun `a second extraction after a failed write succeeds on the same places`() {
        val rig = ExtractorRig(tmp.newFolder(), bigArchive())
        rig.failWriteAfter = 70_000L
        rig.expectRejected(ExtractionReason.WRITE_ERROR, "first run")
        rig.failWriteAfter = -1L
        assertExtractedNames(rig, rig.run())
        assertEquals(200_000L, File(rig.target, "tokens.txt").length())
    }

    @Test
    fun `a second extraction after a damaged archive succeeds once the archive is replaced`() {
        val good = TarFixtures.tinyArchive()
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.cutInMiddle(good))
        rig.expectRejected(ExtractionReason.TRUNCATED, "damaged archive")
        rig.archive.writeBytes(good)
        assertExtractedNames(rig, rig.run())
    }

    @Test
    fun `a second extraction after a refused read succeeds on the same places`() {
        val bytes = bigArchive()
        val rig = ExtractorRig(tmp.newFolder(), bytes)
        rig.failReadAfter = bytes.size - 1000L
        rig.expectRejected(ExtractionReason.STREAM_ERROR, "first run")
        rig.failReadAfter = -1L
        assertExtractedNames(rig, rig.run())
    }
}
