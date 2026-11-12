package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SealedRecord
import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.port.RecordKeys
import dev.breaker.shared.api.SyncPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the pending conversion does when the transport or the uploader goes wrong, and when it must
 * not run at all. Rigs hold two rows and a port at version 2, so the first upload is the one that
 * goes wrong and the second row shows whether the pass went on.
 */
class PendingConversionStopTest {

    private fun rigWithTwoRows(): PendingRig {
        val rig = PendingRig()
        rig.fake.version = 2
        rig.ids = listOf(1, 2)
        return rig
    }

    /** A failure here means a non-200 answer, an unreachable server or a throwing transport costs a drain. */
    @Test
    fun `a non 200 status, an unreachable answer and a throwing transport end the pass quietly`() {
        val answers = listOf<() -> PullTransportResult>(
            { PullTransportResult.Answered(500, "oops") },
            { PullTransportResult.Answered(401, "[]") },
            { PullTransportResult.Answered(404, pendingList(listOf(pendingRow(clientId(1))))) },
            { PullTransportResult.Unreachable },
            { throw IllegalStateException("transport broke") },
        )
        for (answer in answers) {
            val rig = rigWithTwoRows()
            rig.answerOverride = answer

            val report = rig.drain()

            assertEquals("android_sync: the drain report is intact", SyncReport(0, 0, 0), report)
            assertEquals("android_sync: the list was asked for", 1, rig.pending.calls)
            assertEquals("android_sync: no failure counted", 0, rig.counts().failed)
            assertEquals("android_sync: nothing converted", 0, rig.counts().converted)
            assertEquals("android_sync: the port is not called", 0, rig.log.count("convert"))
            assertEquals("android_sync: nothing uploaded", 0, rig.log.count("upload"))
            rig.answerOverride = null
            rig.drain()
            assertEquals("android_sync: the next drain converts both", 2, rig.counts().converted)
        }
    }

    /** A failure here means FAILED does not stop the pass, or is not counted. */
    @Test
    fun `a failed upload stops the pass at the first row and counts one failure`() {
        val rig = rigWithTwoRows()
        rig.uploader.script(UploadOutcome.FAILED)

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        assertEquals("android_sync: the port was asked once", 1, rig.fake.convertCalls.size)
        assertEquals("android_sync: one failed", 1, rig.counts().failed)
        assertEquals("android_sync: none converted", 0, rig.counts().converted)
    }

    /** A failure here means UNREACHABLE counts a failure, does not stop, or drops the row. */
    @Test
    fun `an unreachable server stops the pass, counts no failure and the row is tried again`() {
        val rig = rigWithTwoRows()
        rig.uploader.script(UploadOutcome.UNREACHABLE)

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        assertEquals("android_sync: nothing failed", 0, rig.counts().failed)
        assertEquals("android_sync: nothing converted", 0, rig.counts().converted)
        rig.drain()
        assertEquals(
            "android_sync: the next drain tries the stopped row first, then the other",
            listOf(clientId(1), clientId(1), clientId(2)),
            rig.uploadedIds(),
        )
        assertEquals("android_sync: both converted now", 2, rig.counts().converted)
    }

    /** A failure here means a 429 does not stop the pass or its Retry-After number is lost. */
    @Test
    fun `a 429 stops the pass and keeps the retry after seconds`() {
        val rig = rigWithTwoRows()
        rig.uploader.thenThrow(PermanentRefusalException(429, "rate limited", retryAfterSeconds = 7))

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        assertEquals("android_sync: the number is kept", 7, rig.counts().retryAfterSeconds)
        assertEquals("android_sync: a 429 counts no failure", 0, rig.counts().failed)
    }

    /**
     * A failure here means a 409 does not stop the pass, or does not reach the memory the drain uses.
     * The pass asks for no list while the version is refused, and a queued row then stops at the
     * drain's own version check with no seal and no upload, which can only happen when both hold the
     * same RefusedKeyVersion.
     */
    @Test
    fun `a 409 stops the pass and the version is refused for the conversion and the drain`() {
        val rig = rigWithTwoRows()
        rig.uploader.thenThrow(PermanentRefusalException(409, "key version conflict"))

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        assertEquals("android_sync: the list was asked for once", 1, rig.pending.calls)
        rig.drain()
        assertEquals("android_sync: no second list request while refused", 1, rig.pending.calls)
        assertEquals("android_sync: no second conversion", 1, rig.fake.convertCalls.size)
        assertEquals("android_sync: no second upload", 1, rig.uploader.pushes.size)

        rig.service.enqueue(aTranscription(clientId(9)))
        val report = rig.drain()

        assertEquals("android_sync: the drain stops at the version check", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: the drain sealed nothing", 0, rig.log.count("seal"))
        assertEquals("android_sync: still one upload in all", 1, rig.uploader.pushes.size)
    }

    /** A failure here means a 400 stops the pass, is not counted, or the next row is not tried. */
    @Test
    fun `a 400 counts a failure and the next row is still tried`() {
        val rig = rigWithTwoRows()
        rig.uploader.thenThrow(PermanentRefusalException(400, "bad body"))

        rig.drain()

        assertEquals(
            "android_sync: both rows were tried",
            listOf(clientId(1), clientId(2)),
            rig.uploadedIds(),
        )
        assertEquals("android_sync: one failed", 1, rig.counts().failed)
        assertEquals("android_sync: one converted", 1, rig.counts().converted)
        assertNull("android_sync: a 400 carries no retry-after", rig.counts().retryAfterSeconds)
        // Permitted re-point (row-level skip ruling): the refused row is marked and not sent again.
        rig.gone.add(clientId(2))
        rig.drain()
        assertEquals(
            "android_sync: the next drain does not upload the refused row again",
            listOf(clientId(1), clientId(2)),
            rig.uploadedIds(),
        )
        assertEquals("android_sync: no new failure", 1, rig.counts().failed)
    }

    /** A failure here means a 413 is treated as a stop, or is not counted. */
    @Test
    fun `a 413 counts a failure and the next row is still tried`() {
        val rig = rigWithTwoRows()
        rig.uploader.thenThrow(PermanentRefusalException(413, "too large"))

        rig.drain()

        assertEquals("android_sync: both rows were tried", 2, rig.uploader.pushes.size)
        assertEquals("android_sync: one failed", 1, rig.counts().failed)
        assertEquals("android_sync: one converted", 1, rig.counts().converted)
        // Permitted re-point (row-level skip ruling): the refused row is marked and not sent again.
        rig.gone.add(clientId(2))
        rig.drain()
        assertEquals("android_sync: the next drain uploads nothing again", 2, rig.uploader.pushes.size)
        assertEquals("android_sync: no new failure", 1, rig.counts().failed)
    }

    /** A failure here means a status 500 does not count one failure and stop the pass at its row. */
    @Test
    fun `a 500 counts one failure and stops the pass at the first row`() {
        val rig = rigWithTwoRows()
        rig.uploader.thenThrow(PermanentRefusalException(500, "server error"))

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        assertEquals("android_sync: one failed", 1, rig.counts().failed)
        assertEquals("android_sync: none converted", 0, rig.counts().converted)
        assertNull("android_sync: a 500 carries no retry-after", rig.counts().retryAfterSeconds)
    }

    /** A failure here means a plain exception from the uploader is not a failure that stops. */
    @Test
    fun `a plain exception from the uploader counts a failure and stops the pass`() {
        val rig = rigWithTwoRows()
        rig.uploader.thenThrow(IllegalStateException("boom"))

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        assertEquals("android_sync: one failed", 1, rig.counts().failed)
    }

    /** A failure here means a service without a port, or without a transport, still builds or runs a pass. */
    @Test
    fun `with no port or no pending transport no list is asked for and there are no counts`() {
        val body = pendingList(listOf(pendingRow(clientId(1))))
        val pending = ScriptedPending { PullTransportResult.Answered(200, body) }
        val noPort = QueuedSyncService(
            database = InMemorySyncQueueDatabase(),
            uploader = CountingUploader(),
            probe = ScriptedProbe(true),
            pendingTransport = pending,
        )
        noPort.pushPending()
        assertNull("android_sync: no port, no conversion", noPort.pendingCounts())
        assertEquals("android_sync: no port, no list request", 0, pending.calls)

        val keys = FakeRecordKeys()
        keys.version = 2
        val uploader = CountingUploader()
        val noTransport = QueuedSyncService(
            database = InMemorySyncQueueDatabase(),
            uploader = uploader,
            probe = ScriptedProbe(true),
            recordKeys = keys,
        )
        noTransport.pushPending()
        assertNull("android_sync: no transport, no conversion", noTransport.pendingCounts())
        assertEquals("android_sync: nothing converted", emptyList<ConvertCall>(), keys.convertCalls)
        assertEquals("android_sync: nothing uploaded", emptyList<SyncPush>(), uploader.pushes)
    }

    /** A failure here means a port with no keys still makes the pass fetch the list or call the port. */
    @Test
    fun `a port with no keys asks for no list and makes no convert call`() {
        val rig = PendingRig()
        rig.ids = listOf(1)

        rig.drain()

        assertEquals("android_sync: version 0, no list request", 0, rig.pending.calls)
        assertEquals("android_sync: no convert call", emptyList<ConvertCall>(), rig.fake.convertCalls)
        assertEquals("android_sync: nothing uploaded", emptyList<SyncPush>(), rig.uploader.pushes)
        assertEquals("android_sync: the runner exists with zero counts", 0, rig.counts().converted)

        rig.fake.version = 1
        rig.drain()

        assertEquals("android_sync: control, at version 1 the list is asked for", 1, rig.pending.calls)
        assertEquals("android_sync: control, the row is converted", 1, rig.counts().converted)
    }

    /** An uploader that throws an Error, which the drain's own catch of Exception would not stop. */
    private class ErrorUploader : SyncUploader {
        override fun upload(push: SyncPush): UploadOutcome = throw AssertionError("android_sync: broke")
        override fun delete(clientId: String): UploadOutcome = throw AssertionError("android_sync: broke")
    }

    /** A port whose convertSealed throws an Error, and that keeps the array it was given. */
    private class ErrorKeys(private val inner: FakeRecordKeys) : RecordKeys by inner {
        var seen: ByteArray? = null

        override fun convertSealed(clientId: String, sealedBox: ByteArray): SealedRecord? {
            seen = sealedBox
            throw AssertionError("android_sync: port broke")
        }
    }

    /** A queue whose isQueued throws once [armed] is set. */
    private class ArmedQueue(private val inner: InMemorySyncQueueDatabase) : SyncQueueDatabase by inner {
        var armed = false
        var thrown = 0

        override fun isQueued(clientId: String): Boolean {
            if (!armed) return inner.isQueued(clientId)
            thrown++
            throw IllegalStateException("android_sync: queue read broke")
        }
    }

    private fun serviceOver(
        database: SyncQueueDatabase,
        uploader: SyncUploader,
        keys: RecordKeys,
        onFetch: () -> Unit = {},
    ): QueuedSyncService {
        val pending = ScriptedPending {
            onFetch()
            PullTransportResult.Answered(200, pendingList(listOf(pendingRow(clientId(1), boxText(1)))))
        }
        return QueuedSyncService(
            database = database,
            uploader = uploader,
            probe = ScriptedProbe(true),
            recordKeys = keys,
            pendingTransport = pending,
        )
    }

    /** A failure here means an Error from the uploader inside the pass costs the drain its report. */
    @Test
    fun `an Error thrown by the uploader inside the pass does not leave pushPending`() {
        val keys = FakeRecordKeys()
        keys.version = 2
        val service = serviceOver(InMemorySyncQueueDatabase(), ErrorUploader(), keys)

        val report = service.pushPending()

        assertEquals("android_sync: the finished drain keeps its report", SyncReport(0, 0, 0), report)
        assertEquals("android_sync: the pass did reach the uploader", 1, keys.convertCalls.size)
    }

    /** A failure here means an Error from the port costs the drain its report or leaves the box unwiped. */
    @Test
    fun `an Error thrown by the port does not leave pushPending and the box is still wiped`() {
        val fake = FakeRecordKeys()
        fake.version = 2
        val keys = ErrorKeys(fake)
        val uploader = CountingUploader()
        val service = serviceOver(InMemorySyncQueueDatabase(), uploader, keys)

        val report = service.pushPending()

        assertEquals("android_sync: the finished drain keeps its report", SyncReport(0, 0, 0), report)
        assertEquals("android_sync: nothing uploaded", 0, uploader.pushes.size)
        assertEquals("android_sync: the box was wiped", listOf<Byte>(0, 0, 0), keys.seen?.toList())
    }

    /** A failure here means a queue read that throws inside the pass costs the drain its report. */
    @Test
    fun `a queue read that throws inside the pass does not leave pushPending`() {
        val keys = FakeRecordKeys()
        keys.version = 2
        val queue = ArmedQueue(InMemorySyncQueueDatabase())
        val service = serviceOver(queue, CountingUploader(), keys, onFetch = { queue.armed = true })

        val report = service.pushPending()

        assertEquals("android_sync: the finished drain keeps its report", SyncReport(0, 0, 0), report)
        assertEquals("android_sync: the pass's own queue read threw once", 1, queue.thrown)
        assertTrue("android_sync: nothing was converted", keys.convertCalls.isEmpty())
    }

    /** A failure here means a null pendingTransport changes what the drain pushes or builds a runner. */
    @Test
    fun `with a null pending transport the service drains exactly as before`() {
        val keys = FakeRecordKeys()
        keys.version = 2
        val uploader = CountingUploader()
        val service = QueuedSyncService(
            database = InMemorySyncQueueDatabase(),
            uploader = uploader,
            probe = ScriptedProbe(true),
            recordKeys = keys,
            pendingTransport = null,
        )
        service.enqueue(aTranscription(clientId(1)))
        service.enqueue(aTranscription(clientId(2)))

        val report = service.pushPending()

        assertEquals("android_sync: both rows are pushed", SyncReport(2, 0, 0), report)
        assertEquals(
            "android_sync: sealed at the port's version, one push per row",
            listOf(clientId(1), clientId(2)),
            uploader.pushes.map { it.clientId },
        )
        assertEquals("android_sync: at version 2", listOf(2, 2), uploader.pushes.map { it.keyVersion })
        assertNull("android_sync: no runner is built", service.pendingCounts())
        assertEquals("android_sync: the port was never asked", emptyList<ConvertCall>(), keys.convertCalls)
    }
}
