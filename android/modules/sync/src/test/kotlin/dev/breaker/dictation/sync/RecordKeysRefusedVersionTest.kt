package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Test

/** The 409 memory: a refused key version is not tried again until the port reports another one. */
class RecordKeysRefusedVersionTest {

    private val rig = PortRig()

    private fun conflict() = PermanentRefusalException(409, "key version conflict")

    /** A failure here means a 409 parks, removes or drops the row, or the report is wrong. */
    @Test
    fun `a 409 keeps the row queued and does not park it`() {
        rig.keys.version = 2
        rig.queue(clientId(1))
        rig.uploader.thenThrow(conflict())

        val report = rig.push()

        assertEquals("android_sync: one failure, nothing parked", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: the row stays queued", listOf(clientId(1)), rig.database.queuedIds())
        assertEquals("android_sync: the row is not parked", 0, rig.database.parkedCount())
        assertEquals("android_sync: the row is not removed", 0, rig.database.removeCalls)
    }

    /** A failure here means the drain tries a refused version again, or calls seal for it. */
    @Test
    fun `later drains at the same version call neither the uploader nor seal`() {
        rig.keys.version = 2
        rig.queue(clientId(1))
        rig.uploader.thenThrow(conflict())
        rig.push()

        val second = rig.push()
        val third = rig.push()

        assertEquals("android_sync: the second drain is one failure", SyncReport(0, 1, 0), second)
        assertEquals("android_sync: the third drain is one failure", SyncReport(0, 1, 0), third)
        assertEquals("android_sync: one upload over three drains", 1, rig.log.count("upload"))
        assertEquals("android_sync: seal was called once over three drains", 1, rig.log.count("seal"))
        assertEquals("android_sync: the row is still queued", listOf(clientId(1)), rig.database.queuedIds())
    }

    /** A failure here means a new key version from the port is not tried, or the row is not removed. */
    @Test
    fun `a new version from the port is pushed and a confirmed push removes the row`() {
        rig.keys.version = 2
        rig.queue(clientId(1))
        rig.uploader.thenThrow(conflict())
        rig.push()
        rig.push()
        rig.keys.version = 3

        val report = rig.push()

        assertEquals("android_sync: the push is confirmed", SyncReport(1, 0, 0), report)
        assertEquals("android_sync: the uploader was called a second time", 2, rig.log.count("upload"))
        assertEquals("android_sync: the push is at version 3", 3, rig.uploader.received.last().keyVersion)
        assertEquals("android_sync: the row is gone", emptyList<String>(), rig.database.queuedIds())
    }

    /** A failure here means the memory outlives a version change, so an old refusal blocks a new one. */
    @Test
    fun `the memory is dropped when the version changes`() {
        rig.keys.version = 2
        rig.queue(clientId(1))
        rig.uploader.thenThrow(conflict())
        rig.push()
        rig.keys.version = 3
        rig.uploader.thenThrow(conflict())
        rig.push()
        rig.keys.version = 2

        val report = rig.push()

        assertEquals("android_sync: version 2 is pushed again", SyncReport(1, 0, 0), report)
        assertEquals("android_sync: three uploads in all", 3, rig.log.count("upload"))
    }

    /** A failure here means a 409 with no port is no longer today's FAILED, or is remembered. */
    @Test
    fun `a 409 with no port is failed and the next drain calls the uploader again`() {
        val plain = ServiceRig()
        plain.queue(clientId(1))
        plain.uploader.thenThrow(conflict())

        val first = plain.push()
        val second = plain.push()

        assertEquals("android_sync: the first drain is FAILED", SyncReport(0, 1, 0), first)
        assertEquals("android_sync: the second drain pushes and confirms", SyncReport(1, 0, 0), second)
        assertEquals("android_sync: the uploader was called twice", 2, plain.uploader.calls)
    }

    /** A failure here means a 400 no longer parks the row when a port is wired. */
    @Test
    fun `a 400 parks the row with a port`() {
        rig.keys.version = 2
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(
            PermanentRefusalException(PermanentRefusalException.PERMANENT_REFUSAL_400, "bad body"),
        )

        val report = rig.push()

        assertEquals("android_sync: one parked, one pushed", SyncReport(1, 0, 1), report)
        assertEquals("android_sync: one row is parked", 1, rig.database.parkedCount())
        assertEquals("android_sync: the parked row stays", listOf(clientId(1)), rig.database.queuedIds())
    }

    /** A failure here means a 429 stops carrying Retry-After with a port, or is remembered like a 409. */
    @Test
    fun `a 429 carries retry after with a port and is not remembered`() {
        rig.keys.version = 2
        rig.queue(clientId(1))
        rig.uploader.thenThrow(PermanentRefusalException(429, "rate limited", retryAfterSeconds = 45))

        val first = rig.push()
        val second = rig.push()

        assertEquals("android_sync: one failure and the number", SyncReport(0, 1, 0, 45), first)
        assertEquals("android_sync: the next drain tries again and confirms", SyncReport(1, 0, 0), second)
        assertEquals("android_sync: the uploader was called twice", 2, rig.uploader.calls)
    }
}
