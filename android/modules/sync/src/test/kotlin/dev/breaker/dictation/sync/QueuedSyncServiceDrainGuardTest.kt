package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.port.ConnectivityProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when `pushPending` is called while another call is running, when an entry is
 * queued during a drain, and when the drain guard must be given back.
 *
 * The tests are single-threaded: the "other call" is a re-entrant call made from inside the
 * uploader. A guard that lets a second call in uploads an entry twice at once; a guard that is
 * not given back leaves the queue stuck until the app restarts.
 */
class QueuedSyncServiceDrainGuardTest {

    private val rig = ServiceRig()

    /** A failure here means a second call runs a drain beside the first, or touches the queue or the probe. */
    @Test
    fun `a second pushPending while one is running returns zero and does not upload again`() {
        rig.queue(clientId(1), clientId(2))
        var entered = false
        var inner: SyncReport? = null
        var eventsBefore = -1
        var eventsAfter = -1
        var uploadsSeenInside = -1
        rig.uploader.onUpload = { _ ->
            if (!entered) {
                entered = true
                eventsBefore = rig.log.events.size
                inner = rig.service.pushPending()
                eventsAfter = rig.log.events.size
                uploadsSeenInside = rig.uploader.received.size
            }
        }

        val outer = rig.push()

        assertEquals("android_sync: a call that finds a drain running must give SyncReport(0, 0)", SyncReport(0, 0), inner)
        assertEquals("android_sync: a busy call must touch nothing, not even the probe", eventsBefore, eventsAfter)
        assertEquals("android_sync: a busy call must not upload", 1, uploadsSeenInside)
        assertEquals("android_sync: the running drain must push both entries", SyncReport(2, 0), outer)
        assertEquals(
            "android_sync: each entry must be uploaded once",
            listOf(clientId(1), clientId(2)),
            rig.uploadedIds(),
        )
    }

    /** A failure here means an entry queued while a drain runs is sent or counted in that drain, or lost. */
    @Test
    fun `an enqueue during a drain waits for the next drain`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.onUpload = { sent ->
            if (sent.clientId == clientId(1)) rig.service.enqueue(aTranscription(clientId(3)))
        }

        val first = rig.push()

        assertEquals("android_sync: the drain must send only the two entries queued when it started", SyncReport(2, 0), first)
        assertEquals("android_sync: the entry queued during the drain must not be sent in that drain", listOf(clientId(1), clientId(2)), rig.uploadedIds())
        assertEquals("android_sync: the entry queued during the drain must wait in the queue", listOf(clientId(3)), rig.database.queuedIds())

        val second = rig.push()

        assertEquals("android_sync: the next drain must send the waiting entry", SyncReport(1, 0), second)
        assertEquals(
            "android_sync: the waiting entry must go out in the next drain",
            listOf(clientId(1), clientId(2), clientId(3)),
            rig.uploadedIds(),
        )
    }

    /** A failure here means the drain guard stays taken after a drain, so every later call returns at once. */
    @Test
    fun `the drain guard is released after a drain so the next call runs`() {
        rig.queue(clientId(1))
        assertEquals("android_sync: the first drain must push", SyncReport(1, 0), rig.push())

        rig.queue(clientId(2))
        val second = rig.push()

        assertEquals("android_sync: the next call must run a drain, not return at once", SyncReport(1, 0), second)
        assertEquals("android_sync: both entries must have been uploaded", listOf(clientId(1), clientId(2)), rig.uploadedIds())
    }

    /** A failure here means one drain that throws leaves the guard taken, so the queue is stuck until a restart. */
    @Test
    fun `the drain guard is released when the drain throws`() {
        rig.queue(clientId(1))
        val cause = IllegalStateException("the disk is gone")
        rig.database.failNextHead(cause)

        val thrown = thrownBy { rig.push() }

        assertSame("android_sync: the failure must leave pushPending unchanged", cause, thrown)

        val next = rig.push()

        assertEquals("android_sync: the call after a throwing drain must run a drain", SyncReport(1, 0), next)
        assertEquals("android_sync: the entry must be uploaded by that call", listOf(clientId(1)), rig.uploadedIds())
    }

    /** A failure here means an uploader that enqueues on every call keeps one drain running without end. */
    @Test
    fun `an uploader that enqueues on every call does not keep the drain running`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.onUpload = { _ ->
            rig.service.enqueue(aTranscription(clientId(100 + rig.uploader.calls)))
        }

        val first = rig.push()

        assertEquals("android_sync: the first drain must end after the two entries it started with", SyncReport(2, 0), first)
        assertEquals(
            "android_sync: the entries queued by the uploader must wait in the queue",
            listOf(clientId(101), clientId(102)),
            rig.database.queuedIds(),
        )
        assertEquals("android_sync: the uploader must have been called twice", 2, rig.uploader.calls)

        val second = rig.push()

        assertEquals("android_sync: the second drain must end after the two entries it started with", SyncReport(2, 0), second)
        assertEquals(
            "android_sync: the entries queued during the second drain must wait again",
            listOf(clientId(103), clientId(104)),
            rig.database.queuedIds(),
        )
        assertEquals("android_sync: the uploader must have been called four times", 4, rig.uploader.calls)
    }

    /** A failure here means a throwing probe leaves the drain guard taken, or its exception is replaced. */
    @Test
    fun `a probe exception propagates and the drain lock is released`() {
        val database = InMemorySyncQueueDatabase()
        val uploader = ScriptedUploader()
        val probe = object : ConnectivityProbe {
            private var asked = 0

            override fun isServerReachable(): Boolean {
                asked++
                if (asked == 1) throw IllegalStateException("android_sync: the probe broke")
                return true
            }
        }
        val service = QueuedSyncService(database = database, uploader = uploader, probe = probe)
        service.enqueue(aTranscription(clientId(1)))

        val thrown = thrownBy { service.pushPending() }

        assertTrue("android_sync: the probe failure must reach the caller", thrown is IllegalStateException)
        assertEquals("android_sync: the probe failure must keep its message", "android_sync: the probe broke", thrown?.message)
        assertEquals("android_sync: a failing probe must upload nothing", emptyList<String>(), uploader.received.map { it.clientId })
        assertEquals("android_sync: the next call must run a drain", SyncReport(1, 0), service.pushPending())
    }

    /** A failure here means a throwing head read leaves the drain guard taken, or its exception is replaced. */
    @Test
    fun `a database exception propagates and the drain lock is released`() {
        val fresh = ServiceRig()
        fresh.queue(clientId(1))
        val cause = IllegalStateException("android_sync: the head broke")
        fresh.database.failNextHead(cause)

        val thrown = thrownBy { fresh.push() }

        assertSame("android_sync: the database failure must reach the caller unchanged", cause, thrown)
        assertEquals("android_sync: a failing read must upload nothing", emptyList<String>(), fresh.uploadedIds())

        assertEquals("android_sync: the next call must run a drain", SyncReport(1, 0), fresh.push())
    }
}
