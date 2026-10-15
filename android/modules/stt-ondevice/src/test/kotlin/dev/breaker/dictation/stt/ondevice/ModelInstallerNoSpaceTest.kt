package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The installer's answer when a download has too little free space, through the real
 * fetcher over a scripted opener. The user sentence for that case is compared as literal
 * text. The write failures and the plain IOException at each step are the controls: they
 * keep the staging sentence and show the new refusal is not every IOException.
 */
class ModelInstallerNoSpaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LocalModelStore
    private lateinit var staging: File

    private val payload = ByteArray(3000) { ((it * 7) % 251).toByte() }
    private val digest = httpTestSha256Hex(payload)
    private val sink = RecordingSink()

    private class RecordingSink : ModelDebugSink {
        val lines = ArrayList<String>()
        override fun debug(message: String) {
            lines.add(message)
        }
    }

    @Before
    fun setUp() {
        store = LocalModelStore(tmp.newFolder("models"))
        staging = store.stagingDirectory()
    }

    private fun entry() = testEntry(sha256 = digest)

    private fun list(): Step = serve(okBytes(httpTestChecksumList(digest)))

    private fun install(
        opener: FakeOpener,
        usableSpace: (File) -> Long = { Long.MAX_VALUE },
        openOutput: (File) -> OutputStream = { FileOutputStream(it) },
    ): ModelInstaller.InstallResult =
        ModelInstaller(store, fetcherOf(opener, openOutput = openOutput, usableSpace = usableSpace), sink).install(entry())

    private fun refused(result: ModelInstaller.InstallResult, claim: String): ModelInstaller.InstallResult.Refused =
        result as? ModelInstaller.InstallResult.Refused ?: throw AssertionError("$claim: expected a refusal but got $result")

    private fun assertNothingInstalled() {
        assertFalse("the model is not installed", store.isInstalled("tiny"))
        assertFalse("no model directory was created", store.directoryFor("tiny").exists())
    }

    private fun listFile(dir: File): ModelFetcher.Result {
        val file = File(dir, "checksums.txt")
        file.writeBytes(httpTestChecksumList(digest))
        return ModelFetcher.Result.Fetched(file)
    }

    @Test
    fun `model step with too little space refuses as no space with the literal sentence`() {
        val opener = FakeOpener(list(), serve(okBytes(payload)))
        val result = refused(install(opener, usableSpace = { 1000L }), "model step with 1000 bytes free")
        assertEquals(ModelInstaller.Refusal.NO_SPACE, result.refusal)
        assertEquals(
            "the user sentence",
            "There is not enough free space on the phone to download the model.",
            result.detail,
        )
        assertFalse("nothing was left on disk", result.leftOnDisk)
        assertNothingInstalled()
        assertEquals("only the checksum list is left in staging", listOf("checksums.txt"), stagedNames(staging))
        assertTrue(
            "the sink names the refusal: ${sink.lines}",
            sink.lines.any { it.startsWith("install refused (NO_SPACE):") },
        )
    }

    @Test
    fun `checksum step with no space refuses as no space and never requests the model`() {
        val opener = FakeOpener(list(), serve(okBytes(payload)))
        val result = refused(install(opener, usableSpace = { 0L }), "checksum step with 0 bytes free")
        assertEquals(ModelInstaller.Refusal.NO_SPACE, result.refusal)
        assertEquals(
            "the user sentence",
            "There is not enough free space on the phone to download the model.",
            result.detail,
        )
        assertEquals("only the checksum address was requested", listOf(DownloadLimits().checksumsUrl), opener.requests.map { it.url })
        assertEquals("nothing is left in staging", emptyList<String>(), stagedNames(staging))
        assertNothingInstalled()
    }

    @Test
    fun `a write failure at the model step still gives the save sentence`() {
        val opener = FakeOpener(list(), serve(okBytes(payload)))
        val open: (File) -> OutputStream = { file ->
            if (file.name == "checksums.txt") FileOutputStream(file) else FailingOutput(file, 1000, OutputFault.WRITE)
        }
        val result = refused(install(opener, openOutput = open), "write failure at the model step")
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, result.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, result.detail)
        assertNothingInstalled()
    }

    @Test
    fun `a write failure at the checksum step still gives the save sentence`() {
        val opener = FakeOpener(list(), serve(okBytes(payload)))
        val result = refused(install(opener, openOutput = { FailingOutput(it, 10, OutputFault.WRITE) }), "write failure at the checksum step")
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, result.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, result.detail)
        assertEquals("the model was not requested", 1, opener.requests.size)
        assertNothingInstalled()
    }

    @Test
    fun `a hand-written fetcher that throws a plain IOException still gives the save sentence`() {
        val modelThrows = object : ModelFetcher {
            override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result =
                throw IOException("boom")

            override fun fetchChecksums(stagingDir: File): ModelFetcher.Result = listFile(stagingDir)
        }
        val checksumThrows = object : ModelFetcher {
            override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result =
                ModelFetcher.Result.Failed("unused")

            override fun fetchChecksums(stagingDir: File): ModelFetcher.Result =
                throw IOException("boom")
        }
        val atModel = refused(ModelInstaller(store, modelThrows, sink).install(entry()), "plain IOException at the model step")
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, atModel.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, atModel.detail)
        val atChecksums = refused(ModelInstaller(store, checksumThrows, sink).install(entry()), "plain IOException at the checksum step")
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, atChecksums.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, atChecksums.detail)
        assertNothingInstalled()
    }
}
