package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** clearPendingSave failing after a successful enqueue leaves the marker; the next start re-queues once
 * (idempotent re-push at worst: the server's push returns PushResult.NoOp for a second push of the same
 * client id and key version, so no duplicate row is stored). All markers here carry an old token, so the
 * walk reconciles them; a current-token marker would be left and counted as deferred instead. */
class PendingSaveClearAfterEnqueueFailureTest {

    private companion object {
        const val THIS_TOKEN = 1_700_000_000_000L
        const val OLD_TOKEN = 1_600_000_000_000L
    }

    @Test
    fun `a failed enqueue during the walk keeps the marker and the next walk re-queues once`() {
        val cleared = ArrayList<String>()
        var failEnqueue = true
        // First walk: enqueue throws, the marker stays.
        val first = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("stuck", OLD_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { id -> aTranscription(id) },
            enqueue = {
                if (failEnqueue) throw RuntimeException("android_sync: planned enqueue failure")
            },
            clearPendingSave = { cleared.add(it) },
        )
        assertEquals("android_sync: the failed enqueue is counted as a marker failure", 1, first.markerFailures)
        assertEquals("android_sync: nothing is deferred", 0, first.deferred)
        assertTrue("android_sync: the marker was not cleared", cleared.isEmpty())

        // Second walk: enqueue succeeds, the marker is cleared (one idempotent re-push).
        failEnqueue = false
        val second = backfillPendingSaves(
            runToken = THIS_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("stuck", OLD_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { id -> aTranscription(id) },
            enqueue = { },
            clearPendingSave = { cleared.add(it) },
        )
        assertEquals("android_sync: the stuck save is re-queued once", 1, second.reQueued)
        assertEquals("android_sync: no further failures", 0, second.markerFailures)
        assertEquals("android_sync: nothing is deferred", 0, second.deferred)
        assertEquals("android_sync: the marker is now cleared", listOf("stuck"), cleared)
    }
}
