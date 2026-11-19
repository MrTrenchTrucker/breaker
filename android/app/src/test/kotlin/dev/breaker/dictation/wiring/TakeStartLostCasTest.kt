package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.TakeStartAnchor
import dev.breaker.dictation.commit.TakeStartWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * A microphone end that loses the session compare-and-set must leave the held window alone.
 *
 * The end reads the generation of the take it is ending. While it is reading, that take is finished
 * on the same thread, so the session has already moved and the end's compare-and-set loses. The
 * window now belongs to the live commit that finish produced, and dropping it would leave that
 * commit with no window to check against the field it is about to write. Only an end that wins the
 * compare-and-set may clear, and only against the generation it read.
 *
 * One named thread. The finish runs inside the generation step on that same thread, so the ordering
 * is fixed rather than raced, and the join is bounded so a regression fails the run instead of
 * hanging it. A failure inside the thread is carried back to the test thread.
 */
class TakeStartLostCasTest {

    @After
    fun clearTheAnchor() {
        TakeStartAnchor.clear()
    }

    @Test
    fun `an end that loses the session compare-and-set leaves the held window alone`() {
        TakeStartAnchor.clear()

        val held = TakeStartWindow(windowId = 903, packageName = "com.lostcas.take")

        lateinit var rig: Rig
        val finishSeen = AtomicReference<FinishResult>()
        val recorder: TakeStartRecorder = object : TakeStartRecorder {
            override fun capture() {
                TakeStartAnchor.capture(held)
            }
            override fun generation(): Long {
                // Finishing here moves the session before the end's compare-and-set, so the end
                // loses it. The engine fake answers Success, and that path leaves the anchor alone.
                finishSeen.set(rig.runner.finish())
                return TakeStartAnchor.generation()
            }
            override fun clearIf(generation: Long) {
                TakeStartAnchor.clearIfGeneration(generation)
            }
            override fun clear() {
                TakeStartAnchor.clear()
            }
        }
        rig = Rig(takeStart = recorder)

        assertEquals("app: the first begin records", BeginResult.Recording, rig.runner.begin())
        // Speech, so the finish has audio to transcribe: an empty capture finishes as a failure,
        // and that path clears the anchor, which would prove nothing here.
        rig.speak()
        assertEquals("app: the first take captured its window", held, TakeStartAnchor.current())

        val endFailure = AtomicReference<Throwable>()
        val end = Thread({
            try {
                rig.runner.onCaptureEnded()
            } catch (t: Throwable) {
                endFailure.set(t)
            }
        }, "lostcas-end")
        end.start()
        end.join(10_000)
        assertFalse("app: the end finished in time", end.isAlive)
        assertNull("app: the end threw", endFailure.get())

        // Pin the path: a finish that failed or ended elsewhere would have cleared the anchor itself.
        assertTrue("app: the re-entrant finish finished the take", finishSeen.get() is FinishResult.ReadyToSend)
        assertEquals("app: a lost session compare-and-set leaves the held window alone", held, TakeStartAnchor.current())
    }
}
