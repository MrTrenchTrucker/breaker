package dev.breaker.dictation

import dev.breaker.dictation.host.ProbeStatusRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ProbeStatusRefreshTest {

    private class Recording {
        val events: MutableList<String> = ArrayList()
        var runs: Int = 0
    }

    private fun inline(recording: Recording, fail: Boolean = false): ProbeStatusRefresh =
        ProbeStatusRefresh(
            refreshProbe = {
                recording.runs += 1
                recording.events += "refresh"
                if (fail) throw IllegalStateException("probe down")
            },
            startThread = { work -> work() },
        )

    @Test
    fun `onDone is called once after the refresh`() {
        val recording = Recording()
        val done = AtomicInteger(0)
        inline(recording).start { done.incrementAndGet(); recording.events += "done" }
        assertEquals(listOf("refresh", "done"), recording.events)
        assertEquals(1, done.get())
    }

    @Test
    fun `a throwing refresh still calls onDone once`() {
        val recording = Recording()
        val done = AtomicInteger(0)
        inline(recording, fail = true).start { done.incrementAndGet(); recording.events += "done" }
        assertEquals(listOf("refresh", "done"), recording.events)
        assertEquals(1, done.get())
    }

    @Test
    fun `a start while one is in flight does nothing`() {
        val handed: MutableList<() -> Unit> = ArrayList()
        val done = AtomicInteger(0)
        val probe = ProbeStatusRefresh(refreshProbe = {}, startThread = { work -> handed.add(work) })
        probe.start { done.incrementAndGet() }
        probe.start { done.incrementAndGet() }
        assertEquals("a second start while in flight must not hand any work to the starter", 1, handed.size)
        assertEquals("a second start while in flight must not run", 0, done.get())
        handed[0]()
        assertEquals(1, done.get())
    }

    @Test
    fun `a completed run lets the next start run again`() {
        val recording = Recording()
        val done = AtomicInteger(0)
        val probe = inline(recording)
        probe.start { done.incrementAndGet() }
        assertEquals(1, done.get())
        probe.start { done.incrementAndGet() }
        assertEquals(2, done.get())
        assertEquals(2, recording.runs)
    }

    @Test
    fun `the default starter runs the callback off the caller's thread`() {
        val caller = Thread.currentThread()
        val callbackThread = AtomicReference<Thread?>(null)
        val latch = CountDownLatch(1)
        ProbeStatusRefresh(refreshProbe = {}).start {
            callbackThread.set(Thread.currentThread())
            latch.countDown()
        }
        assertTrue("the callback never ran", latch.await(5, TimeUnit.SECONDS))
        assertNotEquals("the callback must not run on the caller's thread", caller, callbackThread.get())
        assertEquals("breaker-probe-status", callbackThread.get()?.name)
    }
}
