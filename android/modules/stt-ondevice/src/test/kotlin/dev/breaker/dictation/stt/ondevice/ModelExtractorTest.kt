package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Limits that are small enough for tiny test archives; a test names only the one it probes. */
internal fun limitsOf(
    maxEntries: Int = 64,
    maxEntryBytes: Long = 1_000_000L,
    maxWrittenBytes: Long = 4_000_000L,
    maxStreamBytes: Long = 16_000_000L,
    maxPathLength: Int = 128,
    maxDepth: Int = 4,
): ExtractionLimits = ExtractionLimits(maxEntries, maxEntryBytes, maxWrittenBytes, maxStreamBytes, maxPathLength, maxDepth)

/** A directory "top/" and the profile files below it, each [size] bytes, then [extra] entries. */
internal fun topWith(
    files: List<String> = TarFixtures.TINY_FILES,
    size: Int = 40,
    extra: List<TarEntrySpec> = emptyList(),
): List<TarEntrySpec> =
    listOf(TarFixtures.dir("top/")) + files.map { TarFixtures.file("top/$it", TarFixtures.contentOf(it, size)) } + extra

/** The tar of [entries] with a proper end, compressed. */
internal fun bz(entries: List<TarEntrySpec>): ByteArray = TarFixtures.bz2(TarFixtures.archiveOf(entries))

/** An input that counts what it delivers, notes its close and can fail after [failAfter] bytes (negative: never). */
internal class TrackedInput(private val inner: InputStream, private val failAfter: Long) : InputStream() {
    var closed = false
    private var delivered = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (failAfter >= 0L && delivered >= failAfter) throw IOException("injected read failure")
        val room = if (failAfter < 0L) len else minOf(len.toLong(), failAfter - delivered).toInt()
        val n = inner.read(b, off, room)
        if (n > 0) delivered += n
        return n
    }

    override fun close() {
        closed = true
        inner.close()
    }
}

/** An output that notes its close and fails once the rig's running total would pass its failure point. */
internal class TrackedOutput(val file: File, private val inner: OutputStream, private val rig: ExtractorRig) : OutputStream() {
    var closed = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (rig.failWriteAfter >= 0L && rig.totalWritten + len > rig.failWriteAfter) throw IOException("injected write failure")
        inner.write(b, off, len)
        rig.totalWritten += len
    }

    override fun close() {
        closed = true
        inner.close()
    }
}

/**
 * The places, the archive file and the recording or failing seams of one extraction.
 * The files of the profile are [files]; the limits are [limits].
 */
internal class ExtractorRig(
    root: File,
    archiveBytes: ByteArray,
    val files: List<String> = TarFixtures.TINY_FILES,
    val limits: ExtractionLimits = limitsOf(),
) {
    val archive = File(root, "model.archive").also { it.writeBytes(archiveBytes) }
    val staging = File(root, ".staging")
    val workDir = File(staging, "m.extracting")
    val target = File(File(root, "m"), "files")
    private val archiveBefore = archiveBytes.copyOf()
    val profile = ExtractionProfile(files[0], files[1], files[2], files[3], limits)

    var freeSpace = Long.MAX_VALUE
    val spaceAskedAt = mutableListOf<File>()
    val inputs = mutableListOf<TrackedInput>()
    val outputs = mutableListOf<TrackedOutput>()
    val synced = mutableListOf<String>()
    val syncedWhileOpen = mutableListOf<Boolean>()
    var totalWritten = 0L
    var failReadAfter = -1L
    var failOpenOutputAt = -1
    var failWriteAfter = -1L
    var failSync = false
    var renameResult = true
    var renameCalls = 0
    var workDirNamesAtRename: List<String>? = null
    var targetExistedAtRename: Boolean? = null
    private var outputOpens = 0

    fun extractor(): ModelExtractor = ModelExtractor(
        usableSpace = { dir -> spaceAskedAt.add(dir); freeSpace },
        open = { file -> TrackedInput(file.inputStream(), failReadAfter).also { inputs.add(it) } },
        openOutput = { file -> openOutputFile(file) },
        syncFile = { out -> recordSync(out as TrackedOutput) },
        rename = { from, to -> recordRename(from, to) },
    )

    fun run(): ExtractionOutcome = extractor().extract(archive, profile, workDir, target)

    private fun openOutputFile(file: File): OutputStream {
        outputOpens++
        if (outputOpens == failOpenOutputAt) throw IOException("injected open failure")
        return TrackedOutput(file, FileOutputStream(file), this).also { outputs.add(it) }
    }

    private fun recordSync(out: TrackedOutput) {
        synced.add(out.file.name)
        syncedWhileOpen.add(!out.closed)
        if (failSync) throw IOException("injected sync failure")
    }

    private fun recordRename(from: File, to: File): Boolean {
        renameCalls++
        workDirNamesAtRename = from.list()?.sorted()
        targetExistedAtRename = to.exists()
        return if (renameResult) from.renameTo(to) else false
    }

    /** Runs the extraction and demands a refusal with [reason] that left nothing behind. */
    fun expectRejected(reason: ExtractionReason, label: String): ExtractionOutcome.Rejected {
        val outcome = run()
        assertTrue("$label: expected Rejected($reason) but got $outcome", outcome is ExtractionOutcome.Rejected)
        val rejected = outcome as ExtractionOutcome.Rejected
        assertEquals("$label: reason", reason, rejected.reason)
        assertNothingLeft(label)
        assertTrue("$label: every opened stream is closed", inputs.all { it.closed } && outputs.all { it.closed })
        return rejected
    }

    fun assertNothingLeft(label: String) {
        assertFalse("$label: the target must not exist", target.exists())
        assertFalse("$label: the work directory must not exist", workDir.exists())
        assertEquals("$label: nothing may stay in the staging directory", 0, staging.list()?.size ?: 0)
        assertArrayEquals("$label: the archive file must be unchanged", archiveBefore, archive.readBytes())
    }
}

class ModelExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun extracted(rig: ExtractorRig): ExtractionOutcome.Extracted {
        val outcome = rig.run()
        assertTrue("expected Extracted but got $outcome", outcome is ExtractionOutcome.Extracted)
        return outcome as ExtractionOutcome.Extracted
    }

    @Test
    fun `a tiny shaped archive extracts exactly the four profile files into the target`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        val done = extracted(rig)
        assertEquals("directory", rig.target, done.directory)
        assertEquals("file count", 4, done.fileCount)
        assertEquals("bytes written", 4L * 48L, done.bytes)
        assertEquals("names in the target", TarFixtures.TINY_FILES.sorted(), rig.target.list()!!.sorted())
        assertTrue("the target holds plain files only", rig.target.listFiles()!!.all { it.isFile })
        assertFalse("the work directory is gone", rig.workDir.exists())
        assertEquals("one input was opened", 1, rig.inputs.size)
        assertTrue("the input is closed", rig.inputs.all { it.closed })
        assertTrue("every output is closed", rig.outputs.isNotEmpty() && rig.outputs.all { it.closed })
    }

    @Test
    fun `the extracted bytes equal the archive bytes`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        extracted(rig)
        for (name in TarFixtures.TINY_FILES) {
            assertArrayEquals("content of $name", TarFixtures.contentOf(name, 48), File(rig.target, name).readBytes())
        }
    }

    @Test
    fun `a small shaped archive extracts its four files and not the full precision encoder`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.smallArchive(), files = TarFixtures.SMALL_FILES)
        extracted(rig)
        assertEquals(TarFixtures.SMALL_FILES.sorted(), rig.target.list()!!.sorted())
        assertFalse(File(rig.target, "encoder-epoch-99-avg-1.onnx").exists())
        for (name in TarFixtures.SMALL_FILES) {
            assertArrayEquals("content of $name", TarFixtures.contentOf(name, 48), File(rig.target, name).readBytes())
        }
    }

    @Test
    fun `entries that are not in the profile are read past and not written`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        extracted(rig)
        for (skipped in listOf("README.md", "export-onnx-en-20M.sh", "decoder-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.int8.onnx", "test_wavs")) {
            assertFalse("$skipped must not be written", File(rig.target, skipped).exists())
        }
        assertEquals("only the four files were opened for writing", 4, rig.outputs.size)
    }

    @Test
    fun `a file with a profile name deeper in the tree is not written`() {
        val decoy = listOf(TarFixtures.dir("top/"), TarFixtures.dir("top/sub/"), TarFixtures.file("top/sub/tokens.txt", TarFixtures.contentOf("decoy", 33)))
        val real = TarFixtures.TINY_FILES.map { TarFixtures.file("top/$it", TarFixtures.contentOf(it, 40)) }
        val rig = ExtractorRig(tmp.newFolder(), bz(decoy + real))
        val done = extracted(rig)
        assertEquals(4L * 40L, done.bytes)
        assertArrayEquals("tokens.txt is the top-level one", TarFixtures.contentOf("tokens.txt", 40), File(rig.target, "tokens.txt").readBytes())
    }

    @Test
    fun `a leading dot-slash on every name is accepted`() {
        val dotted = topWith().map { TarEntrySpec("./" + it.name, it.type, it.content) }
        val rig = ExtractorRig(tmp.newFolder(), bz(dotted))
        extracted(rig)
        assertEquals(TarFixtures.TINY_FILES.sorted(), rig.target.list()!!.sorted())
    }

    @Test
    fun `fsync is called once per written file before it is closed and never for a skipped entry`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        extracted(rig)
        assertEquals("one sync per written file", TarFixtures.TINY_FILES.sorted(), rig.synced.sorted())
        assertTrue("each sync happens while the file is still open", rig.syncedWhileOpen.all { it })
    }

    @Test
    fun `the files appear in the target by one rename after all four are in the work directory`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        extracted(rig)
        assertEquals("rename calls", 1, rig.renameCalls)
        assertEquals("files in the work directory at the rename", TarFixtures.TINY_FILES.sorted(), rig.workDirNamesAtRename)
        assertEquals("the target did not exist before the rename", false, rig.targetExistedAtRename)
        assertFalse("the work directory is gone after the rename", rig.workDir.exists())
    }

    @Test
    fun `the target parent directory is created when it is absent`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        assertFalse(rig.target.parentFile!!.exists())
        extracted(rig)
        assertTrue(rig.target.isDirectory)
    }

    @Test
    fun `a stale work directory left by a killed run is swept before the next extraction`() {
        val rig = ExtractorRig(tmp.newFolder(), TarFixtures.tinyArchive())
        rig.workDir.mkdirs()
        File(rig.workDir, "junk.bin").writeText("left over")
        extracted(rig)
        assertEquals("only the four files, no old junk", TarFixtures.TINY_FILES.sorted(), rig.target.list()!!.sorted())
    }

    @Test
    fun `a profile file missing from the archive is refused`() {
        for (name in TarFixtures.TINY_FILES) {
            val rig = ExtractorRig(tmp.newFolder(), bz(topWith(files = TarFixtures.TINY_FILES - name)))
            rig.expectRejected(ExtractionReason.MISSING_FILE, "without $name")
        }
    }

    @Test
    fun `a profile file of zero bytes is refused`() {
        for (name in TarFixtures.TINY_FILES) {
            val entries = listOf(TarFixtures.dir("top/")) + TarFixtures.TINY_FILES.map {
                TarFixtures.file("top/$it", if (it == name) ByteArray(0) else TarFixtures.contentOf(it, 40))
            }
            val rig = ExtractorRig(tmp.newFolder(), bz(entries))
            rig.expectRejected(ExtractionReason.MISSING_FILE, "$name with no bytes")
        }
    }
}
