package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.PullSink
import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests of the pull trigger seam: QueuedSyncService carries PullTrigger and runs it after a
 * finished drain, under the same drain mutex. The database is InMemorySyncQueueDatabase, so no
 * Android SQLite is needed and the tests run on a plain JVM.
 */
class PullTriggerTest {

    private val clientA = "00000001-0000-4000-8000-000000000001"
    private val clientB = "00000002-0000-4000-8000-000000000002"
    private val clientD = "00000003-0000-4000-8000-000000000003"
    private val storedAt = "2023-11-14T22:13:20.000Z"
    private val deleteStoredAt = "2023-11-14T22:13:20.123Z"
    private val deletedAtMs = 1_700_000_000_123L

    private fun body(text: String, createdAtMs: Long): SyncBody =
        SyncBody(text, SyncSource.SERVER, "small.en", 1_200, createdAtMs)

    private fun encryptedOf(body: SyncBody): String = SyncBodyCodec.encodePlain(body)

    private fun liveRecord(clientId: String, change: Long, encrypted: String, storedAt: String): TranscriptionRecord =
        TranscriptionRecord(clientId, change, false, 0, encrypted, storedAt)

    private fun deleteRecord(clientId: String, change: Long, storedAt: String): TranscriptionRecord =
        TranscriptionRecord(clientId, change, true, null, null, storedAt)

    private fun page(items: List<TranscriptionRecord>, next: String, more: Boolean): String {
        val itemStrings = items.map { r ->
            when (r.deleted) {
                true -> "{\"client_id\":\"${r.clientId}\",\"change\":${r.change},\"deleted\":true,\"stored_at\":\"${r.storedAt}\"}"
                false -> "{\"client_id\":\"${r.clientId}\",\"change\":${r.change},\"deleted\":false,\"key_version\":0,\"encrypted\":\"${r.encrypted}\",\"stored_at\":\"${r.storedAt}\"}"
            }
        }
        return "{\"items\":[${itemStrings.joinToString(",")}],\"next\":\"$next\",\"more\":$more}"
    }

    private fun service(
        database: SyncQueueDatabase = InMemorySyncQueueDatabase(),
        uploader: ScriptedUploader = ScriptedUploader(),
        transport: PullTransport? = null,
        sink: PullSink? = null,
    ): QueuedSyncService =
        QueuedSyncService(
            database = database,
            uploader = uploader,
            probe = ScriptedProbe(reachable = true),
            pullTransport = transport,
            pullSink = sink,
        )

    // ---- test 1: queued id not overwritten by a pulled row ----

    /** A pulled live row whose id waits in the queue is skipped; the local edit stays queued. */
    @Test
    fun `a pulled row skips a queued id and never overwrites it`() {
        val encA = encryptedOf(body("theirs", 1_700_000_000_000L))
        val encB = encryptedOf(body("theirs too", 1_700_000_000_000L))
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(
                liveRecord(clientA, 1, encA, storedAt),
                liveRecord(clientB, 2, encB, storedAt),
            ), "n1", false)),
            PullTransportResult.Answered(200, page(emptyList(), "n1", false)),
        ))
        val sink = RecordingPullSink()
        val database = InMemorySyncQueueDatabase()
        // clientA waits in the queue with a source the drain cannot push, so the drain skips it
        // and it is still queued when the pull runs.
        database.insertIfAbsent(clientA, "kept locally", "bogus", "small.en", 1_200, 1_700_000_000_000L)
        val subject = service(database = database, transport = transport, sink = sink)

        val report = subject.pushPending()

        assertEquals("the queued row is not pushable, so the drain skips it and nothing uploads",
            SyncReport(0, 1, 0), report)
        assertTrue("the trigger is wired", subject.pullTrigger != null)
        // The pulled row whose id waits in the queue is skipped; the local edit stays queued.
        assertEquals("the pulled row with the queued id is not stored", listOf(clientB), sink.rowIds())
        assertEquals("the queue still holds the local id", listOf(clientA), database.queuedIds())
        // Two pull runs: the list from scratch, then the follow-up page from its cursor.
        assertEquals("two pull requests", 2, transport.requests.size)
        assertEquals("the first pull sends no since", "", transport.requests[0].query())
        assertEquals("the follow-up sends the list cursor", "since=n1", transport.requests[1].query())
        assertEquals("the cursor moved forward", "n1", database.pullCursor())
    }

    // ---- test 2: a pulled delete is applied through the trigger ----

    /** A pulled delete record reaches applyRemoteDelete with the exact deleted_at milliseconds. */
    @Test
    fun `a pulled delete is applied through the trigger`() {
        val database = InMemorySyncQueueDatabase()
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(
                deleteRecord(clientD, 4, deleteStoredAt),
            ), "1-1", false)),
            PullTransportResult.Answered(200, page(emptyList(), "1-1", false)),
        ))
        val sink = RecordingPullSink()
        val subject = service(database = database, transport = transport, sink = sink)

        val report = subject.pushPending()

        assertEquals("an empty drain still returns a clean report", SyncReport(0, 0, 0), report)
        assertEquals("the delete reaches the sink exactly once",
            listOf("$clientD@$deletedAtMs"), sink.deleteCalls())
        assertEquals("the list cursor was saved", "1-1", database.pullCursor())
        assertEquals("two pull runs: the list and its follow-up", 2, transport.requests.size)
    }

    // ---- test 3: a pull failure leaves the cursor unsaved and the next drain retries ----

    /** An unreachable pull saves no cursor; a subsequent drain pulls again and saves it. */
    @Test
    fun `a pull failure leaves the cursor unsaved and the next drain retries`() {
        val database = InMemorySyncQueueDatabase()
        val encA = encryptedOf(body("hello", 1_700_000_000_000L))
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Unreachable,
            PullTransportResult.Answered(200, page(listOf(
                liveRecord(clientA, 1, encA, storedAt),
            ), "n1", false)),
            PullTransportResult.Answered(200, page(emptyList(), "n1", false)),
        ))
        val sink = RecordingPullSink()
        val subject = service(database = database, transport = transport, sink = sink)

        val report1 = subject.pushPending()
        assertEquals("the failing pull changes nothing in the report", SyncReport(0, 0, 0), report1)
        assertNull("the cursor is not saved after a failing pull", database.pullCursor())
        assertEquals("the first pull sent no since", "", transport.requests[0].query())

        val report2 = subject.pushPending()
        assertEquals("the retrying drain returns a clean report", SyncReport(0, 0, 0), report2)
        assertEquals("the retry pulled from scratch again (cursor was never saved)", "",
            transport.requests[1].query())
        assertEquals("the cursor is saved on the successful retry", "n1", database.pullCursor())
        // The failed pull, then the retry's list from scratch, then the retry's follow-up page.
        assertEquals("the pull ran again after the failure (failed pull, retry list, retry follow-up)",
            3, transport.requests.size)
        assertEquals("the retry's follow-up sends the list cursor", "since=n1", transport.requests[2].query())
        assertEquals("the retry's row was applied", listOf(clientA), sink.rowIds())
    }

    // ---- test 4: no second sync runs while one is in flight ----

    /** A second pushPending while one drain (and its pull) is in flight returns at once. */
    @Test
    fun `no second drain runs while one is in flight`() {
        val database = InMemorySyncQueueDatabase()
        database.insertIfAbsent(clientA, "going out", "local", "small.en", 1_200, 1_700_000_000_000L)
        val latch = CountDownLatch(1)
        val fetches = java.util.concurrent.atomic.AtomicInteger(0)
        val transport = object : PullTransport {
            override fun fetch(request: PullRequest): PullTransportResult {
                fetches.incrementAndGet()
                latch.await()
                return PullTransportResult.Answered(200, page(emptyList(), "n1", false))
            }
        }
        val sink = RecordingPullSink()
        val subject = service(database = database, transport = transport, sink = sink)

        val first = java.util.concurrent.atomic.AtomicReference<SyncReport?>()
        val second = java.util.concurrent.atomic.AtomicReference<SyncReport?>()
        val threadA = Thread { first.set(subject.pushPending()) }
        val threadB = Thread { second.set(subject.pushPending()) }
        threadA.start()
        Thread.sleep(300) // let A take the drain and park inside its pull
        threadB.start()
        threadB.join(10_000)
        latch.countDown()
        threadA.join(10_000)

        val a = first.get()
        val b = second.get()
        assertEquals("one call drains and pulls, the other returns at once",
            setOf(SyncReport(1, 0), SyncReport(0, 0)), setOf(a!!, b!!))
        // The pull ran under the same drain mutex: one drain pulled twice (list + follow-up),
        // and no second drain ever started.
        assertEquals("the pull ran under the shared drain mutex, not beside it", 2, fetches.get())
        assertEquals("the cursor was saved", "n1", database.pullCursor())
        assertEquals("the pushed row was removed", emptyList<String>(), database.queuedIds())
    }

    // ---- test 5: syncOn false means no pull ----

    /** create with no pull halves wires no trigger; a drain then touches nothing pull-related. */
    @Test
    fun `no pull halves means no pull trigger is wired`() {
        val database = InMemorySyncQueueDatabase()
        val subject = service(database = database)
        assertNull("create with no pull args wires no trigger", subject.pullTrigger)

        subject.pushPending()
        assertNull("with no trigger the cursor stays untouched", database.pullCursor())
    }

    /** One pull half without the other still wires no trigger. */
    @Test
    fun `one pull half without the other wires no trigger`() {
        val database = InMemorySyncQueueDatabase()
        val transportOnly = service(database = database, transport = ScriptedPullTransport(emptyList()))
        assertNull("transport without sink wires no trigger", transportOnly.pullTrigger)
        val sinkOnly = service(database = database, sink = RecordingPullSink())
        assertNull("sink without transport wires no trigger", sinkOnly.pullTrigger)
    }

    // ---- test 6: enqueue never waits on a pull ----

    /** enqueue returns at once and never runs the pull, even when a pull would block forever. */
    @Test
    fun `enqueue never waits on the pull`() {
        val database = InMemorySyncQueueDatabase()
        val latch = CountDownLatch(1)
        val pulled = AtomicBoolean(false)
        val transport = object : PullTransport {
            override fun fetch(request: PullRequest): PullTransportResult {
                pulled.set(true)
                // Bounded so a break that makes enqueue wait on the pull fails loudly instead of hanging.
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return PullTransportResult.Unreachable
            }
        }
        val sink = RecordingPullSink()
        val subject = service(database = database, transport = transport, sink = sink)

        val start = System.currentTimeMillis()
        subject.enqueue(Transcription(
            id = clientId(1), text = "queued locally", source = TranscriptionSource.LOCAL,
            model = "small.en", durationMs = 1_200, createdAt = 1_700_000_000_000L,
        ))
        val elapsed = System.currentTimeMillis() - start

        assertTrue("enqueue must not wait on the pull (took ${elapsed} ms)", elapsed < 2_000)
        assertFalse("enqueue must not run the pull", pulled.get())
        assertTrue("the row is queued", database.isQueued(clientId(1)))
        latch.countDown()
    }

    // ---- test 7: an unreachable drain returns before the pull ----

    /** A drain that stops on UploadOutcome.UNREACHABLE returns before the pull runs. */
    @Test
    fun `an unreachable drain returns before the pull`() {
        val database = InMemorySyncQueueDatabase()
        database.insertIfAbsent(clientA, "going out", "local", "small.en", 1_200, 1_700_000_000_000L)
        val uploader = ScriptedUploader().also { it.script(UploadOutcome.UNREACHABLE) }
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(emptyList(), "n1", false)),
            PullTransportResult.Answered(200, page(emptyList(), "n1", false)),
        ))
        val sink = RecordingPullSink()
        val subject = service(database = database, uploader = uploader, transport = transport, sink = sink)

        val report = subject.pushPending()

        assertEquals("the unreachable drain stops and reports nothing failed (unlike a FAILED)", SyncReport(0, 0), report)
        assertEquals("the pull transport was never called", 0, transport.requests.size)
        assertNull("the cursor stays untouched", database.pullCursor())
    }

    // ---- test 8: a failed drain returns before the pull ----

    /** A drain that stops on UploadOutcome.FAILED returns before the pull runs. */
    @Test
    fun `a failed drain returns before the pull`() {
        val database = InMemorySyncQueueDatabase()
        database.insertIfAbsent(clientA, "going out", "local", "small.en", 1_200, 1_700_000_000_000L)
        val uploader = ScriptedUploader().also { it.script(UploadOutcome.FAILED) }
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(emptyList(), "n1", false)),
            PullTransportResult.Answered(200, page(emptyList(), "n1", false)),
        ))
        val sink = RecordingPullSink()
        val subject = service(database = database, uploader = uploader, transport = transport, sink = sink)

        val report = subject.pushPending()

        assertEquals("the failed drain stops with one failed row", SyncReport(0, 1), report)
        assertEquals("the pull transport was never called", 0, transport.requests.size)
        assertNull("the cursor stays untouched", database.pullCursor())
    }

    // ---- test 9: a throwing pull sink never crashes the drain or saves a cursor ----

    /** A pull sink that throws inside the cycle is swallowed: the report is unchanged, no cursor saved. */
    @Test
    fun `a pull sink that throws leaves the report and the cursor unchanged`() {
        val database = InMemorySyncQueueDatabase()
        val encA = encryptedOf(body("hello", 1_700_000_000_000L))
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(
                liveRecord(clientA, 1, encA, storedAt),
            ), "n1", false)),
            PullTransportResult.Answered(200, page(emptyList(), "n1", false)),
        ))
        val sink = RecordingPullSink().also { it.throwOn = IllegalStateException("boom in the sink") }
        val subject = service(database = database, transport = transport, sink = sink)

        val report = subject.pushPending()

        assertEquals("the failing pull changes neither the report nor the drain outcome",
            SyncReport(0, 0, 0), report)
        assertNull("a failing pull saves no cursor", database.pullCursor())
        assertEquals("the pull request was still sent before the sink threw", 1, transport.requests.size)
    }
}
