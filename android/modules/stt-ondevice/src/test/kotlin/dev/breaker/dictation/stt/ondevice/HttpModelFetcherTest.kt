package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/** The happy path of the real fetcher and the shape of the requests it sends. */
class HttpModelFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var staging: File

    private val payload = ByteArray(5000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        staging = tmp.newFolder("staging")
    }

    @Test
    fun `fetchModel writes the served bytes to a file under the staging directory and returns it`() {
        val opener = FakeOpener(serve(okBytes(payload)))
        val file = attemptModel(fetcherOf(opener), testEntry(), staging).fetchedFile("a plain 200 reply")
        assertEquals("the staged file is named after the model id", "tiny.download", file.name)
        assertEquals("the staged file sits directly in the staging directory", staging.canonicalFile, file.parentFile.canonicalFile)
        assertArrayEquals("the staged bytes are the served bytes", payload, file.readBytes())
        assertEquals("nothing else is left in staging", listOf("tiny.download"), stagedNames(staging))
    }

    @Test
    fun `fetchModel names the staged file after the id of the entry`() {
        val opener = FakeOpener(serve(okBytes(payload)))
        val file = attemptModel(fetcherOf(opener), testEntry(id = "other-model_2"), staging).fetchedFile("a second id")
        assertEquals("other-model_2.download", file.name)
    }

    @Test
    fun `fetchChecksums fetches the checksum address and stages its text`() {
        val limits = DownloadLimits(checksumsUrl = "https://api.github.com/repos/example/example/releases/assets/7")
        val text = "model.archive\t${"a".repeat(64)}\n".toByteArray()
        val opener = FakeOpener(serve(okBytes(text)))
        val file = attemptChecksums(fetcherOf(opener, limits), staging).fetchedFile("a checksum list")
        assertEquals("checksums.txt", file.name)
        assertEquals("the staged file sits directly in the staging directory", staging.canonicalFile, file.parentFile.canonicalFile)
        assertArrayEquals("the staged text is the served text", text, file.readBytes())
        assertEquals("one request, to the configured checksum address", listOf(limits.checksumsUrl), opener.requests.map { it.url })
    }

    @Test
    fun `the default checksum address is the published asset route`() {
        assertEquals(
            "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/424735889",
            DownloadLimits().checksumsUrl,
        )
    }

    @Test
    fun `every request asks for the binary stream and identity encoding and carries no credential or cookie`() {
        val seenAll = ArrayList<SeenRequest>()
        val modelOpener = FakeOpener(serve(redirectTo(HttpFixtures.RELEASE_URL)), serve(okBytes(payload)))
        attemptModel(fetcherOf(modelOpener), testEntry(), staging).fetchedFile("model after one redirect")
        val listOpener = FakeOpener(serve(redirectTo(HttpFixtures.RELEASE_URL)), serve(okBytes(payload)))
        attemptChecksums(fetcherOf(listOpener), staging).fetchedFile("checksums after one redirect")
        seenAll.addAll(modelOpener.requests)
        seenAll.addAll(listOpener.requests)
        assertEquals("two requests for each fetch", 4, seenAll.size)
        for ((i, request) in seenAll.withIndex()) {
            assertEquals(
                "request $i carries exactly Accept, Accept-Encoding and User-Agent: ${request.headers.keys}",
                setOf("Accept", "Accept-Encoding", "User-Agent"),
                request.headers.keys,
            )
            assertEquals("request $i asks for the binary stream", "application/octet-stream", request.headers["Accept"])
            assertEquals("request $i asks for no compression", "identity", request.headers["Accept-Encoding"])
            val agent = request.headers["User-Agent"] ?: ""
            assertTrue("request $i has a plain product string as User-Agent: '$agent'", agent.isNotBlank() && agent.length <= 100)
            assertTrue("the User-Agent is printable ASCII", agent.all { it.code in 32..126 })
        }
    }

    @Test
    fun `the first request goes to the address of the entry itself`() {
        val first = testEntry(url = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/111")
        val second = testEntry(id = "other", url = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/222")
        val openerA = FakeOpener(serve(okBytes(payload)))
        attemptModel(fetcherOf(openerA), first, staging).fetchedFile("first entry")
        val openerB = FakeOpener(serve(okBytes(payload)))
        attemptModel(fetcherOf(openerB), second, staging).fetchedFile("second entry")
        assertEquals(listOf(first.url), openerA.requests.map { it.url })
        assertEquals(listOf(second.url), openerB.requests.map { it.url })
    }

    @Test
    fun `a model id that is not a safe name makes no request and fails`() {
        val unsafe = listOf("../evil", "a/b", ".hidden", "", "..", "a b", "x".repeat(65), "a\\b", "a:b")
        for (id in unsafe) {
            val opener = FakeOpener(serve(reply(body = ThrowIfRead("body for id '$id'"))))
            val reason = attemptModel(fetcherOf(opener), testEntry(id = id), staging).failedReason("unsafe id '$id'")
            assertEquals("model id not allowed", reason)
            assertEquals("no request for unsafe id '$id'", 0, opener.requests.size)
        }
        assertEquals("nothing was written for any unsafe id", emptyList<String>(), stagedNames(staging))
        assertEquals("nothing was written outside staging", listOf("staging"), stagedNames(tmp.root))
    }

    @Test
    fun `a safe name at the length limit and with dots dashes and underscores is served`() {
        for (id in listOf("x".repeat(64), "a.b-c_d")) {
            val opener = FakeOpener(serve(okBytes(payload)))
            val file = attemptModel(fetcherOf(opener), testEntry(id = id), staging).fetchedFile("safe id '$id'")
            assertEquals("$id.download", file.name)
            assertEquals(1, opener.requests.size)
        }
    }

    @Test
    fun `a stale file from an earlier attempt is replaced and not appended to`() {
        val stale = File(staging, "tiny.download")
        stale.writeBytes(ByteArray(9000) { 9 })
        val outputs = RecordingOutputs(append = true)
        val opener = FakeOpener(serve(okBytes(payload)))
        val file = attemptModel(fetcherOf(opener, openOutput = outputs::open), testEntry(), staging).fetchedFile("with a stale file")
        assertArrayEquals("only the new bytes are in the file", payload, file.readBytes())
        assertEquals("the stale file was reopened under the same name", listOf(stale.name), outputs.files.map { it.name })
    }

    @Test
    fun `an install through the model installer with the fake opener ends Installed`() {
        val store = LocalModelStore(tmp.newFolder("models"))
        // The served model is a real archive: a successful install unpacks it.
        val archive = TarFixtures.tinyArchive()
        val digest = httpTestSha256Hex(archive)
        val entry = testEntry(sha256 = digest)
        val opener = FakeOpener(serve(okBytes(httpTestChecksumList(digest))), serve(okBytes(archive)))
        val result = ModelInstaller(store, fetcherOf(opener)).install(entry)
        assertEquals(ModelInstaller.InstallResult.Installed("tiny", digest), result)
        assertTrue("the model is installed", store.isInstalled("tiny"))
        assertArrayEquals("the installed archive holds the served bytes", archive, store.archiveFile("tiny").readBytes())
        assertEquals(
            "the checksum list was asked for first, then the model",
            listOf(DownloadLimits().checksumsUrl, entry.url),
            opener.requests.map { it.url },
        )
        assertFalse("the partial download is gone from staging", File(store.stagingDirectory(), "tiny.download").exists())
    }

    @Test
    fun `a body spread over many reads is staged byte for byte`() {
        val big = ByteArray(200_000) { (it % 253).toByte() }
        val opener = FakeOpener(serve(reply(body = BytesStream(big, chunk = 777), contentLength = big.size.toLong())))
        val file = attemptModel(fetcherOf(opener), testEntry(), staging).fetchedFile("chunked body")
        assertArrayEquals(big, file.readBytes())
    }

    @Test
    fun `the staged file is complete and closed when the fetch returns`() {
        val outputs = RecordingOutputs()
        val opener = FakeOpener(serve(okBytes(payload)))
        attemptModel(fetcherOf(opener, openOutput = outputs::open), testEntry(), staging).fetchedFile("recorded output")
        assertEquals("every byte went to the output", payload.size.toLong(), outputs.bytesWritten)
        assertTrue("the output was closed", outputs.closes > 0)
    }

    @Test
    fun `the checksum list already in staging is left alone by a model fetch`() {
        val other = File(staging, "checksums.txt")
        FileOutputStream(other).use { it.write(1) }
        val opener = FakeOpener(serve(okBytes(payload)))
        attemptModel(fetcherOf(opener), testEntry(), staging).fetchedFile("with a neighbour")
        assertTrue("the checksum list is not touched", other.exists())
    }
}
