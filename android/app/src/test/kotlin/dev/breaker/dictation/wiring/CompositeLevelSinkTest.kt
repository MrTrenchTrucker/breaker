package dev.breaker.dictation.wiring

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the fan-out sink contract without a host: add/remove keep the visible set exactly, publish
 * reaches only the sinks present when it starts (each exactly once), and a single extra thread never throws
 * while the main thread adds and removes. Records every level/handoff so reachability can be checked instead
 * of guessed, keeping every wait bounded.
 *
 * The concurrency test goes red (a ConcurrentModificationException in the publisher thread) on a plain
 * ArrayList, because publish now iterates the list directly without a copy; this CopyOnWriteArrayList stays
 * quiet. HomeLevelSinkLifecycleGateTest rule iii pins that source as a second guard.
 */

/** Records each level it is handed and how many times publish was called on it. */
private class RecordingSink : LevelSink {
    val levels = CopyOnWriteArrayList<Float>()
    var calls = 0
        private set
    override fun publish(level: Float) {
        calls++
        levels.add(level)
    }
}

/** A sink whose own [publish] removes itself from [composite] and, only if given, re-adds [extra]. */
private class SelfRemovingSink(
    private val composite: CompositeLevelSink,
    private val extra: LevelSink? = null,
) : LevelSink {
    var calls = 0
        private set
    override fun publish(level: Float) {
        calls++
        composite.remove(this)
        if (extra != null) {
            composite.add(extra)
        }
    }
}

internal class CompositeLevelSinkTest {

    @Test
    fun `a removed sink is not reached by a later publish`() {
        val tile = RecordingSink()
        val home = RecordingSink()
        val composite = CompositeLevelSink()

        composite.add(tile)
        composite.add(home)
        assertEquals("app: two sinks added", 2, composite.size)

        composite.remove(home)
        assertEquals("app: one sink remains after removing the home sink", 1, composite.size)

        composite.publish(0.5f)
        assertEquals("app: only the tile present at publish time is reached", listOf(0.5f), tile.levels.toList())
        assertEquals("app: the removed sink receives nothing", 0, home.calls)
    }

    @Test
    fun `removing a sink that was never added changes nothing`() {
        val tile = RecordingSink()
        val other = RecordingSink() // never added to composite
        val composite = CompositeLevelSink()

        composite.add(tile)
        val before = composite.size

        composite.remove(other) // not present, so no effect on the set
        assertEquals("app: size unchanged by a no-op remove", before, composite.size)

        composite.publish(0.25f)
        assertEquals("app: still reaches the present sink once", listOf(0.25f), tile.levels.toList())
        assertEquals("app: a never-added sink is called zero times", 0, other.calls)
    }

    @Test
    fun `a self-removing sink that re-adds another is reached exactly once`() {
        val composite = CompositeLevelSink()
        val keep1 = RecordingSink()
        val keep2 = RecordingSink()
        val extra = RecordingSink() // added during publish, never present at start
        val selfRemover = SelfRemovingSink(composite, extra)

        composite.add(keep1)
        composite.add(keep2)
        composite.add(selfRemover)

        composite.publish(0.75f) // no exception thrown reaching here

        assertEquals("app: keep1 reached once", 1, keep1.calls)
        assertEquals("app: keep2 reached once", 1, keep2.calls)
        assertEquals("app: self-remover reached once", 1, selfRemover.calls)
        // extra was only added during the iteration; because publish takes an iterator snapshot it is not re-entered here.
        assertEquals("app: re-added sink receives nothing from this publish", 0, extra.calls)

        composite.publish(0.75f) // second publish reaches the re-added extra and both keeps again
        assertEquals("app: re-added extra reached once by the second publish", 1, extra.calls)
        assertEquals("app: keep1 reached twice after two publishes", 2, keep1.calls)
        assertEquals("app: keep2 reached twice after two publishes", 2, keep2.calls)
        assertEquals("app: self-remover still called once (it removed itself)", 1, selfRemover.calls)
    }

    @Test
    fun `one publisher thread plus a remover never throws over 2000 cycles`() {
        val composite = CompositeLevelSink()
        val problems = CopyOnWriteArrayList<Throwable>()
        val done = AtomicBoolean(false)
        val publishes = AtomicInteger(0)
        val started = CountDownLatch(1)
        val firstPublish = CountDownLatch(1)
        // One permanent slow sink: each publish stays inside the loop long enough for the other thread to change the list.
        composite.add(LevelSink { Thread.yield() })

        val publisher = Thread {
            started.countDown()
            try {
                while (!done.get()) {
                    composite.publish(0.5f)
                    publishes.incrementAndGet()
                    firstPublish.countDown()
                }
            } catch (t: Throwable) {
                problems.add(t)
            }
        }
        publisher.isDaemon = true
        publisher.start()
        assertTrue("app: the publisher thread must start", started.await(5, TimeUnit.SECONDS))
        assertTrue("app: the publisher published at least once", firstPublish.await(5, TimeUnit.SECONDS))

        var previous: LevelSink? = null
        try {
            repeat(2000) {
                val s = RecordingSink()
                composite.add(s)
                previous?.let { composite.remove(it) }
                previous = s
            }
        } finally {
            done.set(true)
        }

        publisher.join(5000L)
        assertFalse("app: the publisher thread did not finish in time", publisher.isAlive)
        assertTrue("app: the publisher ran at least once", publishes.get() > 0)
        assertEquals("app: no throwable from either thread: ${problems.toList()}", 0, problems.size)
    }
}
