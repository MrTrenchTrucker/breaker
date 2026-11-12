package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The push with a RecordKeys port: sealed by the port, at the port's version, never in plain form. */
class RecordKeysPushTest {

    private val rig = PortRig()

    /** The R1 body bytes of the row that aTranscription(id) queues. */
    private fun expectedBytes(id: String): ByteArray {
        val t = aTranscription(id)
        val body = SyncBody(t.text, SyncSource.LOCAL, t.model, t.durationMs, t.createdAt)
        return SyncBodyCodec.encodeBody(body)
    }

    /** A failure here means a port push is not sent at the port's version with the port's text. */
    @Test
    fun `a push goes out at the port version with the port frame text`() {
        rig.keys.version = 3
        rig.queue(clientId(1))

        val report = rig.push()

        assertEquals("android_sync: one row pushed", SyncReport(1, 0, 0), report)
        val push = rig.uploader.received.single()
        assertEquals("android_sync: the client id", clientId(1), push.clientId)
        assertEquals("android_sync: the key version is the port's", 3, push.keyVersion)
        assertEquals(
            "android_sync: the text is the port's",
            rig.keys.frameFor(clientId(1), 3),
            push.encrypted,
        )
        assertNull("android_sync: no resave flag", push.resave)
    }

    /** A failure here means the port is given another client id or another body than the row's. */
    @Test
    fun `the port sees the client id and the R1 body bytes of the row`() {
        rig.keys.version = 3
        rig.queue(clientId(1))

        rig.push()

        assertEquals(
            "android_sync: seal got the client id",
            listOf(clientId(1)),
            rig.keys.sealedClientIds,
        )
        assertArrayEquals(
            "android_sync: seal got the R1 body bytes of the row",
            expectedBytes(clientId(1)),
            rig.keys.sealedCopies.single(),
        )
    }

    /** A failure here means a confirmed port push leaves the row queued, or a failed one removes it. */
    @Test
    fun `the row is removed after CONFIRMED and kept after FAILED`() {
        rig.keys.version = 3
        rig.queue(clientId(1))
        rig.uploader.script(UploadOutcome.FAILED)

        val failed = rig.push()

        assertEquals("android_sync: FAILED counts one failure", SyncReport(0, 1, 0), failed)
        assertEquals(
            "android_sync: the row stays after FAILED",
            listOf(clientId(1)),
            rig.database.queuedIds(),
        )
        val confirmed = rig.push()
        assertEquals("android_sync: CONFIRMED counts one push", SyncReport(1, 0, 0), confirmed)
        assertEquals(
            "android_sync: the row is gone after CONFIRMED",
            emptyList<String>(),
            rig.database.queuedIds(),
        )
    }

    /** A failure here means the key version is read when the row is queued, not when it is pushed. */
    @Test
    fun `the key version is read at push time`() {
        rig.keys.version = 1
        rig.queue(clientId(1))
        rig.keys.version = 2

        rig.push()

        assertEquals(
            "android_sync: the push is at the version the port reports now",
            2,
            rig.uploader.received.single().keyVersion,
        )
    }

    /** A failure here means a port without keys lets something out, or fails to keep the rows. */
    @Test
    fun `no keys sends nothing and keeps the rows`() {
        rig.keys.version = 0
        rig.queue(clientId(1), clientId(2))

        val report = rig.push()

        assertEquals("android_sync: the drain stops with one failure", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: the uploader is never called", 0, rig.log.count("upload"))
        assertTrue("android_sync: nothing plain or sealed was sent", rig.uploader.received.isEmpty())
        assertEquals("android_sync: seal is never called", 0, rig.log.count("seal"))
        assertEquals(
            "android_sync: both rows stay queued",
            listOf(clientId(1), clientId(2)),
            rig.database.queuedIds(),
        )
        assertEquals("android_sync: nothing is removed", 0, rig.database.removeCalls)
    }

    /** A failure here means a seal that answers null falls back to a plain push or drops the row. */
    @Test
    fun `a seal that answers null stops the drain with the rows kept`() {
        rig.keys.version = 1
        rig.keys.canSeal = false
        rig.queue(clientId(1), clientId(2))

        val report = rig.push()

        assertEquals("android_sync: the drain stops with one failure", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: seal was asked once, for the first row", 1, rig.log.count("seal"))
        assertEquals("android_sync: the uploader is never called", 0, rig.log.count("upload"))
        assertEquals(
            "android_sync: both rows stay queued",
            listOf(clientId(1), clientId(2)),
            rig.database.queuedIds(),
        )
    }

    /** A failure here means a row with a bad client id is sealed, or ends the drain. */
    @Test
    fun `a row with a bad client id is skipped and the drain goes on`() {
        rig.keys.version = 2
        rig.queue("not-a-client-id", clientId(2))

        val report = rig.push()

        assertEquals("android_sync: one skipped, one pushed", SyncReport(1, 1, 0), report)
        assertEquals(
            "android_sync: only the good row reached seal",
            listOf(clientId(2)),
            rig.keys.sealedClientIds,
        )
        assertEquals(
            "android_sync: only the good row was uploaded",
            listOf(clientId(2)),
            rig.uploader.received.map { it.clientId },
        )
        assertEquals(
            "android_sync: the skipped row stays queued",
            listOf("not-a-client-id"),
            rig.database.queuedIds(),
        )
    }

    /** A failure here means a body the codec refuses is sealed, or ends the drain. */
    @Test
    fun `a row the codec refuses is skipped and the drain goes on`() {
        rig.keys.version = 2
        rig.service.enqueue(aTranscription(clientId(1), durationMs = Long.MAX_VALUE))
        rig.queue(clientId(2))

        val report = rig.push()

        assertEquals("android_sync: one skipped, one pushed", SyncReport(1, 1, 0), report)
        assertEquals(
            "android_sync: only the good row reached seal",
            listOf(clientId(2)),
            rig.keys.sealedClientIds,
        )
    }

    /** A failure here means the body array given to seal still holds the text after the call. */
    @Test
    fun `the body array handed to seal is zero after the call`() {
        rig.keys.version = 2
        rig.queue(clientId(1))

        rig.push()

        val kept = rig.keys.sealedBodies.single()
        val seen = rig.keys.sealedCopies.single()
        assertTrue("android_sync: control: not zero during the call", seen.any { it != 0.toByte() })
        assertTrue("android_sync: the array is all zero after the call", kept.all { it == 0.toByte() })
        assertEquals("android_sync: the array keeps its length", seen.size, kept.size)
    }
}
