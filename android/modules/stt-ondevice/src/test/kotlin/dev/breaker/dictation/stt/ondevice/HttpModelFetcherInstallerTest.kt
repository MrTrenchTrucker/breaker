package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * The sentences a user would see, through the real installer and store with the real fetcher
 * over a scripted opener. The sentences are compared through the constants of [ModelMessages].
 */
class HttpModelFetcherInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LocalModelStore
    private lateinit var staging: File

    private val payload = ByteArray(3000) { ((it * 7) % 251).toByte() }
    private val digest = httpTestSha256Hex(payload)
    private val sink = RecordingSink()

    private class RecordingSink : ModelDebugSink {
        val lines = ArrayList<String>()
        override fun debug(message: String) { lines.add(message) }
    }

    @Before
    fun setUp() {
        store = LocalModelStore(tmp.newFolder("models"))
        staging = store.stagingDirectory()
    }

    private fun entry() = testEntry(sha256 = digest)

    private fun list(): Step = serve(okBytes(httpTestChecksumList(digest)))

    private fun install(opener: FakeOpener, openOutput: (File) -> OutputStream = { FileOutputStream(it) }): ModelInstaller.InstallResult =
        ModelInstaller(store, fetcherOf(opener, openOutput = openOutput), sink).install(entry())

    private fun refused(result: ModelInstaller.InstallResult, claim: String): ModelInstaller.InstallResult.Refused =
        result as? ModelInstaller.InstallResult.Refused ?: throw AssertionError("$claim: expected a refusal but got $result")

    private fun assertNothingInstalled() {
        assertFalse("the model is not installed", store.isInstalled("tiny"))
        assertFalse("no model directory was created", store.directoryFor("tiny").exists())
    }

    @Test
    fun `no network at the checksum step gives the checksum-list sentence and the model is never requested`() {
        for (error in listOf(UnknownHostException("down"), ConnectException("down"))) {
            val opener = FakeOpener(failWith(error), serve(okBytes(payload)))
            val result = refused(install(opener), "${error.javaClass.simpleName} at the checksum step")
            assertEquals(ModelInstaller.Refusal.CHECKSUMS_FAILED, result.refusal)
            assertEquals(ModelMessages.CHECKSUMS_DOWNLOAD_FAILED, result.detail)
            assertEquals("only the checksum address was asked", listOf(DownloadLimits().checksumsUrl), opener.requests.map { it.url })
            assertTrue(
                "the technical text names the class: ${sink.lines}",
                sink.lines.any { it.contains("network: ${error.javaClass.simpleName}") },
            )
            assertNothingInstalled()
        }
    }

    @Test
    fun `a model-step network failure gives the model download sentence and no staged model file remains`() {
        val opener = FakeOpener(list(), failWith(ConnectException("down")))
        val result = refused(install(opener), "network failure at the model step")
        assertEquals(ModelInstaller.Refusal.FETCH_FAILED, result.refusal)
        assertEquals(ModelMessages.MODEL_DOWNLOAD_FAILED, result.detail)
        assertEquals("only the checksum list is left in staging", listOf("checksums.txt"), stagedNames(staging))
        assertNothingInstalled()
    }

    @Test
    fun `a disk write failure gives the save sentence`() {
        val opener = FakeOpener(list(), serve(okBytes(payload)))
        val open: (File) -> OutputStream = { file ->
            if (file.name == "checksums.txt") FileOutputStream(file) else FailingOutput(file, 1000, OutputFault.WRITE)
        }
        val result = refused(install(opener, open), "write failure at the model step")
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, result.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, result.detail)
        assertEquals("only the checksum list is left in staging", listOf("checksums.txt"), stagedNames(staging))
        assertNothingInstalled()
    }

    @Test
    fun `a disk write failure on the checksum list gives the save sentence and the model is never requested`() {
        val opener = FakeOpener(list(), serve(okBytes(payload)))
        val result = refused(install(opener) { FailingOutput(it, 10, OutputFault.WRITE) }, "write failure at the checksum step")
        assertEquals(ModelInstaller.Refusal.STAGING_FAILED, result.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, result.detail)
        assertEquals("the model was not requested", 1, opener.requests.size)
        assertEquals("nothing is left in staging", emptyList<String>(), stagedNames(staging))
    }

    @Test
    fun `a truncated body gives the download sentence and not the check sentence`() {
        val cut = reply(body = BytesStream(payload.copyOf(1500)), contentLength = payload.size.toLong())
        val result = refused(install(FakeOpener(list(), serve(cut))), "body cut at half its declared length")
        assertEquals(ModelInstaller.Refusal.FETCH_FAILED, result.refusal)
        assertEquals(ModelMessages.MODEL_DOWNLOAD_FAILED, result.detail)
        assertNotEquals("not the check sentence", ModelMessages.DOWNLOAD_FAILED_CHECK, result.detail)
        assertEquals("only the checksum list is left in staging", listOf("checksums.txt"), stagedNames(staging))
        assertNothingInstalled()
    }

    @Test
    fun `a swapped body of the right length is refused by the digest check and discarded`() {
        val swapped = payload.reversedArray()
        assertNotEquals("control: the swapped bytes differ from the pinned ones", digest, httpTestSha256Hex(swapped))
        val result = refused(install(FakeOpener(list(), serve(okBytes(swapped)))), "swapped body of the same length")
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, result.refusal)
        assertEquals(ModelMessages.DOWNLOAD_FAILED_CHECK, result.detail)
        assertFalse("the file was deleted", result.leftOnDisk)
        assertEquals("only the checksum list is left in staging", listOf("checksums.txt"), stagedNames(staging))
        assertNothingInstalled()
    }

    @Test
    fun `a cap breach leaves nothing in staging but the checksum file`() {
        val over = reply(body = SizedStream(HttpFixtures.CAP_1MB + 1))
        val result = refused(install(FakeOpener(list(), serve(over))), "body one byte over the cap")
        assertEquals(ModelInstaller.Refusal.FETCH_FAILED, result.refusal)
        assertEquals(ModelMessages.MODEL_DOWNLOAD_FAILED, result.detail)
        assertEquals("only the checksum list is left in staging", listOf("checksums.txt"), stagedNames(staging))
        assertNothingInstalled()
    }

    @Test
    fun `an unsafe model id is refused as a failed download before any model request`() {
        val opener = FakeOpener(list(), serve(okBytes(payload)))
        val unsafe = ModelInstaller(store, fetcherOf(opener), sink).install(testEntry(id = "../evil", sha256 = digest))
        val result = refused(unsafe, "unsafe model id")
        assertEquals(ModelInstaller.Refusal.FETCH_FAILED, result.refusal)
        assertEquals(ModelMessages.MODEL_DOWNLOAD_FAILED, result.detail)
        assertEquals("only the checksum address was asked", 1, opener.requests.size)
    }
}
