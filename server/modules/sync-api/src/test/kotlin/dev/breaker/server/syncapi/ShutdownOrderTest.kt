package dev.breaker.server.syncapi

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

// A recording fake proves stopInternal runs, then closeDatabase; a throwing stopInternal still
// closes the database and its failure still reaches the caller. The fake only records what
// shutdownInOrder did; it is never built from a value the test varies.
internal class ShutdownOrderTest {

    private class Recorder {
        val calls = mutableListOf<String>()
        var stopInternalThrows: Boolean = false
    }

    private class InternalFailure : RuntimeException("internal engine failed")

    private fun shutdownWith(rec: Recorder) {
        shutdownInOrder(
            stopInternal = {
                rec.calls += "internal"
                if (rec.stopInternalThrows) throw InternalFailure()
            },
            closeDatabase = { rec.calls += "database" },
        )
    }

    @Test
    fun `stop internal then close database in order`() {
        val rec = Recorder()
        shutdownWith(rec)
        assertEquals("sync-api: shutdown order", listOf("internal", "database"), rec.calls)
    }

    @Test
    fun `throwing stop internal still closes the database and the failure reaches the caller`() {
        val rec = Recorder()
        rec.stopInternalThrows = true
        var caught: Throwable? = null
        try {
            shutdownWith(rec)
        } catch (failure: InternalFailure) {
            caught = failure
        }
        assertEquals("sync-api: database closed even when stop internal throws",
            listOf("internal", "database"), rec.calls)
        if (caught == null) fail("sync-api: the internal-engine failure did not reach the caller")
    }

    @Test
    fun `stop sweep runs between stop internal and close database`() {
        val rec = Recorder()
        shutdownInOrder(
            stopInternal = { rec.calls += "internal" },
            closeDatabase = { rec.calls += "database" },
            stopSweep = { rec.calls += "sweep" },
        )
        assertEquals("sync-api: sweep runs between internal and database",
            listOf("internal", "sweep", "database"), rec.calls)
    }

    @Test
    fun `throwing stop sweep still closes the database and the failure reaches the caller`() {
        val rec = Recorder()
        var caught: Throwable? = null
        try {
            shutdownInOrder(
                stopInternal = { rec.calls += "internal" },
                closeDatabase = { rec.calls += "database" },
                stopSweep = { rec.calls += "sweep"; throw InternalFailure() },
            )
        } catch (failure: InternalFailure) {
            caught = failure
        }
        assertEquals("sync-api: database closed even when stop sweep throws",
            listOf("internal", "sweep", "database"), rec.calls)
        if (caught == null) fail("sync-api: the sweep failure did not reach the caller")
    }

    @Test
    fun `throwing stop internal still runs stop sweep and close database`() {
        val rec = Recorder()
        var caught: Throwable? = null
        try {
            shutdownInOrder(
                stopInternal = { rec.calls += "internal"; throw InternalFailure() },
                closeDatabase = { rec.calls += "database" },
                stopSweep = { rec.calls += "sweep" },
            )
        } catch (failure: InternalFailure) {
            caught = failure
        }
        assertEquals("sync-api: sweep and database still run when stop internal throws",
            listOf("internal", "sweep", "database"), rec.calls)
        if (caught == null) fail("sync-api: the internal-engine failure did not reach the caller")
    }
}
