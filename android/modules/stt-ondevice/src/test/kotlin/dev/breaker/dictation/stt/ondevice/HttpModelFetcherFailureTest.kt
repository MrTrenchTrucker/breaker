package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** How the real fetcher reports status codes, network errors, disk errors and surprises, and what it closes. */
class HttpModelFetcherFailureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var staging: File

    /** An Error that no fetcher may swallow. */
    private class MarkerError : Error("marker")

    @Before
    fun setUp() {
        staging = tmp.newFolder("staging")
    }

    private fun model(step: Step, outputs: RecordingOutputs = RecordingOutputs()): Attempt =
        attemptModel(fetcherOf(FakeOpener(step), openOutput = outputs::open), testEntry(), staging)

    private fun checksums(step: Step): Attempt = attemptChecksums(fetcherOf(FakeOpener(step)), staging)

    private fun noPartial() = assertEquals("no file is left in staging", emptyList<String>(), stagedNames(staging))

    @Test
    fun `each non-200 status is a failure naming the status`() {
        for (status in listOf(201, 204, 206, 400, 401, 403, 404, 429, 500, 503)) {
            val body = CloseCounter(BytesStream("error text".toByteArray()))
            val opener = FakeOpener(serve(reply(status, body)))
            val reason = attemptModel(fetcherOf(opener), testEntry(), staging).failedReason("model status $status")
            assertEquals("http $status", reason)
            assertEquals("no second request after status $status", 1, opener.requests.size)
            assertEquals("http $status", checksums(serve(reply(status))).failedReason("checksum status $status"))
        }
        noPartial()
    }

    @Test
    fun `each network exception from the opener is a failure result and never a throw`() {
        val secretMessage = "failed https://host.example/x?sig=${HttpFixtures.SECRET}"
        val cases: List<Pair<IOException, String>> = listOf(
            UnknownHostException(secretMessage) to "network: UnknownHostException",
            ConnectException(secretMessage) to "network: ConnectException",
            NoRouteToHostException(secretMessage) to "network: NoRouteToHostException",
            IOException(secretMessage) to "network: IOException",
            SocketTimeoutException(secretMessage) to "timed out",
            SSLHandshakeException(secretMessage) to "tls",
            SSLPeerUnverifiedException(secretMessage) to "tls",
            SSLException(secretMessage) to "tls",
        )
        for ((error, expected) in cases) {
            val name = error.javaClass.simpleName
            val fromModel = model(failWith(error)).failedReason("$name from a model request")
            val fromList = checksums(failWith(error)).failedReason("$name from a checksum request")
            assertEquals("$name from a model request", expected, fromModel)
            assertEquals("$name from a checksum request", expected, fromList)
            assertFalse("$name: the message leaked into the reason", fromModel.contains(HttpFixtures.SECRET) || fromModel.contains("://"))
        }
        noPartial()
    }

    @Test
    fun `a read that fails midway is a failure and the partial file is deleted`() {
        val cases = listOf(
            IOException("cut") to "network: IOException",
            SocketTimeoutException("slow") to "timed out",
            SSLException("broken") to "tls",
        )
        for ((error, expected) in cases) {
            val body = FailingStream(afterBytes = 5000, error = error)
            val outputs = RecordingOutputs()
            val outcome = model(serve(reply(body = body, contentLength = 9000)), outputs)
            assertEquals("${error.javaClass.simpleName} midway", expected, outcome.failedReason("${error.javaClass.simpleName} midway"))
            assertEquals("control: a partial file was started", 1, outputs.files.size)
            assertFalse("the partial file is gone", outputs.files[0].exists())
            assertTrue("the body was closed", body.closes > 0)
            noPartial()
        }
    }

    @Test
    fun `a write that fails is thrown as an IOException and the partial file is deleted`() {
        for (fault in OutputFault.values()) {
            var made: FailingOutput? = null
            val body = SizedStream(200_000)
            val opener = FakeOpener(serve(reply(body = body)))
            val open: (File) -> java.io.OutputStream = { file -> FailingOutput(file, 100_000, fault).also { made = it } }
            val outcome = attemptModel(fetcherOf(opener, openOutput = open), testEntry(), staging)
            outcome.thrownIo("a $fault failure on a model write")
            val output = made ?: throw AssertionError("the output was never opened for $fault")
            assertTrue("control: the partial file existed when the $fault failed", output.fileExistedWhenFailing)
            assertTrue("the body was closed", body.closes > 0)
            noPartial()
        }
    }

    @Test
    fun `a write that fails while staging the checksum list is thrown and leaves no file`() {
        val opener = FakeOpener(serve(okBytes(ByteArray(5000) { 1 })))
        val open: (File) -> java.io.OutputStream = { file -> FailingOutput(file, 100, OutputFault.WRITE) }
        attemptChecksums(fetcherOf(opener, openOutput = open), staging).thrownIo("a write failure on the checksum list")
        noPartial()
    }

    @Test
    fun `an unexpected runtime exception is a failure result and never a throw`() {
        val errors = listOf(RuntimeException("surprise"), IllegalStateException("odd"), IllegalArgumentException("bad"), SecurityException("no"))
        for (error in errors) {
            val name = error.javaClass.simpleName
            assertEquals("unexpected", model(failWith(error)).failedReason("$name from the opener"))
            assertEquals("unexpected", checksums(failWith(error)).failedReason("$name from the opener"))
        }
        val outputs = RecordingOutputs()
        val body = FailingStream(afterBytes = 3000, error = IllegalStateException("odd read"))
        val outcome = model(serve(reply(body = body, contentLength = 9000)), outputs)
        assertEquals("unexpected", outcome.failedReason("a runtime exception from the body"))
        assertTrue("the body was closed", body.closes > 0)
        noPartial()
    }

    @Test
    fun `an Error from the opener is not swallowed`() {
        try {
            attemptModel(fetcherOf(FakeOpener(failWith(MarkerError()))), testEntry(), staging)
        } catch (expected: MarkerError) {
            return
        }
        throw AssertionError("the Error thrown by the opener did not reach the caller")
    }

    @Test
    fun `an Error from the body is not swallowed`() {
        val body = ThrowIfRead("a body")
        try {
            attemptModel(fetcherOf(FakeOpener(serve(reply(body = body)))), testEntry(), staging)
        } catch (expected: AssertionError) {
            assertEquals("a body was read", expected.message)
            return
        }
        throw AssertionError("the Error thrown by the body did not reach the caller")
    }

    private fun closedAfter(
        name: String,
        body: ProbeStream,
        status: Int = 200,
        contentLength: Long = -1L,
        contentType: String? = "application/octet-stream",
        cancelled: () -> Boolean = { false },
        openOutput: (File) -> java.io.OutputStream = { java.io.FileOutputStream(it) },
    ) {
        val opener = FakeOpener(serve(reply(status, body, contentLength = contentLength, contentType = contentType)))
        attemptModel(fetcherOf(opener, cancelled = cancelled, openOutput = openOutput), testEntry(), staging)
        assertTrue("$name: the reply body was closed", body.closes > 0)
    }

    @Test
    fun `the reply body is closed on success failure cap and cancel`() {
        val cap = HttpFixtures.CAP_1MB
        val cancel = CancelFlag()
        closedAfter("success", CloseCounter(SizedStream(100)))
        closedAfter("status 404", CloseCounter(SizedStream(10)), status = 404)
        closedAfter("cap breach", SizedStream(cap + 1))
        closedAfter("declared above the cap", ThrowIfRead("body"), contentLength = cap + 1)
        closedAfter("cancel during the copy", HookStream(100_000) { if (it == 2) cancel.on = true }, cancelled = cancel.check)
        closedAfter("truncated", SizedStream(10), contentLength = 20)
        closedAfter("empty", SizedStream(0))
        closedAfter("json content type", SizedStream(10), contentType = "application/json")
        closedAfter("read failure", FailingStream(10, IOException("cut")), contentLength = 99)
        closedAfter("write failure", SizedStream(5000), openOutput = { FailingOutput(it, 10, OutputFault.WRITE) })
    }

    private fun redirectScenario(name: String, first: CloseCounter): FakeOpener {
        val release = HttpFixtures.RELEASE_URL
        return when (name) {
            "followed" -> FakeOpener(serve(reply(302, first, location = release)), serve(okBytes(ByteArray(10) { 1 })))
            "refused host" -> FakeOpener(serve(reply(302, first, location = "https://evil.example.com/x")))
            "no location" -> FakeOpener(serve(reply(302, first)))
            else -> FakeOpener(serve(reply(302, first, location = release)), failWith(IOException("down")))
        }
    }

    @Test
    fun `every redirect reply body is closed whatever happens next`() {
        for (name in listOf("followed", "refused host", "no location", "next hop fails")) {
            val first = CloseCounter(BytesStream(ByteArray(0)))
            attemptModel(fetcherOf(redirectScenario(name, first)), testEntry(), staging)
            assertTrue("$name: the redirect reply was closed", first.closes > 0)
        }
        val bodies = List(2) { CloseCounter(BytesStream(ByteArray(0))) }
        val opener = FakeOpener(bodies.map { serve(reply(302, it, location = HttpFixtures.RELEASE_URL)) })
        attemptModel(fetcherOf(opener, DownloadLimits(maxRedirects = 1)), testEntry(), staging)
        assertTrue("too many redirects: every redirect reply was closed", bodies.all { it.closes > 0 })
    }
}
