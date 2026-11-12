package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** With no RecordKeys port every push and pull is what it was before the port existed. */
class RecordKeysNullPortTest {

    private val rig = ServiceRig()

    private fun expectedBody(id: String): SyncBody {
        val t = aTranscription(id)
        return SyncBody(t.text, SyncSource.LOCAL, t.model, t.durationMs, t.createdAt)
    }

    /** A failure here means the default push is no longer plain, key version 0. */
    @Test
    fun `a drain with no port pushes the plain key version 0 push`() {
        rig.queue(clientId(1))

        val report = rig.push()

        assertEquals("android_sync: one pushed", SyncReport(1, 0, 0), report)
        val push = rig.uploader.received.single()
        assertEquals("android_sync: key version 0", 0, push.keyVersion)
        assertEquals(
            "android_sync: the whole push equals the codec's plain push",
            SyncBodyCodec.plainPush(clientId(1), expectedBody(clientId(1))),
            push,
        )
        assertEquals(
            "android_sync: the body decodes back",
            expectedBody(clientId(1)),
            rig.uploadedBodies().single(),
        )
    }

    /** A failure here means an explicit null port differs from the default. */
    @Test
    fun `an explicit null port pushes the same push as the default`() {
        val log = CallLog()
        val uploader = ScriptedUploader(log)
        val service = QueuedSyncService(
            database = InMemorySyncQueueDatabase(log),
            uploader = uploader,
            probe = ScriptedProbe(true, log),
            recordKeys = null,
        )
        service.enqueue(aTranscription(clientId(1)))
        rig.queue(clientId(1))

        service.pushPending()
        rig.push()

        assertEquals("android_sync: the same push", rig.uploader.received, uploader.received)
        assertTrue("android_sync: control: something was pushed", uploader.received.isNotEmpty())
    }

    /** A failure here means a key_version 1 row is read without a port, or its message changed. */
    @Test
    fun `a pulled key version 1 row is unreadable with the same message`() {
        val record = TranscriptionRecord(
            clientId = clientId(1),
            change = 1L,
            deleted = false,
            keyVersion = 1,
            encrypted = "frame-text",
            storedAt = "2023-11-14T22:13:20.000Z",
        )
        val sink = RecordingPullSink()

        val report = PullApplier(sink, RecordingQueueProbe()).apply(
            PullRunResult(PullOutcome.Ok(listOf(record), "next"), false),
        )
        val failure = thrownBy { PulledRowBody.read(record) }

        assertEquals("android_sync: one unreadable", 1, report.unreadable)
        assertEquals("android_sync: nothing stored", emptyList<String>(), sink.rowIds())
        assertEquals("pulled body refused: key version 1 is not read yet", failure?.message)
        assertEquals(
            "android_sync: an explicit null port gives the same message",
            failure?.message,
            thrownBy { PulledRowBody.read(record, null) }?.message,
        )
    }
}
