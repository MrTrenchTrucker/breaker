package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A delete row has no body and needs no keys: with a RecordKeys port wired, the drain never asks the
 * port about it, never seals it and never lets the 409 memory stop it, and push rows behave as before.
 */
class RecordKeysDeleteRowsTest {

    private val rig = PortRig()

    private fun conflict() = PermanentRefusalException(409, "key version conflict")

    /** A failure here means a delete waits for the port, or a port without keys lets a push out. */
    @Test
    fun `a port with no keys still sends the delete and stops at the push row`() {
        rig.keys.version = 0
        rig.service.enqueueDelete(clientId(1))
        rig.queue(clientId(2))
        rig.uploader.deleteScript(UploadOutcome.CONFIRMED)

        val report = rig.push()

        assertEquals(
            "android_sync: the delete counts one push, the push row one failure",
            SyncReport(1, 1, 0),
            report,
        )
        assertEquals("android_sync: the delete was sent", listOf(clientId(1)), rig.uploader.deleted)
        assertTrue("android_sync: nothing was pushed in plain form", rig.uploader.received.isEmpty())
        assertEquals("android_sync: no upload was made", 0, rig.log.count("upload"))
        assertEquals("android_sync: seal is never called", 0, rig.log.count("seal"))
        assertEquals(
            "android_sync: only the push row stays queued",
            listOf(clientId(2)),
            rig.database.queuedIds(),
        )
        val deleteAt = rig.log.events.indexOf("delete")
        val versionAt = rig.log.events.indexOf("version")
        assertTrue("android_sync: the delete call is in the log", deleteAt >= 0)
        assertTrue("android_sync: the port is first asked after the delete was sent", versionAt > deleteAt)
        assertEquals("android_sync: the port is asked once, for the push only", 1, rig.log.count("version"))
    }

    /** A failure here means a delete is held back by the 409 memory, or a confirmed delete clears it. */
    @Test
    fun `a delete is sent while the 409 memory refuses the version and does not clear it`() {
        rig.keys.version = 2
        rig.queue(clientId(1))
        rig.uploader.thenThrow(conflict())
        val refused = rig.push()
        assertEquals("android_sync: control: the 409 is one failure", SyncReport(0, 1, 0), refused)
        rig.service.clearAll()
        rig.service.enqueueDelete(clientId(2))
        rig.uploader.deleteScript(UploadOutcome.CONFIRMED)

        val report = rig.push()

        assertEquals("android_sync: the delete is confirmed despite the 409", SyncReport(1, 0, 0), report)
        assertEquals("android_sync: the delete was sent", listOf(clientId(2)), rig.uploader.deleted)
        assertEquals("android_sync: no second upload", 1, rig.log.count("upload"))
        assertEquals("android_sync: no second seal", 1, rig.log.count("seal"))
        assertEquals("android_sync: the port was not asked for the delete", 1, rig.log.count("version"))
        assertEquals("android_sync: the queue is empty", emptyList<String>(), rig.database.queuedIds())

        rig.queue(clientId(3))
        val after = rig.push()

        assertEquals("android_sync: the memory still refuses version 2", SyncReport(0, 1, 0), after)
        assertEquals("android_sync: still one upload", 1, rig.log.count("upload"))
        assertEquals("android_sync: still one seal", 1, rig.log.count("seal"))
        assertEquals(
            "android_sync: the new push row stays queued",
            listOf(clientId(3)),
            rig.database.queuedIds(),
        )
    }

    /** A failure here means a push is not sealed at the port version, or the delete is sealed or uploaded. */
    @Test
    fun `a push is sealed and sent and a delete behind it is sent unsealed`() {
        rig.keys.version = 2
        rig.queue(clientId(1))
        rig.service.enqueueDelete(clientId(2))
        rig.uploader.deleteScript(UploadOutcome.CONFIRMED)

        val report = rig.push()

        assertEquals("android_sync: both rows are counted as pushed", SyncReport(2, 0, 0), report)
        assertEquals(
            "android_sync: only the push row was uploaded",
            listOf(clientId(1)),
            rig.uploader.received.map { it.clientId },
        )
        val push = rig.uploader.received.first()
        assertEquals("android_sync: the push is at the port version", 2, push.keyVersion)
        assertEquals(
            "android_sync: the push carries the sealed text",
            rig.keys.frameFor(clientId(1), 2),
            push.encrypted,
        )
        assertEquals("android_sync: the delete was sent", listOf(clientId(2)), rig.uploader.deleted)
        assertEquals("android_sync: seal ran once, for the push only", 1, rig.log.count("seal"))
        assertEquals("android_sync: seal got the push id", listOf(clientId(1)), rig.keys.sealedClientIds)
        assertEquals("android_sync: one upload", 1, rig.log.count("upload"))
        assertEquals("android_sync: the queue is empty", emptyList<String>(), rig.database.queuedIds())
    }
}
