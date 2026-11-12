package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The pull through the service with a RecordKeys port: [QueuedSyncService] must hand its port to the
 * applier it builds inside its pull cycle. One pull page holds one key_version 1 row and one
 * key_version 0 row. The database is [InMemorySyncQueueDatabase], so the tests run on a plain JVM.
 *
 * The service does not expose the applier's counts, so "nothing unreadable" is shown the way the
 * service lets it be shown: the sink holds the row that the port opened, and open ran once.
 */
class RecordKeysServicePullTest {

    private val sealedId = "00000001-0000-4000-8000-000000000001"
    private val plainId = "00000002-0000-4000-8000-000000000002"
    private val sealedFrame = "frame-text-for-a"
    private val storedAt = "2023-11-14T22:13:20.000Z"

    private val openedBody = SyncBody("opened words", SyncSource.LOCAL, "small.en", 1_200, 1_700_000_000_000L)
    private val plainBody = SyncBody("plain words", SyncSource.SERVER, "base.en", 900, 1_700_000_000_500L)

    /** The service, its port and its sink after one drain that ran the pull. */
    private class Pulled(val keys: FakeRecordKeys, val sink: RecordingPullSink)

    private fun liveItem(clientId: String, change: Long, keyVersion: Int, encrypted: String): String =
        "{\"client_id\":\"$clientId\",\"change\":$change,\"deleted\":false,\"key_version\":$keyVersion," +
            "\"encrypted\":\"$encrypted\",\"stored_at\":\"$storedAt\"}"

    private fun page(items: List<String>, next: String): String =
        "{\"items\":[${items.joinToString(",")}],\"next\":\"$next\",\"more\":false}"

    /** Builds the service with a pull transport, a pull sink and a port, and runs one drain. */
    private fun pullOnce(): Pulled {
        val keys = FakeRecordKeys()
        keys.openResult = SyncBodyCodec.encodeBody(openedBody)
        val sink = RecordingPullSink()
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(
                liveItem(sealedId, 1, 1, sealedFrame),
                liveItem(plainId, 2, 0, SyncBodyCodec.encodePlain(plainBody)),
            ), "n1")),
            PullTransportResult.Answered(200, page(emptyList(), "n1")),
        ))
        val subject = QueuedSyncService(
            database = InMemorySyncQueueDatabase(),
            uploader = ScriptedUploader(),
            probe = ScriptedProbe(reachable = true),
            pullTransport = transport,
            pullSink = sink,
            recordKeys = keys,
        )

        subject.pushPending()

        assertEquals("android_sync: the list and its follow-up were both pulled", 2, transport.requests.size)
        return Pulled(keys, sink)
    }

    /** A failure here means the service does not pass its port to the pull applier. */
    @Test
    fun `a pulled key version 1 row is opened through the port the service holds`() {
        val pulled = pullOnce()

        assertEquals(
            "android_sync: the port got the client id, the key version 1 and the text, once",
            listOf(OpenCall(sealedId, 1, sealedFrame)),
            pulled.keys.openCalls.filter { it.clientId == sealedId },
        )
        val stored = pulled.sink.rows.map { it.row }.firstOrNull { it.id == sealedId }
        assertNotNull("android_sync: the opened row reached the sink, so it was not unreadable", stored)
        assertEquals(
            "android_sync: the stored row carries the body that open returned",
            Transcription(
                sealedId, "opened words", TranscriptionSource.LOCAL, "small.en", 1_200, 1_700_000_000_000L,
            ),
            stored,
        )
    }

    /** A failure here means a key_version 0 row is opened by the port, or is not stored from plain text. */
    @Test
    fun `a pulled key version 0 row in the same pull stays plain`() {
        val pulled = pullOnce()

        assertEquals(
            "android_sync: the port was asked for the key version 1 row only, never for the plain one",
            listOf(sealedId),
            pulled.keys.openCalls.map { it.clientId },
        )
        val stored = pulled.sink.rows.map { it.row }.firstOrNull { it.id == plainId }
        assertNotNull("android_sync: the plain row reached the sink", stored)
        assertEquals(
            "android_sync: the plain row is stored from its plain base64 text",
            Transcription(
                plainId, "plain words", TranscriptionSource.SERVER, "base.en", 900, 1_700_000_000_500L,
            ),
            stored,
        )
    }
}
