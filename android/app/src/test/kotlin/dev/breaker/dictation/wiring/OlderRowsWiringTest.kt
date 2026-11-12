package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.core.port.OlderBound
import dev.breaker.dictation.core.port.OlderHistory
import dev.breaker.dictation.core.port.OlderResult
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.core.port.SyncService
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The older-rows wiring: olderRowsTransport builds the OlderHistory over the older-rows transport
 * and one addressed session per call; offered follows the session; a delete queues the delete and
 * then asks the worker to drain. SyncService, SyncWorker's background and scheduler, and the HTTP
 * transport are fakes: no Android class and no network. BreakerApp.kt never names the transport
 * class, so the not-wired gate test stays green.
 */
class OlderRowsWiringTest {

    companion object {
        private const val BASE = "https://server.test"
        private const val EMPTY_LAST_PAGE = "{\"items\":[],\"next\":\"1700000000000\",\"more\":false}"
    }

    private fun session(): AddressedSession =
        AddressedSession(BASE, AuthSession("u1", "user", "token-a", UserRole.USER, setOf(TokenScope.TRANSCRIBE)))

    private fun settingsStore(): SettingsStore = object : SettingsStore {
        override fun load() = AppSettings(serverUrl = BASE)
        override fun save(settings: AppSettings) {}
    }

    /** Records, in order, every delete queued and every drain that ran. */
    private class FakeSyncService : SyncService {
        val order = mutableListOf<String>()
        var enqueueDeleteThrows: Throwable? = null
        var report: SyncReport = SyncReport(0, 0)

        override fun enqueue(transcription: Transcription) {}

        override fun enqueueDelete(clientId: String) {
            enqueueDeleteThrows?.let { throw it }
            order += "enqueueDelete $clientId"
        }

        override fun pushPending(): SyncReport {
            order += "push"
            return report
        }
    }

    private class InlineBackground : Background {
        override fun submit(block: () -> Unit) {
            block()
        }
    }

    private class RecordedHandle : SyncScheduler.Handle {
        var cancelled: Boolean = false

        override fun cancel() {
            cancelled = true
        }
    }

    /** Arms nothing; keeps every backoff timer the worker arms so a test can see it cancelled. */
    private class RecordingScheduler : SyncScheduler {
        val handles = mutableListOf<RecordedHandle>()

        override fun runSoon(block: () -> Unit) = Unit

        override fun runAfter(delayMs: Long, block: () -> Unit): SyncScheduler.Handle {
            val handle = RecordedHandle()
            handles.add(handle)
            return handle
        }
    }

    /** Answers every request with one canned reply and keeps the requests. No network. */
    private class FakeTransport : HttpTransport {
        val requests = mutableListOf<CallRequest>()

        override fun send(request: CallRequest): BytesReply {
            requests.add(request)
            return BytesReply(200, EMPTY_LAST_PAGE.toByteArray(Charsets.UTF_8))
        }

        override fun open(request: CallRequest): dev.breaker.dictation.http.StreamReply =
            throw UnsupportedOperationException("open is not used by the older-rows wiring")
    }

    private fun built(
        syncOn: Boolean = true,
        service: FakeSyncService = FakeSyncService(),
        transport: FakeTransport = FakeTransport(),
        scheduler: SyncScheduler = RecordingScheduler(),
        supplier: () -> AddressedSession? = { session() },
    ): OlderHistory? =
        olderRowsTransport(
            syncOn = syncOn,
            settingsStore = settingsStore(),
            addressedSession = supplier,
            syncService = service,
            worker = SyncWorker(
                service = service,
                background = InlineBackground(),
                scheduler = scheduler,
                sessionGate = { session().session },
            ),
            transport = transport,
        )

    @Test
    fun `a build where sync is not allowed has no older rows at all`() {
        assertNull(built(syncOn = false))
    }

    @Test
    fun `offered is false with no session`() {
        assertFalse(built(supplier = { null })!!.available())
    }

    @Test
    fun `offered is true with a session`() {
        assertTrue(built()!!.available())
    }

    @Test
    fun `offered is read again on every available call`() {
        var live: AddressedSession? = session()
        val older = built(supplier = { live })!!
        assertTrue(older.available())
        live = null
        assertFalse(older.available())
    }

    @Test
    fun `a delete queues the delete and then asks the worker to drain`() {
        val service = FakeSyncService()
        val older = built(service = service)!!
        assertTrue(older.delete("row-7"))
        assertEquals(listOf("enqueueDelete row-7", "push"), service.order)
    }

    @Test
    fun `a throwing enqueueDelete answers false and the worker is not asked to drain`() {
        val service = FakeSyncService()
        service.enqueueDeleteThrows = IllegalStateException("the queue is wedged")
        val older = built(service = service)!!
        assertFalse(older.delete("row-8"))
        assertTrue(service.order.isEmpty())
    }

    @Test
    fun `an older read goes to the older route with the bound and the session token`() {
        val transport = FakeTransport()
        val older = built(transport = transport)!!
        val result = older.older(OlderBound(1_700_000_000_000L, 42L))
        assertSame(OlderResult.NoneOlder, result)
        val request = transport.requests.single()
        assertEquals("https://server.test/v1/transcriptions/older?older_than=1700000000000-42", request.url)
        assertEquals(listOf("Authorization" to "Bearer token-a"), request.headers)
    }

    @Test
    fun `a delete is a new save for the worker so it cancels an armed backoff and drains at once`() {
        val service = FakeSyncService()
        service.report = SyncReport(0, 1)
        val scheduler = RecordingScheduler()
        val older = built(service = service, scheduler = scheduler)!!
        older.delete("a")
        assertEquals(1, scheduler.handles.size)
        assertFalse(scheduler.handles[0].cancelled)
        older.delete("b")
        assertEquals(listOf("enqueueDelete a", "push", "enqueueDelete b", "push"), service.order)
        assertTrue(scheduler.handles[0].cancelled)
    }

    @Test
    fun `the older read uses the connect read and reply limits of the pull`() {
        val transport = FakeTransport()
        built(transport = transport)!!.older(OlderBound(1_700_000_000_000L, null))
        val limits = transport.requests.single().limits
        assertEquals(10_000, limits.connectTimeoutMs)
        assertEquals(30_000, limits.readTimeoutMs)
        assertEquals(5_242_880L, limits.maxReplyBytes)
    }
}
