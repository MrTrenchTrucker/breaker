package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.TakeStartAnchor
import dev.breaker.dictation.commit.TakeStartWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The generation a self-ended capture clears against is read before the session moves, not after.
 *
 * A thread ends a capture and parks inside the recorder's generation step, before the session has
 * moved back to idle. While it is parked, a second begin on the test thread must be refused as busy
 * because the first take is still recording. If the generation were read after the session moved,
 * the session would already be idle, that second begin would record, and the fresh window it
 * captured would be the one the late clear then drops.
 *
 * One named thread and two latches, every wait bounded and asserted, no sleeps and no new processes.
 */
class TakeStartSnapshotOrderTest {

    @After
    fun clearTheAnchor() {
        TakeStartAnchor.clear()
    }

    @Test
    fun `a begin arriving while the end reads its generation is refused as busy`() {
        TakeStartAnchor.clear()

        // The window the first take captures.
        val firstWindow = TakeStartWindow(windowId = 902, packageName = "com.snapshot.take")

        // The end parks inside the generation step: entered, then released by the test thread.
        val genEntered = CountDownLatch(1)
        val releaseGen = CountDownLatch(1)
        val recorder: TakeStartRecorder = object : TakeStartRecorder {
            override fun capture() {
                TakeStartAnchor.capture(firstWindow)
            }
            override fun generation(): Long {
                genEntered.countDown()
                assertTrue("app: the generation step was released in time", releaseGen.await(10, TimeUnit.SECONDS))
                return TakeStartAnchor.generation()
            }
            override fun clearIf(generation: Long) {
                TakeStartAnchor.clearIfGeneration(generation)
            }
            override fun clear() {
                TakeStartAnchor.clear()
            }
        }
        val rig = Rig(takeStart = recorder)

        // A first take is recording and holds the anchor.
        assertEquals("app: the first begin records", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the first take captured its window", firstWindow, TakeStartAnchor.current())

        val endFailure = AtomicReference<Throwable>()

        // Ends the capture and parks inside the generation step, before the session moves.
        val end = Thread({
            try {
                rig.runner.onCaptureEnded()
            } catch (t: Throwable) {
                endFailure.set(t)
            }
        }, "snapshot-end")
        end.start()

        assertTrue("app: the end reached its generation step in time", genEntered.await(10, TimeUnit.SECONDS))
        assertEquals("app: the first window is still held while the end reads its generation", firstWindow, TakeStartAnchor.current())

        // The session has not moved, so this begin is refused before it captures anything. A begin
        // that recorded here would mean the session moved before the generation was read.
        assertEquals(
            "app: a begin arriving before the session moves is refused as busy",
            BeginResult.Refused(RunnerSentences.BUSY),
            rig.runner.begin(),
        )

        // Release the end: it reads the generation it snapshotted and clears only that one.
        releaseGen.countDown()
        end.join(10_000)
        assertFalse("app: the end finished in time", end.isAlive)
        assertNull("app: the end threw", endFailure.get())
    }
}
