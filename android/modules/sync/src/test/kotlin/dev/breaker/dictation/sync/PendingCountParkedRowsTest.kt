package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** The live-count rule the fix depends on: pendingCount() leaves parked rows out (ADR-038). */
class PendingCountParkedRowsTest {

    private fun newRig() = ServiceRig()

    /** 3 live rows and 2 parked rows: pendingCount reads 3, parkedCount reads 2. */
    @Test
    fun `pendingCount excludes parked rows when 3 are live and 2 are parked it reads 3`() {
        val rig = newRig()
        rig.queue(clientId(1), clientId(2), clientId(3), clientId(4), clientId(5))
        rig.database.parkRow(clientId(4), "bad body")
        rig.database.parkRow(clientId(5), "body too large")

        assertEquals("android_sync: pendingCount leaves parked rows out", 3, rig.service.pendingCount())
        assertEquals("android_sync: parkedCount counts parked rows", 2, rig.service.parkedCount())
    }

    /** Only parked rows: pendingCount reads 0 (the old expression reads -2). */
    @Test
    fun `pendingCount reads 0 when every row is parked`() {
        val rig = newRig()
        rig.queue(clientId(1), clientId(2))
        rig.database.parkRow(clientId(1), "bad body")
        rig.database.parkRow(clientId(2), "body too large")

        assertEquals("android_sync: all parked means pendingCount is zero", 0, rig.service.pendingCount())
        assertEquals("android_sync: parkedCount still counts both parked rows", 2, rig.service.parkedCount())
    }

    /** No parked rows: pendingCount reads the full queue. */
    @Test
    fun `pendingCount reads all rows when none are parked`() {
        val rig = newRig()
        rig.queue(clientId(1), clientId(2), clientId(3))

        assertEquals("android_sync: no parked rows means pendingCount is the queue size", 3, rig.service.pendingCount())
        assertEquals("android_sync: parkedCount is zero", 0, rig.service.parkedCount())
    }
}
