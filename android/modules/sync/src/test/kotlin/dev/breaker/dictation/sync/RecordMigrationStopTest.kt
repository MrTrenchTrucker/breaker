package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.shared.api.SyncPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the migration does when the uploader fails, cannot reach the server or refuses a record. Each
 * test holds two records at version 0 and a port at version 2, so the first upload is the one that
 * goes wrong and the second record shows whether the pass went on.
 */
class RecordMigrationStopTest {

    private fun rigWithTwoRecords(): MigrationRig {
        val rig = MigrationRig()
        rig.fake.version = 2
        rig.items = listOf(
            migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN),
            migrationLive(clientId(2), 2, 0, MIGRATION_PLAIN),
        )
        return rig
    }

    /** A failure here means FAILED does not stop the pass, or is not counted. */
    @Test
    fun `a failed upload stops the pass at the first record and counts one failure`() {
        val rig = rigWithTwoRecords()
        rig.uploader.script(UploadOutcome.FAILED)

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        assertEquals("android_sync: the port was asked once", 1, rig.fake.resealCalls.size)
        assertEquals("android_sync: one failed", 1, rig.counts().failed)
        assertEquals("android_sync: none replaced", 0, rig.counts().replaced)
    }

    /** A failure here means UNREACHABLE counts a failure, does not stop, or skips the record for good. */
    @Test
    fun `an unreachable server stops the pass, counts no failure and the record is tried again`() {
        val rig = rigWithTwoRecords()
        rig.uploader.script(UploadOutcome.UNREACHABLE)

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        assertEquals("android_sync: nothing failed", 0, rig.counts().failed)
        assertEquals("android_sync: nothing replaced", 0, rig.counts().replaced)
        rig.drain()
        assertEquals(
            "android_sync: the next drain tries the stopped record first, then the other",
            listOf(clientId(1), clientId(1), clientId(2)),
            rig.uploadedIds(),
        )
        assertEquals("android_sync: both replaced now", 2, rig.counts().replaced)
    }

    /** A failure here means a 429 does not stop the pass or its Retry-After number is lost. */
    @Test
    fun `a 429 stops the pass and keeps the retry after seconds`() {
        val rig = rigWithTwoRecords()
        rig.uploader.thenThrow(PermanentRefusalException(429, "rate limited", retryAfterSeconds = 7))

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        assertEquals("android_sync: the number is kept", 7, rig.counts().retryAfterSeconds)
        assertEquals("android_sync: a 429 counts no failure", 0, rig.counts().failed)
    }

    /**
     * A failure here means a 409 does not stop the pass, or does not reach the memory the drain
     * uses. The proof is in three parts: the migration lists no more and re-seals nothing while the
     * version is refused, and a queued row then stops at the drain's own version check with no
     * seal and no upload, which can only happen when both hold the same RefusedKeyVersion.
     */
    @Test
    fun `a 409 stops the pass and the version is refused for the migration and the drain`() {
        val rig = rigWithTwoRecords()
        rig.uploader.thenThrow(PermanentRefusalException(409, "key version conflict"))

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        val requests = rig.transport.requests.size
        rig.drain()
        assertEquals(
            "android_sync: the next drain makes the pull's own request only, the migration does not list",
            1,
            rig.transport.requests.size - requests,
        )
        assertEquals("android_sync: no second re-seal", 1, rig.fake.resealCalls.size)
        assertEquals("android_sync: no second upload", 1, rig.uploader.pushes.size)

        rig.service.enqueue(aTranscription(clientId(9)))
        val report = rig.drain()

        assertEquals("android_sync: the drain stops at the version check", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: the drain sealed nothing", 0, rig.log.count("seal"))
        assertEquals("android_sync: still one upload in all", 1, rig.uploader.pushes.size)
    }

    /** A failure here means a 400 stops the pass, is not counted, or the next record is not tried. */
    @Test
    fun `a 400 counts a failure and the next record is still tried`() {
        val rig = rigWithTwoRecords()
        rig.uploader.thenThrow(PermanentRefusalException(400, "bad body"))

        rig.drain()

        assertEquals(
            "android_sync: both records were tried",
            listOf(clientId(1), clientId(2)),
            rig.uploadedIds(),
        )
        assertEquals("android_sync: one failed", 1, rig.counts().failed)
        assertEquals("android_sync: one replaced", 1, rig.counts().replaced)
        assertNull("android_sync: a 400 carries no retry-after", rig.counts().retryAfterSeconds)
        val before = rig.transport.requests.size
        rig.drain()
        // Ruling: unconvertible and permanent problems do not keep the walk open.
        assertEquals(
            "android_sync: the walk ended, so the next drain lists nothing",
            1,
            rig.transport.requests.size - before,
        )
        assertEquals(
            "android_sync: and uploads nothing more",
            listOf(clientId(1), clientId(2)),
            rig.uploadedIds(),
        )
    }

    /** A failure here means a transient FAILED no longer keeps pending, so the record is never retried. */
    @Test
    fun `a failed upload on the only record keeps pending, the next drain lists and uploads it again`() {
        val rig = MigrationRig()
        rig.fake.version = 2
        rig.items = listOf(migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN))
        rig.uploader.script(UploadOutcome.FAILED)

        rig.drain()

        assertEquals("android_sync: one upload, one failed", listOf(clientId(1)), rig.uploadedIds())
        assertEquals("android_sync: one failed", 1, rig.counts().failed)
        val before = rig.transport.requests.size
        rig.drain()
        assertEquals(
            "android_sync: the next drain makes the pull's request and the listing",
            2,
            rig.transport.requests.size - before,
        )
        assertEquals(
            "android_sync: the record is uploaded again",
            listOf(clientId(1), clientId(1)),
            rig.uploadedIds(),
        )
        assertEquals("android_sync: replaced this time", 1, rig.counts().replaced)
    }

    /** A failure here means a listing that throws or is unreachable breaks the drain or the state. */
    @Test
    fun `a listing that throws or is unreachable ends the pass quietly, the next drain recovers`() {
        for (broken in listOf<() -> PullTransportResult>(
            { throw IllegalStateException("listing broke") },
            { PullTransportResult.Unreachable },
        )) {
            val rig = rigWithTwoRecords()
            rig.listingFailure = broken

            val report = rig.drain()

            assertEquals("android_sync: the drain report is unchanged", SyncReport(0, 0, 0), report)
            assertEquals("android_sync: the listing was asked for", 3, rig.transport.requests.size)
            assertEquals("android_sync: nothing re-sealed", emptyList<ResealCall>(), rig.fake.resealCalls)
            assertEquals("android_sync: nothing uploaded", 0, rig.uploader.pushes.size)
            rig.listingFailure = null
            rig.drain()
            assertEquals("android_sync: the next drain converts both", 2, rig.counts().replaced)
        }
    }

    /** A failure here means a status 500 does not count one failure and stop the pass at its record. */
    @Test
    fun `a 500 counts one failure and stops the pass at the first record`() {
        val rig = rigWithTwoRecords()
        rig.uploader.thenThrow(PermanentRefusalException(500, "server error"))

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        assertEquals("android_sync: one failed", 1, rig.counts().failed)
        assertEquals("android_sync: none replaced", 0, rig.counts().replaced)
        assertNull("android_sync: a 500 carries no retry-after", rig.counts().retryAfterSeconds)
    }

    /** A failure here means an Error from the uploader inside the migration costs the drain its report. */
    @Test
    fun `an Error thrown by the uploader inside the migration does not leave pushPending`() {
        val keys = FakeRecordKeys()
        keys.version = 2
        val service = serviceOver(InMemorySyncQueueDatabase(), ErrorUploader(), keys)

        val report = service.pushPending()

        assertEquals("android_sync: the finished drain keeps its report", SyncReport(0, 0, 0), report)
        assertEquals("android_sync: the migration did reach the uploader", 1, keys.resealCalls.size)
    }

    /** A failure here means a queue read that throws inside the migration costs the drain its report. */
    @Test
    fun `a queue read that throws inside the migration does not leave pushPending`() {
        val keys = FakeRecordKeys()
        keys.version = 2
        val queue = ArmedQueue(InMemorySyncQueueDatabase())
        val service = serviceOver(queue, CountingUploader(), keys, onListing = { queue.armed = true })

        val report = service.pushPending()

        assertEquals("android_sync: the finished drain keeps its report", SyncReport(0, 0, 0), report)
        assertEquals("android_sync: the migration's own queue read threw once", 1, queue.thrown)
        assertEquals("android_sync: nothing was re-sealed", emptyList<ResealCall>(), keys.resealCalls)
    }

    /** An uploader that throws an Error, which the drain's own catch of Exception would not stop. */
    private class ErrorUploader : SyncUploader {
        override fun upload(push: SyncPush): UploadOutcome = throw AssertionError("android_sync: broke")
        override fun delete(clientId: String): UploadOutcome = throw AssertionError("android_sync: broke")
    }

    /**
     * A queue whose isQueued throws once [armed] is set. The test sets it when the migration's own
     * listing is requested (request number 2), so the pull applier's reads before it are not hit.
     */
    private class ArmedQueue(private val inner: InMemorySyncQueueDatabase) : SyncQueueDatabase by inner {
        var armed = false
        var thrown = 0

        override fun isQueued(clientId: String): Boolean {
            if (!armed) return inner.isQueued(clientId)
            thrown++
            throw IllegalStateException("android_sync: queue read broke")
        }
    }

    /** A service over [database] and [uploader], the port [keys] and a pull of two version 0 records. */
    private fun serviceOver(
        database: SyncQueueDatabase,
        uploader: SyncUploader,
        keys: FakeRecordKeys,
        onListing: () -> Unit = {},
    ): QueuedSyncService {
        val items = listOf(
            migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN),
            migrationLive(clientId(2), 2, 0, MIGRATION_PLAIN),
        )
        val transport = ScriptedPullTransport { n ->
            if (n == 2) onListing()
            PullTransportResult.Answered(200, migrationPage(items, "n$n"))
        }
        return QueuedSyncService(
            database = database,
            uploader = uploader,
            probe = ScriptedProbe(true),
            pullTransport = transport,
            pullSink = RecordingPullSink(),
            recordKeys = keys,
        )
    }

    /** A failure here means a plain exception from the uploader is not counted as a failure that stops. */
    @Test
    fun `a plain exception from the uploader counts a failure and stops the pass`() {
        val rig = rigWithTwoRecords()
        rig.uploader.thenThrow(IllegalStateException("boom"))

        rig.drain()

        assertEquals("android_sync: one upload only", 1, rig.uploader.pushes.size)
        assertEquals("android_sync: one failed", 1, rig.counts().failed)
    }
}
