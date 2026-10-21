package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shape of each push the service hands the uploader: the client id is the transcription id,
 * the key version is always 0, and the encrypted field decodes with the codec to the enqueued
 * transcription, source word included.
 *
 * A push whose body does not decode to the transcription it came from sends the wrong words to the
 * server, and a push at any other key version would be a form the module does not build.
 */
class QueuedSyncServicePushTest {

    private val rig = ServiceRig()

    private val cafeText = "caf" + Char(0xE9) + " quartz"

    /** A failure here means the push does not decode to the transcription it was built from, or its id or key version differ. */
    @Test
    fun `the uploader receives a push that decodes to the enqueued transcription`() {
        rig.service.enqueue(
            aTranscription(
                clientId(1),
                text = "quartz marmalade for one",
                source = TranscriptionSource.LOCAL,
                model = "small.en",
                durationMs = 1_200L,
                createdAt = 1_700_000_000_000L,
            ),
        )

        val report = rig.push()

        assertEquals("android_sync: one entry must give SyncReport(1, 0)", SyncReport(1, 0), report)
        val push = rig.uploader.received.single()
        assertEquals("android_sync: the client id must be the transcription id", clientId(1), push.clientId)
        assertEquals("android_sync: the key version must be 0", 0, push.keyVersion)
        val body = SyncBodyCodec.decodePlain(push.encrypted)
        assertEquals("android_sync: the body text must be the transcription text", "quartz marmalade for one", body.text)
        assertEquals("android_sync: the body source must be local", SyncSource.LOCAL, body.source)
        assertEquals("android_sync: the body model must be the transcription model", "small.en", body.model)
        assertEquals("android_sync: the body duration must be the transcription duration", 1_200L, body.durationMs)
        assertEquals("android_sync: the body createdAt must be the transcription createdAt", 1_700_000_000_000L, body.createdAtMs)
    }

    /** A failure here means a server transcription reaches the uploader with another source, or its text is changed. */
    @Test
    fun `a server transcription is pushed with the source server in its body`() {
        rig.service.enqueue(
            aTranscription(
                clientId(1),
                text = cafeText,
                source = TranscriptionSource.SERVER,
                model = "large-v3",
            ),
        )

        val report = rig.push()

        assertEquals("android_sync: one entry must give SyncReport(1, 0)", SyncReport(1, 0), report)
        val body = rig.uploadedBodies().single()
        assertEquals("android_sync: the body source must be server", SyncSource.SERVER, body.source)
        assertEquals("android_sync: the body text must be unchanged, letter included", cafeText, body.text)
    }

    /** A failure here means a push is built at a key version other than 0. */
    @Test
    fun `every push is sent at key version zero`() {
        rig.service.enqueue(aTranscription(clientId(1), source = TranscriptionSource.LOCAL))
        rig.service.enqueue(aTranscription(clientId(2), source = TranscriptionSource.SERVER))

        val report = rig.push()

        assertEquals("android_sync: both entries must give SyncReport(2, 0)", SyncReport(2, 0), report)
        assertEquals(
            "android_sync: every push must carry key version 0",
            listOf(0, 0),
            rig.uploader.received.map { it.keyVersion },
        )
    }
}
