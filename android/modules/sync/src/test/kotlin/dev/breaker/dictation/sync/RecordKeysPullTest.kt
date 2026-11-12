package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pull with a RecordKeys port: a row at key_version 1 or higher is opened by the port. */
class RecordKeysPullTest {

    private val clientA = "00000001-0000-4000-8000-000000000001"
    private val frame = "frame-text-for-a"
    private val keys = FakeRecordKeys()
    private val sink = RecordingPullSink()
    private val probe = RecordingQueueProbe()

    private fun aRecord(keyVersion: Int?, encrypted: String?, deleted: Boolean = false): TranscriptionRecord =
        TranscriptionRecord(
            clientId = clientA,
            change = 1L,
            deleted = deleted,
            keyVersion = keyVersion,
            encrypted = encrypted,
            storedAt = "2023-11-14T22:13:20.000Z",
        )

    private fun aBody(source: SyncSource = SyncSource.LOCAL): SyncBody =
        SyncBody("opened words", source, "small.en", 1_200, 1_700_000_000_000L)

    private fun apply(record: TranscriptionRecord): ApplyReport =
        PullApplier(sink, probe, keys).apply(PullRunResult(PullOutcome.Ok(listOf(record), "next"), false))

    /** A failure here means a key_version 1 row is not opened through the port and stored. */
    @Test
    fun `a key version 1 row opened through the port is stored`() {
        keys.openResult = SyncBodyCodec.encodeBody(aBody())

        val report = apply(aRecord(1, frame))

        assertEquals("android_sync: the row is stored", 1, report.stored)
        assertEquals("android_sync: nothing unreadable", 0, report.unreadable)
        assertEquals(
            "android_sync: the port got the client id, the key version and the text",
            listOf(OpenCall(clientA, 1, frame)),
            keys.openCalls,
        )
        assertEquals(
            "android_sync: the stored row carries the opened body",
            Transcription(
                clientA, "opened words", TranscriptionSource.LOCAL, "small.en", 1_200, 1_700_000_000_000L,
            ),
            sink.rows.single().row,
        )
    }

    /** A failure here means a version above 1 is not passed to the port as it is. */
    @Test
    fun `the port gets the key version of the record`() {
        keys.openResult = SyncBodyCodec.encodeBody(aBody())

        apply(aRecord(7, frame))

        assertEquals("android_sync: the version is 7", listOf(OpenCall(clientA, 7, frame)), keys.openCalls)
    }

    /** A failure here means a port that cannot open stores a row or hides it from the counts. */
    @Test
    fun `an open that answers null counts unreadable and stores nothing`() {
        keys.openResult = null

        val report = apply(aRecord(1, frame))

        assertEquals("android_sync: one unreadable", 1, report.unreadable)
        assertEquals("android_sync: nothing stored", 0, report.stored)
        assertEquals("android_sync: the sink is never called", emptyList<String>(), sink.rowIds())
    }

    /** A failure here means bytes the lenient reader refuses are stored. */
    @Test
    fun `opened bytes the reader refuses count unreadable`() {
        keys.openResult = "not a body".encodeToByteArray()

        val report = apply(aRecord(1, frame))

        assertEquals("android_sync: one unreadable", 1, report.unreadable)
        assertEquals("android_sync: nothing stored", 0, report.stored)
        assertEquals("android_sync: the sink is never called", emptyList<String>(), sink.rowIds())
    }

    /** A failure here means a user's own record with source local is refused after opening. */
    @Test
    fun `an opened body with source local is accepted`() {
        keys.openResult = SyncBodyCodec.encodeBody(aBody(SyncSource.LOCAL))

        val report = apply(aRecord(1, frame))

        assertEquals("android_sync: stored", 1, report.stored)
        assertEquals(
            "android_sync: the source stays local",
            TranscriptionSource.LOCAL,
            sink.rows.single().row.source,
        )
    }

    /** A failure here means a key_version 0 row stops reading plain when a port is wired. */
    @Test
    fun `key version 0 still reads plain and the port is not asked`() {
        val plain = SyncBodyCodec.encodePlain(aBody())

        val report = apply(aRecord(0, plain))

        assertEquals("android_sync: stored", 1, report.stored)
        assertEquals("android_sync: the port is never asked", 0, keys.openCalls.size)
    }

    /** A failure here means the array the port returned still holds the body after the read. */
    @Test
    fun `the array returned by open is zero after the read`() {
        val returned = SyncBodyCodec.encodeBody(aBody())
        keys.openResult = returned
        assertTrue("android_sync: control: not zero before the read", returned.any { it != 0.toByte() })

        apply(aRecord(1, frame))

        assertTrue("android_sync: the returned array is all zero", returned.all { it == 0.toByte() })
    }

    /** A failure here means a refused body is not zeroed either. */
    @Test
    fun `the array is zero after a refused body too`() {
        val returned = "not a body".encodeToByteArray()
        keys.openResult = returned

        apply(aRecord(1, frame))

        assertTrue("android_sync: the returned array is all zero", returned.all { it == 0.toByte() })
    }

    /** A failure here means a queued id loses its local edit, or the port is asked for it. */
    @Test
    fun `a queued id keeps the local edit and the port is not asked`() {
        probe.queued.add(clientA)
        keys.openResult = SyncBodyCodec.encodeBody(aBody())

        val report = apply(aRecord(1, frame))

        assertEquals("android_sync: skipped as queued", 1, report.skippedQueued)
        assertEquals("android_sync: nothing stored", 0, report.stored)
        assertEquals("android_sync: the port is never asked", 0, keys.openCalls.size)
    }

    /** A failure here means the refusal text for an unopenable row carries text, or changed. */
    @Test
    fun `the refusal for an unopenable row has the fixed words and no input text`() {
        keys.openResult = null

        val failure = thrownBy { PulledRowBody.read(aRecord(1, frame), keys) }

        assertTrue("android_sync: a PulledRowException", failure is PulledRowException)
        assertEquals("pulled body refused: cannot be opened", failure?.message)
        assertTrue("android_sync: no input text in the message", failure?.message?.contains(frame) == false)
    }

    /** A failure here means the other refusals changed when a port is wired. */
    @Test
    fun `the delete and missing text refusals keep their words with a port`() {
        val deleted = thrownBy { PulledRowBody.read(aRecord(1, frame, deleted = true), keys) }
        val missing = thrownBy { PulledRowBody.read(aRecord(1, null), keys) }
        val noVersion = thrownBy { PulledRowBody.read(aRecord(null, frame), keys) }

        assertEquals("pulled body refused: delete record has no body", deleted?.message)
        assertEquals("pulled body refused: missing encrypted body", missing?.message)
        assertEquals("pulled body refused: missing key version", noVersion?.message)
        assertEquals("android_sync: the port is never asked for these", 0, keys.openCalls.size)
    }
}
