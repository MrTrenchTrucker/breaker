package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a mic-indicator listener that throws owes the capture.
 *
 * The indicator is not bookkeeping. It is the only way a screen learns the
 * microphone is live, and its promise is one-directional and absolute: a bound
 * screen never shows "not recording" while the microphone is open, and never
 * shows "recording" once it is shut. A listener that throws — on Android, a
 * view touched off the main thread does exactly this — breaks that promise
 * twice over: the exception escapes with the session still up, and the
 * `forEach` that raised it never reaches the listeners after it, so a screen
 * bound to one of THOSE is left showing the wrong state for a microphone that
 * really is live.
 *
 * Both halves are load-bearing. Guarding the call site so the exception cannot
 * escape fixes the strand and leaves the second listener un-notified; notifying
 * every listener before re-raising fixes the second and leaves the strand.
 */
class MicCaptureIndicatorListenerThrowTest {

    @Test
    fun `a listener that throws on the way to recording does not strand the capture`() {
        // The strand this whole file is about. markRecordingStarted() is called
        // BEFORE the try that wraps source.open(), so an exception from a
        // listener leaves start() with running = true and the indicator live:
        // every later start() is refused as "already running", and every stop()
        // finds no session threads and returns, so the capture is stuck until
        // the process dies.
        //
        // Asserted as a BALANCE, not a single reading: after the throw the
        // device must not be open with no session to stop, and a start issued
        // after the offending listener has been removed must deliver a full
        // take. A capture that merely reported isCapturing == false while
        // leaving the device open would pass the first and fail the second.
        val script = silenceThenSpeech(totalMs = 400)
        // holdsOpenWhenScriptSpent, because this test reads the take to its
        // LAST sample before it stops: a device that reports nothing the moment
        // its script is spent makes the capture conclude on its own with a
        // give-up failure about silence, and that failure is recorded in the
        // very field the last assertion below reads as null. The assertion is
        // about the listener — "recovering from one broken listener is not
        // itself a failure" — so the device must not supply a second, unrelated
        // one while the take is being collected.
        val source = FakeMicSource(script = script, holdsOpenWhenScriptSpent = true)
        val indicator = RecordingIndicator()
        val capture = MicCapture(source = source, indicator = indicator)

        val threw = IllegalStateException("the view was touched off the main thread")
        // Throws only on the way UP. addListener calls the listener once
        // immediately with the current value, so a listener that threw on every
        // call would fault here in the fixture rather than inside start(), and
        // the test would be measuring its own setup. "recording" is the
        // transition that strands the capture, so that is the one it faults on.
        val offender: (Boolean) -> Unit = { recording ->
            if (recording) throw threw
        }
        indicator.addListener(offender)

        val startFailure = AtomicReference<Throwable?>(null)
        try {
            capture.start(AudioListener { })
            startFailure.set(null)
        } catch (e: Throwable) {
            startFailure.set(e)
        }

        // Whatever the caller's side looks like, the session cannot be left up.
        // Read as a balance so a "fixed" state that is really the strand cannot
        // pass: the flag down, the indicator down, and no device open with
        // nothing holding it.
        val stranded = "start threw ${startFailure.get()}, isCapturing " +
            "${capture.isCapturing}, indicator live ${indicator.isRecording}, " +
            "device open ${source.open}, opens ${source.openCalls}, " +
            "closes ${source.closeCalls}"

        assertFalse(
            "a throwing indicator listener left the session up: $stranded. " +
                "isCapturing true means every later start() is refused as " +
                "\"already running\" and every stop() finds no threads to join, " +
                "so the capture is stuck until the process dies",
            capture.isCapturing,
        )
        assertFalse(
            "a throwing indicator listener left the indicator live: $stranded. " +
                "The microphone was never opened by a session that survived, so a " +
                "bound screen shows \"recording\" for a microphone nobody holds",
            indicator.isRecording,
        )
        assertFalse(
            "a device was left open with no session holding it: $stranded",
            source.open,
        )
        assertEquals(
            "the device was opened but never closed: $stranded",
            0,
            source.closeCalls,
        )

        // And the exception reached somewhere real. Not swallowed: a caller that
        // only saw a null would believe the microphone opened cleanly.
        assertNotNull(
            "the listener's exception reached nobody: $stranded. It must be " +
                "reported to the caller of start() and recorded in failure, so " +
                "a broken listener is visible rather than silent",
            capture.failure,
        )
        assertTrue(
            "the recorded failure is not the listener's own: $stranded. It was " +
                "${capture.failure}",
            capture.failure === threw || capture.failure!!.message!!.contains("main thread"),
        )

        // The caller can recover: remove the broken listener and the next start
        // is a full take, not a refusal and not a take that came up short.
        indicator.removeListener(offender)
        val frames = CopyOnWriteArrayList<FloatArray>()
        // A signal, not a clock: wait for the EVENT — the whole take arriving
        // — so a loaded machine cannot fail a capture that is in fact
        // restarting. Counted down ONCE, and only at the cumulative sample
        // total, not per callback: the condition it replaces counted SAMPLES
        // across many frames, so a per-callback count down would fire on the
        // first one. Atomic counters, because this callback runs on the
        // capture's own thread; the CAS keeps a later frame from counting a
        // latch already at zero.
        val takeArrived = CountDownLatch(1)
        val samplesSeen = AtomicInteger(0)
        val takeSignalled = AtomicInteger(0)
        capture.start(AudioListener { frame ->
            frames.add(frame)
            samplesSeen.addAndGet(frame.size)
            if (samplesSeen.get() >= script.size &&
                takeSignalled.compareAndSet(0, 1)
            ) {
                takeArrived.countDown()
            }
        })
        // Bounded, because a capture that stops producing frames signals
        // nothing and must not hang the suite; generous, because that bound is
        // only the floor of a real failure and WAIT_SECONDS is the ceiling this
        // file already puts on any of its waits. The result is discarded on
        // purpose: the assertions below are the verdict and carry the message
        // that says what the capture delivered and why it matters, where an
        // assert here would fire first with a worse one and mask them.
        takeArrived.await(WAIT_SECONDS, TimeUnit.SECONDS)
        capture.stop()

        assertEquals(
            "the start after removing the throwing listener came to " +
                "${frames.sumOf { it.size }} samples but the device produced " +
                "the whole take, so the strand outlived the offending call: " +
                "a capture a caller cannot restart is one they never get back",
            // The whole of the script, read off the fixture rather than restated
            // as a frame count: 400 ms at 16 kHz is 6400 samples, which is 20
            // frames of 320 — the earlier 4-frame constant named a take five
            // times shorter than the device the test actually opened, so it
            // could not be satisfied by a capture that delivered the whole
            // thing. Read off the script it can only ever be right.
            script.size,
            frames.sumOf { it.size },
        )
        assertNull(
            "the capture reported a failure for a take that ran cleanly: " +
                "${capture.failure}. Recovering from one broken listener is not " +
                "itself a failure",
            capture.failure,
        )
    }

    @Test
    fun `every listener is told even when an earlier one throws`() {
        // The indicator's own promise is broken by the forEach this replaces:
        // it halts at the FIRST throwing listener, so a screen bound to a
        // listener registered after it is never told the microphone went live,
        // and is never told it stopped either. That screen shows "not
        // recording" for a live microphone, which is the exact abuse the
        // indicator exists to prevent.
        //
        // Both directions, because both are forEach over the same list: a
        // fix that only guards the way to true leaves the way to false broken,
        // and a screen that missed the true never sees the false either.
        val indicator = RecordingIndicator()
        val secondSaw = CopyOnWriteArrayList<Boolean>()
        val firstThrew = AtomicReference<Throwable?>(null)

        // Throws only on the way up, for the same reason as the other test: the
        // immediate call addListener makes would otherwise fault in the fixture
        // instead of inside markRecordingStarted, and the point here is what a
        // LATER listener is told, not how this one was registered.
        indicator.addListener { recording ->
            if (recording) throw IllegalStateException("the first listener is broken")
        }
        indicator.addListener { secondSaw.add(it) }

        val startFailure = AtomicReference<Throwable?>(null)
        val capture = MicCapture(
            source = FakeMicSource(script = silenceThenSpeech(totalMs = 400)),
            indicator = indicator,
        )
        try {
            capture.start(AudioListener { })
        } catch (e: Throwable) {
            startFailure.set(e)
        }
        firstThrew.set(startFailure.get())
        capture.stop()

        assertNotNull(
            "the first listener's exception reached nobody. It must not be " +
                "swallowed, or a broken listener is indistinguishable from a " +
                "screen that simply was not notified",
            firstThrew.get(),
        )
        assertEquals(
            "the listener registered AFTER the throwing one was told " +
                "[$secondSaw]. It must be told true on the way up and false on " +
                "the way down, whatever an earlier listener did: the forEach " +
                "halting at the first throw is what left this screen showing " +
                "\"not recording\" for a live microphone",
            // The registration call counts, and it is the contract rather than
            // an artefact: addListener tells a new listener the current value
            // immediately, so a screen binding after a mark is not left showing
            // the previous state until the next transition. So this listener
            // saw false (registered while dark), then true (the way up, past
            // the throwing one), then false (the way down). Asserting the list
            // from the first registration is what makes the middle entry a
            // statement about the forEach: dropping it is exactly the bug.
            listOf(false, true, false),
            secondSaw.toList(),
        )
        assertFalse(
            "the indicator is still live after start() and stop() with a " +
                "throwing listener in front",
            indicator.isRecording,
        )
    }
}
