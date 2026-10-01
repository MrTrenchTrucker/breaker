package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tearing a session down: that [MicCapture.stop] releases the device, the
 * indicator and both threads exactly once, and that a take is whole by the time
 * it returns.
 */
class MicCaptureStopTest {

    @Test
    fun `stop releases a capture that ended by itself, and delivers the tail`() {
        // The capture that ends by itself is the one a caller is most likely
        // to get wrong, because everything the caller can see says the job is
        // done: isCapturing is false, the frames it asked for have arrived. It
        // is not done. The device is still open, the indicator is still live,
        // and the take's last frame is still sitting in the dispatcher behind a
        // read that has not returned yet.
        //
        // So this stops a capture that has ended by itself and reads the state
        // at the instant stop() returns — no settling, no sleeping afterwards,
        // because "everything is released by the time stop() returns" is the
        // whole claim. A stop() that returns first and lets the session finish
        // in the background looks identical from here except that the tail
        // arrives a poll later and the device is never closed at all.
        val indicator = RecordingIndicator()
        val indicatorChanges = CopyOnWriteArrayList<Boolean>()
        val source = FakeMicSource(script = speech(TAIL_SAMPLES))
        val capture = MicCapture(
            source = source,
            indicator = indicator,
            frameSamples = FRAME_SAMPLES,
        )
        val sizes = CopyOnWriteArrayList<Int>()

        // Taken before the session exists, so a thread this test's own capture
        // started and stop() left running cannot be subtracted out of the
        // comparison below.
        val liveBeforeStart = liveSessionThreads()
        capture.start(AudioListener { sizes.add(it.size) })
        indicator.addListener { indicatorChanges.add(it) }
        awaitSelfEnded(capture)
        val framesAtSelfEnd = sizes.size

        capture.stop()

        // Everything a caller would be left with if stop() returned before the
        // session was down, gathered into the first failure's message: a stop()
        // that does nothing reports an open device and a live indicator here,
        // and a caller reading only the tail would not know the other two.
        val teardownState = "device closed ${source.closeCalls}x (open=${source.open}), " +
            "indicator changes $indicatorChanges, live session threads " +
            "${(liveSessionThreads() - liveBeforeStart).map { it.name }}"

        // The tail is inside the caller's timeline: stop() joined the
        // dispatcher, so the last frame of the take has been delivered by the
        // time it returns rather than being on its way.
        assertEquals(
            "stop() returned with the frames that had already arrived ($sizes) rather " +
                "than the whole take. The capture had ended by itself $framesAtSelfEnd " +
                "frames in, so the tail was still behind the dispatcher's read, and " +
                "stop() has to join that dispatcher to deliver it. At that moment: " +
                teardownState,
            listOf(FRAME_SAMPLES, TAIL_SAMPLES - FRAME_SAMPLES),
            sizes.toList(),
        )
        assertEquals(
            "the take delivered ${sizes.sum()} samples but the device produced " +
                "$TAIL_SAMPLES, so the tail was either padded or lost audio: $sizes",
            TAIL_SAMPLES,
            sizes.sum(),
        )
        // Corroboration only, not the load-bearing check. liveSessionThreads()
        // matches two thread names across the whole JVM rather than threads
        // this session owns, so a straggler from a different test running at
        // the same time is indistinguishable from one stop() left behind:
        // the check is not hermetic against a foreign thread. What carries
        // this test is the close()/markRecordingStopped counters and the frame
        // assertions above — the counters are this session's own, and the
        // frames are the take. The thread scan agrees with them when nothing
        // foreign is in flight, and is not what the test stands on.
        //
        // When it does fire, the dispatcher is the one worth naming — it is
        // the thread still holding the tail, and it is the one a stop() that
        // only looked at whether the device was still being read would leave
        // running.
        val liveAfterStop = liveSessionThreads() - liveBeforeStart
        assertTrue(
            "stop() returned with these session threads still running: " +
                "${liveAfterStop.map { it.name }}. A session thread left running is a " +
                "device that may still be open and a take that is still growing",
            liveAfterStop.isEmpty(),
        )
        // The device is shut. A counter rather than the source's own flag,
        // because a source closed twice looks exactly like one closed once.
        assertEquals(
            "the device was closed ${source.closeCalls} times; a self-ended capture " +
                "leaves the microphone open until something closes it, and stop() is " +
                "the only thing that does",
            1,
            source.closeCalls,
        )
        assertFalse(
            "the source still reports itself open after stop() returned",
            source.open,
        )
        // And the indicator is dark, having said "recording" exactly once and
        // "not recording" exactly once for one session.
        assertEquals(
            "the indicator reported $indicatorChanges for one session. It goes live " +
                "at start and dark at teardown, and a screen that binds to it is " +
                "told a session ended exactly when it did",
            listOf(true, false),
            indicatorChanges.toList(),
        )
        assertFalse(
            "the indicator was still live after stop() returned; a self-ended " +
                "capture that never marks itself dark shows \"recording\" forever",
            indicator.isRecording,
        )
    }

    @Test
    fun `a stop that lands while the device is opening leaves nothing running`() {
        // stop() is documented as safe at any point in a capture's life, and
        // the one point it cannot act on directly is inside start(): while the
        // device is still opening there is no capture thread and no dispatcher
        // to take, so there is nothing to join and nothing to null out. A stop
        // that only ever looks for threads finds none, returns, and the start()
        // it was racing goes on to spin the whole session up behind the
        // caller's back — running stays true, the device stays open, the
        // indicator stays live, and audio the caller believes it stopped is
        // delivered to the listener it gave to start().
        //
        // So the stop is a recorded fact rather than an action, and the start()
        // that was inside open() acts on it the moment the device comes back:
        // the source is closed, the indicator is marked dark, no thread is
        // spawned and no frame is delivered. It returns quietly rather than
        // throwing, because the caller issued two legal calls and did nothing
        // wrong; a throw here would punish the caller for a race the
        // implementation is supposed to absorb.
        //
        // The wait afterwards is a ceiling, not a hope: the take would be
        // delivered promptly if the stop were lost, so waiting for a frame (or
        // for the session to end) before asserting that none arrived is what
        // makes the failure a statement about the race rather than about how
        // fast this machine is.
        val indicator = RecordingIndicator()
        val source = OpenGateSource(script = silenceThenSpeech(totalMs = 500))
        val capture = MicCapture(source = source, indicator = indicator)
        val frames = CopyOnWriteArrayList<FloatArray>()
        val startFailure = AtomicReference<Throwable?>(null)
        val startReturned = CountDownLatch(1)

        val starter = Thread({
            try {
                capture.start(AudioListener { frames.add(it) })
            } catch (e: Throwable) {
                startFailure.set(e)
            } finally {
                startReturned.countDown()
            }
        }, "open-race-starter")
        starter.start()

        assertTrue(
            "the device was never reached by open(), so this test never got into " +
                "the window it is about",
            source.insideOpen.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        capture.stop()
        source.releaseOpen.countDown()

        assertTrue(
            "start() never returned after the device opened, so the state this " +
                "test reads is not the end of the start at all",
            startReturned.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        assertNull(
            "start() threw for a stop the caller was entitled to make. Both calls " +
                "were legal, so the racing start absorbs the stop and returns " +
                "quietly",
            startFailure.get(),
        )

        val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (frames.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }

        // All three halves, in one message: a stop lost in this window shows up
        // as a session that is still running, and a reader told only that the
        // device was still open would not know the audio was still flowing.
        val racedState = "frames ${frames.size} (${frames.sumOf { it.size }} samples), " +
            "device closed ${source.closeCalls}x (open=${source.delegate.open}), " +
            "indicator live ${indicator.isRecording}, still capturing ${capture.isCapturing}"

        assertTrue(
            "a take was delivered after stop() returned: $racedState. The stop " +
                "landed while the device was opening, so the session must never " +
                "have been started",
            frames.isEmpty(),
        )
        assertEquals(
            "the device was closed ${source.closeCalls} times after a stop that " +
                "landed while it was opening; it must be closed exactly once, by " +
                "the start() that opened it: $racedState",
            1,
            source.closeCalls,
        )
        assertFalse(
            "the device is still open after a stop that landed while it was " +
                "opening: $racedState",
            source.delegate.open,
        )
        assertFalse(
            "the indicator is still live after a stop that landed while the " +
                "device was opening, so a screen bound to it shows \"recording\" " +
                "for a microphone nobody is using: $racedState",
            indicator.isRecording,
        )
        assertFalse(
            "isCapturing is still true after a stop that landed while the device " +
                "was opening: $racedState",
            capture.isCapturing,
        )
    }

    @Test
    fun `stop called twice releases the session once`() {
        // Teardown resources are released once. A caller that stops twice —
        // which is easy to write, since stop() is documented as safe to call
        // when there is nothing to stop — must not close a device that is
        // already shut or announce a second end to a session that has already
        // ended.
        val indicator = RecordingIndicator()
        val indicatorChanges = CopyOnWriteArrayList<Boolean>()
        val source = FakeMicSource(script = silenceThenSpeech(totalMs = 500))
        val capture = MicCapture(source = source, indicator = indicator)

        capture.start(AudioListener { })
        indicator.addListener { indicatorChanges.add(it) }
        capture.stop()
        capture.stop()

        assertEquals(
            "the device was closed ${source.closeCalls} times across two stop() " +
                "calls; a teardown that runs twice releases the same resources twice",
            1,
            source.closeCalls,
        )
        assertEquals(
            "the indicator reported $indicatorChanges across two stop() calls; the " +
                "second stop() found no session to tear down and should have said " +
                "nothing",
            listOf(true, false),
            indicatorChanges.toList(),
        )
        assertFalse(capture.isCapturing)
    }

    @Test
    fun `stop before start does nothing at all`() {
        // There is no session here, so there is nothing to release. stop() is
        // safe to call on a capture that was never opened, which means it must
        // not close a device that was never opened or mark an indicator that
        // was never live.
        val indicator = RecordingIndicator()
        val indicatorChanges = CopyOnWriteArrayList<Boolean>()
        val source = FakeMicSource(script = silenceThenSpeech(totalMs = 500))
        val capture = MicCapture(source = source, indicator = indicator)
        indicator.addListener { indicatorChanges.add(it) }

        capture.stop()

        assertEquals(
            "the device was opened ${source.openCalls} times and closed " +
                "${source.closeCalls} times by a stop() that had no session to tear " +
                "down; there was never a microphone open to close",
            0,
            source.closeCalls,
        )
        assertEquals(
            "stop() on a capture that never started reported $indicatorChanges; a " +
                "session that never began cannot end",
            listOf(false),
            indicatorChanges.toList(),
        )
        assertFalse(indicator.isRecording)
        assertFalse(capture.isCapturing)
    }

    @Test
    fun `stop is safe to call when not capturing`() {
        val capture = MicCapture(FakeMicSource(script = speech(320)))
        capture.stop()
        capture.stop()
        assertFalse(capture.isCapturing)
    }

    @Test
    fun `stop is safe to call twice while capturing`() {
        val capture = MicCapture(FakeMicSource(script = silenceThenSpeech(totalMs = 500)))
        capture.start(AudioListener { })
        capture.stop()
        capture.stop()
        assertFalse(capture.isCapturing)
    }

    @Test
    fun `no frames arrive after stop returns`() {
        val capture = MicCapture(FakeMicSource(script = silenceThenSpeech(totalMs = 400)))
        val frames = collectFrames(capture, expectedFrames = 14)
        val countAtStop = frames.size
        Thread.sleep(120)
        assertEquals(
            "frames kept arriving after stop(): $countAtStop then ${frames.size}",
            countAtStop,
            frames.size,
        )
    }

    @Test
    fun `starting twice is refused rather than opening the microphone twice`() {
        val capture = MicCapture(FakeMicSource(script = silenceThenSpeech(totalMs = 500)))
        capture.start(AudioListener { })
        try {
            try {
                capture.start(AudioListener { })
                fail("expected a second start to be refused")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "the message should say to stop first, was: ${e.message}",
                    e.message!!.contains("stop()"),
                )
            }
        } finally {
            capture.stop()
        }
    }

    @Test
    fun `a capture can be started again after it was stopped`() {
        val capture = MicCapture(FakeMicSource(script = silenceThenSpeech(totalMs = 300)))
        val first = collectFrames(capture, expectedFrames = 12)
        val second = collectFrames(capture, expectedFrames = 12)
        assertTrue("the first capture produced no frames", first.isNotEmpty())
        assertTrue("the second capture produced no frames", second.isNotEmpty())
        assertEquals("a fresh capture should not replay the first one", first.size, second.size)
    }

    @Test
    fun `a restarted capture is a full take of its own, not the last one's leftovers`() {
        // The scenario that separates a reusable capture from one that only
        // works once: a listener that is still inside onFrame when stop()
        // gives up waiting. stop() is allowed to return with that dispatcher
        // alive, and it is blocked in a read of the ring buffer. If the next
        // take shares that buffer, its writes wake the straggler, the
        // straggler takes a read of the new take's audio, and the new take
        // comes up short by exactly that much — two takes, one of them with a
        // hole in it.
        //
        // The two takes are given different audio, because a device that
        // replays the same script makes the two takes identical and any
        // leftover is then indistinguishable from the new take's own tail.
        // Take 1 is all positive, take 2 is all negative, so a single sample
        // of the wrong sign in either take is provably the other take's.
        val firstScript = FloatArray(64_000) { 0.4f }
        // Take 2 is exactly a whole number of frames, so "every sample of take
        // 2 arrived" is a statement about the take and not about where the
        // test chose to stop reading it.
        val secondScript = FloatArray(12 * MicCapture.DEFAULT_FRAME_SAMPLES) { -0.4f }
        val release = CountDownLatch(1)
        val insideListener = CountDownLatch(1)
        val firstFrames = CopyOnWriteArrayList<FloatArray>()
        val secondFrames = CopyOnWriteArrayList<FloatArray>()
        val source = FakeMicSource(script = firstScript)
        val capture = MicCapture(source = source, joinTimeoutMs = 50L)

        capture.start(AudioListener { frame ->
            firstFrames.add(frame)
            insideListener.countDown()
            release.await(10, TimeUnit.SECONDS)
        })
        try {
            assertTrue(
                "take 1 never reached the listener, so nothing was left running at stop()",
                insideListener.await(10, TimeUnit.SECONDS),
            )
            capture.stop()

            assertNotNull(
                "the listener was still inside onFrame when stop() returned, so this " +
                    "test is not exercising the path where a session thread outlives " +
                    "stop()",
                capture.failure,
            )

            source.scriptOnOpen = secondScript
            capture.start(AudioListener { secondFrames.add(it) })
            val framesAtRestart = firstFrames.size

            // Only now is take 1's listener allowed to return. On a capture
            // that shares its buffer, it comes back into a loop that reads the
            // buffer take 2 is filling.
            release.countDown()

            val wanted = secondScript.size
            val deadline = System.currentTimeMillis() + 10_000
            while (secondFrames.sumOf { it.size } < wanted && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            capture.stop()

            assertEquals(
                "the first capture's listener kept being called after the second " +
                    "capture started: $framesAtRestart frames at the restart, " +
                    "${firstFrames.size} after it",
                framesAtRestart,
                firstFrames.size,
            )
            val firstResidue = firstFrames.sumOf { frame -> frame.count { it < -0.1f } }
            assertEquals(
                "the first capture's listener was handed $firstResidue samples of the " +
                    "second take's audio",
                0,
                firstResidue,
            )

            // Take 2 must be a whole take of its own: not a hole where the
            // straggler swallowed a read, and not the previous take's tail.
            val secondResidue = secondFrames.sumOf { frame -> frame.count { it > 0.1f } }
            assertEquals(
                "the second capture was handed $secondResidue samples of the first " +
                    "take's audio. Each take is a single stretch of one sign, so any " +
                    "sample of the other sign is the other take's",
                0,
                secondResidue,
            )

            // Every sample the device produced for take 2 must arrive. Nothing
            // drops here — the listener keeps up and the ring holds the whole
            // take — so a take that arrives short lost audio to a thread that
            // had no business reading it. The shortfall is the straggler's
            // read size, so this fails by whole reads rather than by a sample
            // or two of timing.
            val secondSamples = secondFrames.sumOf { it.size }
            assertEquals(
                "the second capture delivered $secondSamples samples but its device " +
                    "produced ${secondScript.size}, so ${
                        secondScript.size - secondSamples
                    } samples of the take were lost rather than delivered",
                secondScript.size,
                secondSamples,
            )
        } finally {
            release.countDown()
            capture.stop()
        }
    }
}
