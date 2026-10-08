package dev.breaker.dictation.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A take that is replaced while its end call is still running.
 *
 * The end call of the first take parks, the caller gives up on it and starts a
 * second take, and only then does the first end call throw. The throw belongs
 * to a take nobody is listening to any more, so it must not end up in the
 * failure the second take reports.
 *
 * A failure means a late throw from a replaced take's end call was recorded as
 * the failure of the take that replaced it.
 */
class MicCaptureTakeEndReplacedTest {

    @Test
    fun `a replaced take whose end call throws late does not set the next take's failure`() {
        val source = FakeMicSource(script = speech(960), holdsOpenWhenScriptSpent = true)
        val calls = AtomicInteger(0)
        val inFirstEnd = CountDownLatch(1)
        val releaseFirstEnd = CountDownLatch(1)
        val lateThrow = IllegalStateException("late end call exploded")
        val capture = MicCapture(
            source = source,
            joinTimeoutMs = IndicatorTestSupport.STUCK_JOIN_TIMEOUT_MS,
            onTakeEnded = {
                if (calls.incrementAndGet() == 1) {
                    inFirstEnd.countDown()
                    releaseFirstEnd.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    throw lateThrow
                }
            },
        )
        val first = TakeEndRecorder()
        capture.start(first.frames())
        assertTrue(
            "audio: the script was never spent, so the test is not in the state it is about",
            source.scriptSpent.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        // stop() ends the first take; its dispatcher then parks inside the end call, so stop() gives up on it after the join bound.
        capture.stop()
        assertTrue(
            "audio: the first take's end call was never reached, so nothing is parked in it",
            inFirstEnd.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        val second = TakeEndRecorder()
        capture.start(second.frames())
        // The first take is now replaced; let its end call throw.
        releaseFirstEnd.countDown()
        capture.stop()
        awaitNoSessionThreads("the replaced take's end call was released and the second take stopped")

        assertEquals("audio: each take must have been told once", 2, calls.get())
        assertNull(
            "audio: the replaced take's late throw was recorded as the next take's failure: ${capture.failure}",
            capture.failure,
        )
    }
}
