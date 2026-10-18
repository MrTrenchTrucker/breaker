package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** An archive stream whose first read throws [thrown]; it notes its close. */
internal class ReadThrows(private val thrown: Throwable) : InputStream() {
    var closed = false

    override fun read(): Int = throw thrown

    override fun read(b: ByteArray, off: Int, len: Int): Int = throw thrown

    override fun close() {
        closed = true
    }
}

/** An output file that accepts nothing, or accepts everything, and can be told to fail on write and on close. */
internal class OutputThatBreaks(private val failWrite: Boolean, private val failClose: Boolean) : OutputStream() {
    var closeCalls = 0

    override fun write(b: Int) {
        if (failWrite) throw IOException("injected write failure")
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (failWrite) throw IOException("injected write failure")
    }

    override fun close() {
        closeCalls++
        if (failClose) throw IOException("injected close failure")
    }
}

/**
 * The refusal reason of each failure that only a replaced seam or a misbehaving
 * file or directory can produce: the read side, the work directory, the output
 * file, the move into place. Each test names the reason it must see, so a
 * refusal that carries another reason is a failure of its own.
 */
class ModelExtractorSeamTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rigOf(limits: ExtractionLimits = limitsOf()) = ExtractorRig(tmp.newFolder(), bz(topWith()), limits = limits)

    private fun rejectedBy(outcome: ExtractionOutcome, reason: ExtractionReason, label: String): ExtractionOutcome.Rejected {
        assertTrue("$label: expected Rejected($reason) but got $outcome", outcome is ExtractionOutcome.Rejected)
        val rejected = outcome as ExtractionOutcome.Rejected
        assertEquals("$label: reason", reason, rejected.reason)
        return rejected
    }

    private fun readFails(thrown: Throwable, reason: ExtractionReason, label: String) {
        val rig = rigOf()
        val input = ReadThrows(thrown)
        val extractor = ModelExtractor(usableSpace = { Long.MAX_VALUE }, open = { input })
        rejectedBy(extractor.extract(rig.archive, rig.profile, rig.workDir, rig.target), reason, label)
        rig.assertNothingLeft(label)
        assertTrue("$label: the archive stream is closed", input.closed)
    }

    private fun withOutput(rig: ExtractorRig, out: OutputThatBreaks): ExtractionOutcome =
        ModelExtractor(usableSpace = { Long.MAX_VALUE }, openOutput = { out }, syncFile = {})
            .extract(rig.archive, rig.profile, rig.workDir, rig.target)

    // ---- the entry points and the target ----

    @Test
    fun `a model id without a profile is rejected with the missing file reason`() {
        val archive = File(tmp.newFolder(), "a.bin")
        archive.writeBytes(TarFixtures.tinyArchive())
        val outcome = ModelExtractor().extract(archive, "base", LocalModelStore(tmp.newFolder()))
        rejectedBy(outcome, ExtractionReason.MISSING_FILE, "no profile for the id")
    }

    @Test
    fun `a plain file at the target path is refused as an existing target before anything is opened`() {
        val rig = rigOf()
        rig.target.parentFile!!.mkdirs()
        rig.target.writeText("plain file")
        rejectedBy(rig.run(), ExtractionReason.TARGET_EXISTS, "plain file at the target")
        assertEquals("the archive was not opened", 0, rig.inputs.size)
        assertEquals("no space was asked for", 0, rig.spaceAskedAt.size)
        assertEquals("the file at the target is untouched", "plain file", rig.target.readText())
        assertFalse("no work directory was made", rig.workDir.exists())
    }

    // ---- how a failure on the read side is classified ----

    @Test
    fun `a runtime failure on the read side is classified like an input failure`() {
        val bound = IllegalStateException("wrapper", StreamLimitExceededException())
        readFails(bound, ExtractionReason.STREAM_TOO_LARGE, "runtime failure caused by the stream bound")
        readFails(IllegalStateException("archive truncated"), ExtractionReason.TRUNCATED, "runtime failure that says truncated")
        readFails(IllegalStateException("something odd"), ExtractionReason.STREAM_ERROR, "runtime failure with no early end")
    }

    @Test
    fun `a read failure is classified by its class and by the words of its message`() {
        val early = listOf(
            "end of file with no message" to EOFException(),
            "the word truncated" to IOException("archive truncated"),
            "the words unexpected end" to IOException("unexpected end of stream"),
            "the word premature" to IOException("premature end of file"),
            "the words in capitals" to IOException("Unexpected End Of Stream"),
            "the word truncated in capitals" to IOException("TRUNCATED ARCHIVE"),
        )
        for ((label, thrown) in early) readFails(thrown, ExtractionReason.TRUNCATED, label)
        readFails(IOException("the disk exploded"), ExtractionReason.STREAM_ERROR, "a message with no early end")
    }

    @Test
    fun `the cause chain of a read failure is walked`() {
        readFails(IOException("wrapper", StreamLimitExceededException()), ExtractionReason.STREAM_TOO_LARGE, "bound below the top")
        readFails(IOException("wrapper", EOFException()), ExtractionReason.TRUNCATED, "early end below the top")
        readFails(IOException("wrapper", IOException("middle", EOFException())), ExtractionReason.TRUNCATED, "early end two levels down")
    }

    // ---- the work directory ----

    @Test
    fun `a work directory with no parent is refused as a write error`() {
        val rig = rigOf()
        val bare = File("model-work-dir-without-parent")
        assertFalse("fixture: the name is free in the working directory", bare.exists())
        rejectedBy(rig.extractor().extract(rig.archive, rig.profile, bare, rig.target), ExtractionReason.WRITE_ERROR, "no parent")
        assertEquals("the archive was not opened", 0, rig.inputs.size)
        assertFalse("nothing was made at the bare name", bare.exists())
        assertFalse("no target", rig.target.exists())
    }

    @Test
    fun `a plain file in the place of the staging directory is refused as a write error before the archive is opened`() {
        val rig = rigOf()
        rig.staging.writeText("plain file")
        rig.expectRejected(ExtractionReason.WRITE_ERROR, "staging is a plain file")
        assertEquals("the archive was not opened", 0, rig.inputs.size)
        assertEquals("the file is untouched", "plain file", rig.staging.readText())
    }

    @Test
    fun `an old work directory that cannot be removed is refused as a write error before the archive is opened`() {
        val rig = rigOf()
        val stuck = object : File(rig.workDir.path) {
            override fun exists(): Boolean = true

            override fun delete(): Boolean = false
        }
        rejectedBy(rig.extractor().extract(rig.archive, rig.profile, stuck, rig.target), ExtractionReason.WRITE_ERROR, "old directory stays")
        assertEquals("the archive was not opened", 0, rig.inputs.size)
        assertFalse("no target", rig.target.exists())
    }

    @Test
    fun `the space refusal names the number of bytes it wanted free`() {
        val rig = rigOf(limits = limitsOf(maxWrittenBytes = 123_457L))
        rig.freeSpace = 123_456L
        val rejected = rig.expectRejected(ExtractionReason.NO_SPACE, "one byte short")
        assertTrue("the detail must name the bound: ${rejected.detail}", rejected.detail.contains("123457"))
    }

    @Test
    fun `a work directory that cannot be created is refused as a write error before the archive is opened`() {
        val rig = rigOf()
        val unmakeable = object : File(rig.workDir.path) {
            override fun mkdir(): Boolean = false
        }
        rejectedBy(rig.extractor().extract(rig.archive, rig.profile, unmakeable, rig.target), ExtractionReason.WRITE_ERROR, "mkdir fails")
        assertEquals("the archive was not opened", 0, rig.inputs.size)
        assertEquals("nothing was opened for writing", 0, rig.outputs.size)
        assertFalse("no work directory", rig.workDir.exists())
    }

    // ---- the output file ----

    @Test
    fun `a close that fails while a write is failing keeps the write error`() {
        val rig = rigOf()
        val out = OutputThatBreaks(failWrite = true, failClose = true)
        rejectedBy(withOutput(rig, out), ExtractionReason.WRITE_ERROR, "write and close both fail")
        assertEquals("the output was closed once", 1, out.closeCalls)
        rig.assertNothingLeft("write and close both fail")
    }

    @Test
    fun `a close that fails after a complete file is a write error and nothing stays`() {
        val rig = rigOf()
        val out = OutputThatBreaks(failWrite = false, failClose = true)
        rejectedBy(withOutput(rig, out), ExtractionReason.WRITE_ERROR, "close fails after a complete file")
        assertEquals("the output was closed once", 1, out.closeCalls)
        rig.assertNothingLeft("close fails after a complete file")
    }

    // ---- the move into place ----

    @Test
    fun `a target parent that cannot be created is refused as a commit failure`() {
        val root = tmp.newFolder()
        val rig = ExtractorRig(root, bz(topWith()))
        val blocker = File(root, "blocker")
        blocker.writeText("plain file")
        val target = File(File(blocker, "m"), "files")
        rejectedBy(rig.extractor().extract(rig.archive, rig.profile, rig.workDir, target), ExtractionReason.COMMIT_FAILED, "parent below a file")
        assertEquals("the move was not tried", 0, rig.renameCalls)
        assertFalse("the work directory is removed", rig.workDir.exists())
        assertEquals("nothing stays in staging", 0, rig.staging.list()?.size ?: 0)
        assertEquals("the file in the way is untouched", "plain file", blocker.readText())
    }

    @Test
    fun `a target parent that was there before is kept after a failed move`() {
        val rig = rigOf()
        rig.renameResult = false
        val parent = rig.target.parentFile!!
        assertTrue("fixture: the parent exists and is empty", parent.mkdirs() && parent.list()!!.isEmpty())
        rig.expectRejected(ExtractionReason.COMMIT_FAILED, "move fails")
        assertEquals("one move was tried", 1, rig.renameCalls)
        assertTrue("a parent that was there before the run stays", parent.isDirectory)
    }
}
