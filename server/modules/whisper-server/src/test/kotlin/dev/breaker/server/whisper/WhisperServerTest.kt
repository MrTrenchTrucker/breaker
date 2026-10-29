package dev.breaker.server.whisper

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.http.TranscriptionApi
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStatus
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.MutableClock
import dev.breaker.server.whisper.worker.ForwardOutcome
import dev.breaker.server.whisper.worker.QueueWorker
import dev.breaker.server.whisper.worker.TranscriptionForwarder
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Start-stop tests for the whisper-server on an ephemeral port on 127.0.0.1.
 */
internal class WhisperServerTest {

    private val opened = ArrayList<TempDatabase>()
    private val servers = ArrayList<EmbeddedServer<*, *>>()

    @After
    fun tearDown() {
        for (server in servers) {
            try {
                server.stop(1000, 5000)
            } catch (_: Exception) {
                // already stopped
            }
        }
        for (temp in opened) {
            temp.close()
        }
    }

    private data class StoreAndDb(val store: JobStore, val db: TempDatabase)

    private fun openStore(): StoreAndDb {
        val temp = TestDatabases.openTemp()
        opened.add(temp)
        val store = JobFixtures.store(temp, MutableClock(JobFixtures.START))
        return StoreAndDb(store, temp)
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    @Test
    fun `start stop on ephemeral port serves one real request`(): Unit = runBlocking {
        val store = openStore().store
        val worker = QueueWorker(store, TranscriptionForwarder { ForwardOutcome.Success("{\"text\":\"hello\"}") })
        val api = TranscriptionApi(store, worker::wake)

        val port = findFreePort()
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            api.install(this)
        }
        servers.add(server)
        server.start(wait = false)

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        val boundary = "----test${System.nanoTime()}"
        val audio = JobFixtures.audio(1)
        val body = multipartBytes(boundary, audio, "m")

        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/v1/audio/transcriptions"))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(202, response.statusCode())
        assertTrue(response.body().contains("\"job_id\":1"))
        assertEquals(JobStatus.QUEUED, store.get(1)?.status)

        server.stop(1000, 5000)
    }

    @Test
    fun `shutdown with stuck worker swallows the timeout and touches nothing`(): Unit = runBlocking {
        val (store, db) = openStore()
        val latch = java.util.concurrent.CountDownLatch(1)
        val forwarder = TranscriptionForwarder { _ ->
            latch.await()
            ForwardOutcome.Success("{\"text\":\"stuck\"}")
        }
        val worker = QueueWorker(store, forwarder)
        val api = TranscriptionApi(store, worker::wake)

        val port = findFreePort()
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            api.install(this)
        }
        servers.add(server)
        server.start(wait = false)

        val jobId = store.enqueue(1L, JobFixtures.audio(1))
        val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val workerJob = workerScope.launch {
            worker.run()
        }

        withTimeout(5_000) {
            while (store.get(jobId)?.status != JobStatus.PROCESSING) {
                delay(50)
            }
        }

        // Record the call order AND really stop the server. The server is started inside
        // runBlocking, so its lazy engine job is a child of the runBlocking job: an
        // unstopped server keeps runBlocking waiting forever. @After cannot rescue this -
        // the test method never returns, so the teardown never runs.
        val stopCalls = AtomicInteger(0)
        val stop: () -> Unit = {
            stopCalls.incrementAndGet()
            server.stop(1000, 5000)
        }
        val shutdown = WhisperShutdown(stop, workerJob, store, db.db, joinTimeoutMs = 200L)

        val started = System.nanoTime()
        try {
            withTimeout(2_000) {
                shutdown.run()
            }
        } catch (_: TimeoutCancellationException) {
            // The join inside shutdown.run() timed out; the worker is stuck. The
            // assertions below prove the shutdown touched nothing.
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        try {
            assertEquals("stopServer must be called exactly once", 1, stopCalls.get())
            assertTrue("run() took ${elapsedMs}ms, expected < 2200ms", elapsedMs < 2_200)
            assertEquals("job must still be PROCESSING", JobStatus.PROCESSING, store.get(jobId)?.status)
            assertTrue("database must NOT be closed after a timed-out join", !db.db.isClosed())

            runBlocking { store.recoverInterrupted() }
            assertEquals("job must be QUEUED after fresh recover", JobStatus.QUEUED, store.get(jobId)?.status)
        } finally {
            latch.countDown()
            withTimeout(5_000) { workerJob.join() }
        }
    }

    @Test
    fun `shutdown with a cooperative worker recovers and closes`(): Unit = runBlocking {
        val (store, db) = openStore()
        val forwarder = TranscriptionForwarder { _ ->
            delay(60_000)
            ForwardOutcome.Success("{\"text\":\"x\"}")
        }
        val worker = QueueWorker(store, forwarder)
        val api = TranscriptionApi(store, worker::wake)

        val port = findFreePort()
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            api.install(this)
        }
        servers.add(server)
        server.start(wait = false)

        val jobId = store.enqueue(1L, JobFixtures.audio(1))
        val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val workerJob = workerScope.launch {
            worker.run()
        }

        withTimeout(5_000) {
            while (store.get(jobId)?.status != JobStatus.PROCESSING) {
                delay(50)
            }
        }

        val stopCalls = AtomicInteger(0)
        val stop: () -> Unit = {
            stopCalls.incrementAndGet()
            server.stop(1000, 5000)
        }
        val shutdown = WhisperShutdown(stop, workerJob, store, db.db, joinTimeoutMs = 5_000L)
        shutdown.run()

        assertEquals("stopServer must be called exactly once", 1, stopCalls.get())
        assertTrue("database must be closed after completed join", db.db.isClosed())

        // The old handle is closed; read the final status through a fresh handle on the same file.
        val fresh = TestDatabases.reopen(db)
        opened.add(fresh)
        val freshStore = JobFixtures.store(fresh, MutableClock(JobFixtures.START))
        assertEquals("job must be QUEUED after completed join", JobStatus.QUEUED, freshStore.get(jobId)?.status)
    }

    @Test
    fun `shutdown with an idle worker recovers and closes`(): Unit = runBlocking {
        val (store, db) = openStore()
        val forwarder = TranscriptionForwarder { _ ->
            ForwardOutcome.Success("{\"text\":\"idle\"}")
        }
        val worker = QueueWorker(store, forwarder)
        val api = TranscriptionApi(store, worker::wake)

        val port = findFreePort()
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            api.install(this)
        }
        servers.add(server)
        server.start(wait = false)

        val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val workerJob = workerScope.launch {
            worker.run()
        }

        val stopCalls = AtomicInteger(0)
        val stop: () -> Unit = {
            stopCalls.incrementAndGet()
            server.stop(1000, 5000)
        }
        val shutdown = WhisperShutdown(stop, workerJob, store, db.db, joinTimeoutMs = 5_000L)
        shutdown.run()

        assertEquals("stopServer must be called exactly once", 1, stopCalls.get())
        assertTrue("database must be closed after idle shutdown", db.db.isClosed())
    }

    private fun multipartBytes(boundary: String, audio: ByteArray, model: String): ByteArray {
        val sb = StringBuilder()
        sb.append("--$boundary\r\n")
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
        sb.append("Content-Type: application/octet-stream\r\n\r\n")
        val header = sb.toString().toByteArray(Charsets.UTF_8)
        val footer = "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return header + audio + footer
    }
}
