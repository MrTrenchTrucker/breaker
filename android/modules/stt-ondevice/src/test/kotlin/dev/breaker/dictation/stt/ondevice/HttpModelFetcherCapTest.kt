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

/** The size caps of the real fetcher: the model cap, declared and running lengths. */
class HttpModelFetcherCapTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var staging: File

    private val cap = HttpFixtures.CAP_1MB

    @Before
    fun setUp() {
        staging = tmp.newFolder("staging")
    }

    private fun model(reply: HttpReply, limits: DownloadLimits = DownloadLimits(), outputs: RecordingOutputs = RecordingOutputs()): Attempt =
        attemptModel(fetcherOf(FakeOpener(serve(reply)), limits, openOutput = outputs::open), testEntry(), staging)

    private fun noPartial() = assertEquals("no file is left in staging", emptyList<String>(), stagedNames(staging))

    @Test
    fun `a body longer than the cap is refused and the partial file is deleted`() {
        val body = CloseCounter(SizedStream(cap + 1))
        val outputs = RecordingOutputs()
        val reason = model(reply(body = body), outputs = outputs).failedReason("cap plus one byte, length not declared")
        assertEquals("too large", reason)
        assertTrue("control: a partial file was started", outputs.files.size == 1 && outputs.bytesWritten > 0)
        assertFalse("the partial file is gone", outputs.files[0].exists())
        noPartial()
    }

    @Test
    fun `a body that never ends is cut off shortly after the cap`() {
        val body = EndlessStream()
        val reason = model(reply(body = body)).failedReason("endless body")
        assertEquals("too large", reason)
        assertTrue("the copy stopped within one MiB of the cap, served ${body.bytesServed}", body.bytesServed <= cap + HttpFixtures.MIB)
        assertTrue("the body was closed", body.closes > 0)
        noPartial()
    }

    @Test
    fun `a declared length above the cap fails before one byte is read`() {
        val body = ThrowIfRead("a body declared above the cap")
        val opener = FakeOpener(serve(reply(body = body, contentLength = cap + 1)))
        val reason = attemptModel(fetcherOf(opener), testEntry(), staging).failedReason("declared cap plus one")
        assertEquals("too large", reason)
        assertEquals("no read happened", 0, body.reads)
        assertTrue("the body was closed", body.closes > 0)
        noPartial()
    }

    @Test
    fun `a body of exactly the cap is accepted whether or not its length was declared`() {
        for (declared in listOf(cap, -1L)) {
            val file = model(reply(body = SizedStream(cap), contentLength = declared)).fetchedFile("exactly the cap, declared=$declared")
            assertEquals("all bytes are staged, declared=$declared", cap, file.length())
        }
    }

    @Test
    fun `one byte more than the cap is refused whether or not the length was declared`() {
        assertEquals("too large", model(reply(body = SizedStream(cap + 1))).failedReason("undeclared cap plus one"))
        noPartial()
        assertEquals(
            "too large",
            model(reply(body = SizedStream(cap + 1), contentLength = cap)).failedReason("body longer than its declared cap"),
        )
        noPartial()
    }

    @Test
    fun `the cap comes from the entry size plus one MiB`() {
        val limits = DownloadLimits()
        assertEquals(3_145_728L, limits.modelCapBytes(testEntry(sizeMb = 2)))
        assertEquals(1_048_576L, limits.modelCapBytes(testEntry(sizeMb = 0)))
        assertEquals(367_001_600L, limits.modelCapBytes(testEntry(sizeMb = 349)))
        assertTrue("the real small model fits its cap", 365_748_162L <= limits.modelCapBytes(testEntry(sizeMb = 349)))
        val three = 3_145_728L
        val entry = testEntry(sizeMb = 2)
        val ok = attemptModel(fetcherOf(FakeOpener(serve(reply(body = SizedStream(three))))), entry, staging)
        assertEquals("three MiB fits a two MiB entry", three, ok.fetchedFile("cap of a 2 MiB entry").length())
        val over = attemptModel(fetcherOf(FakeOpener(serve(reply(body = SizedStream(three + 1))))), entry, staging)
        assertEquals("too large", over.failedReason("one byte over the cap of a 2 MiB entry"))
    }

    @Test
    fun `a body shorter than its declared length fails and leaves no file`() {
        val reason = model(reply(body = SizedStream(60), contentLength = 100)).failedReason("60 of 100 bytes")
        assertEquals("truncated", reason)
        noPartial()
    }

    @Test
    fun `a body longer than its declared length fails and leaves no file`() {
        val reason = model(reply(body = SizedStream(100), contentLength = 60)).failedReason("100 of 60 bytes")
        assertEquals("truncated", reason)
        noPartial()
    }

    @Test
    fun `an empty body fails and leaves no file`() {
        for (declared in listOf(-1L, 0L)) {
            assertEquals("empty", model(reply(body = SizedStream(0), contentLength = declared)).failedReason("empty body, declared=$declared"))
            noPartial()
        }
    }

    @Test
    fun `a body with no declared length is accepted and staged whole`() {
        val bytes = ByteArray(10) { (it + 1).toByte() }
        val file = model(okBytes(bytes, declare = false)).fetchedFile("short undeclared body")
        assertArrayEquals(bytes, file.readBytes())
        val many = ByteArray(200_000) { (it % 251).toByte() }
        val big = model(reply(body = BytesStream(many, chunk = 4096))).fetchedFile("long undeclared body")
        assertArrayEquals(many, big.readBytes())
    }
}
