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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window [MicCapture.start] opens inside [MicSource.open], and what a
 * [MicCapture.stop] that lands in it owes the caller.
 *
 * The class promises two things that this window is the only place can break:
 * that a stop which has RETURNED has stopped the capture, and that start, stop,
 * start again is a second full take. A device takes long enough to open — tens
 * of milliseconds on a phone, a permission prompt on a cold start — that the
 * window is real, and a stop landing in it is a legal pair of calls rather than
 * a caller mistake.
 */
class MicCaptureStartStopRaceTest {

    @Test
    fun `a stop that lands inside the device's opening stops the capture before it returns`() {
        // The residual hole a stop() that only looks for session threads cannot
        // close. While the device is opening there is no capture thread and no
        // dispatcher, so a stop finds nothing to take, and if it RETURNS before
        // it has dropped the session flag then it returns having stopped
        // nothing: isCapturing still reads true, and the next start() is refused
        // as "already running" by a session this very stop ended. From the
        // caller's side it did exactly what it asked, and got a capture it
        // cannot restart.
        //
        // So the racing open() is released about a fifth of a second AFTER the
        // stop comes back, and the assertions run at that moment rather than
        // after the open has come back. An assertion that waited for the open
        // would pass against the old code too, which is the whole point: the
        // state has to be read while the hole is still open.
        //
        // No sleep decides the outcome. The gate release is a third thread on a
        // timer, the "stop has returned" edge is a latch, and the only wait for
        // the take is a bounded poll that ends as soon as the frames land.
        val source = GatedSource(script = speech(FULL_TAKE_SAMPLES))
        val capture = MicCapture(source = source)
        val frames = CopyOnWriteArrayList<FloatArray>()

        Thread({ capture.start(AudioListener { frames.add(it) }) }, "race-starter").start()

        assertTrue(
            "the device was never reached by open(), so this test never got into " +
                "the window it is about",
            source.insideOpen.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        // The stop, and the edge that matters: everything after this latch is
        // read after stop() has RETURNED.
        val stopReturned = CountDownLatch(1)
        val capturingWhenStopReturned = AtomicReference<Boolean?>(null)
        Thread({
            capture.stop()
            capturingWhenStopReturned.set(capture.isCapturing)
            stopReturned.countDown()
        }, "race-stopper").start()

        assertTrue(
            "stop() never returned; the racing open() is holding the session",
            stopReturned.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        Thread({
            Thread.sleep(GATE_RELEASE_MS)
            source.releaseOpen.countDown()
        }, "open-gate-release").start()

        // The claim, at the instant it is claimed. The value is read by the
        // stop thread itself and only published after it has been read, so the
        // latch below makes it a value and not an absence.
        val capturingAtStopReturn = capturingWhenStopReturned.get()
        assertNotNull(
            "the stop thread returned without recording what isCapturing read at " +
                "that moment, so the claim below cannot be made at all",
            capturingAtStopReturn,
        )
        assertFalse(
            "stop() returned and isCapturing still reads true, so the session it " +
                "tore down is still up: a capture the caller has been told it " +
                "stopped cannot be restarted, because the next start() is refused " +
                "as \"already running\" until the racing open() comes back " +
                "(${GATE_RELEASE_MS}ms away). The stop found no session thread to " +
                "take, because the device was still opening, so it has to drop " +
                "the flag on its way out regardless.",
            capturingAtStopReturn == true,
        )

        // And the caller can act on it: the start issued after that stop is a
        // full take, not a refusal and not a take that came up short.
        val startFailure = AtomicReference<Throwable?>(null)
        Thread({
            try {
                capture.start(AudioListener { frames.add(it) })
            } catch (e: Throwable) {
                startFailure.set(e)
            }
        }, "race-restart").start()

        val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (frames.sumOf { it.size } < FULL_TAKE_SAMPLES &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(5)
        }
        capture.stop()

        assertNull(
            "start() after a stop that had returned threw: ${startFailure.get()}. " +
                "The stop ended a session, so the next start must not be told the " +
                "capture is already running",
            startFailure.get(),
        )
        assertEquals(
            "the take after the restart came to ${frames.sumOf { it.size }} samples " +
                "but the device produced $FULL_TAKE_SAMPLES, so it is not a full " +
                "take of its own",
            FULL_TAKE_SAMPLES,
            frames.sumOf { it.size },
        )
        assertNull(
            "the capture reported a failure for a take that ran cleanly: " +
                "${capture.failure}. Waiting for the previous device's open to " +
                "finish is not a failure",
            capture.failure,
        )
    }

    @Test
    fun `a start that passes the running flag while an earlier open is in flight waits for the device`() {
        // One microphone, one open at a time — on the path where two starts
        // genuinely overlap.
        //
        // The obvious way to test this does not test it. A second start issued
        // while the first is still CAPTURING is refused by the running flag in
        // MicCapture.start(), before it ever reaches openAndPublish, the device
        // gate or source.open(). So the check is vacuous: the microphone was
        // never shared because the second start never got near it, and the
        // assertion holds on code that has no device gate at all.
        //
        // What genuinely overlaps is a THIRD start, separated from the first by
        // a stop. The stop drops the running flag, so the third start passes the
        // flag and reaches openAndPublish with the FIRST start still inside its
        // own source.open(). The two are now at the device at the same moment,
        // and what has to hold them apart is the gate rather than the flag.
        //
        // The order is made deterministic rather than timed. The source holds
        // its open until this test releases it, so "the first start is inside
        // open()" is a latch rather than a hope, and the stop in the middle is
        // issued and observed to return before the third start exists at all.
        // Inside the source, every open's entry and exit is recorded against
        // the thread that made it and the peak overlap counted, so "the third
        // start did not enter open() until the first had left it" is read off
        // what the device saw rather than inferred from how long anyone slept.
        val source = GatedSource(script = speech(FULL_TAKE_SAMPLES))
        // Generous, so the third start's wait on the gate is bounded by this
        // test releasing the first open and not by the give-up firing. The first
        // open is held for the negative window and released straight after, so
        // this ceiling is never approached.
        val capture = MicCapture(source = source, joinTimeoutMs = GATE_WAIT_MS)

        val firstStart = AtomicReference<Throwable?>(null)
        val first = Thread({
            try {
                capture.start(AudioListener { })
            } catch (e: Throwable) {
                firstStart.set(e)
            }
        }, "c-open-first")
        first.start()
        assertTrue(
            "the first start never reached open(), so the race window never opened",
            source.insideOpen.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        // The stop in the middle. It has to RETURN, and it has to drop the
        // running flag — otherwise the third start is refused by the flag and
        // this test has measured the vacuous path it exists to replace. Both
        // are observed rather than assumed.
        val stopReturned = CountDownLatch(1)
        val capturingAfterStop = AtomicReference<Boolean?>(null)
        Thread({
            capture.stop()
            capturingAfterStop.set(capture.isCapturing)
            stopReturned.countDown()
        }, "c-open-stopper").start()
        assertTrue(
            "the stop() that landed inside the first open never returned; the " +
                "first start is still holding the device and there is no overlap " +
                "to test",
            stopReturned.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        assertEquals(
            "stop() returned while isCapturing still read true, so the running " +
                "flag was not dropped and the third start will be refused by the " +
                "flag instead of reaching the device",
            false,
            capturingAfterStop.get(),
        )

        // The third start. Its frames are collected, because a gate that
        // refused every start outright would satisfy the window below and
        // deliver nothing.
        val frames = CopyOnWriteArrayList<FloatArray>()
        val thirdStart = AtomicReference<Throwable?>(null)
        val thirdFinished = CountDownLatch(1)
        Thread({
            try {
                capture.start(AudioListener { frames.add(it) })
            } catch (e: Throwable) {
                thirdStart.set(e)
            }
            thirdFinished.countDown()
        }, "c-open-third").start()

        // A bounded window in which the third start must NOT reach the device:
        // the first is provably still inside its own open, so an open here is
        // two on one microphone. Polled rather than slept — the window ends the
        // moment a second open appears, so a bypassed gate is caught at once
        // instead of at the end of a fixed delay. Nothing here decides the
        // outcome; it only ends the window.
        val windowDeadline = System.currentTimeMillis() + NO_OVERLAP_WINDOW_MS
        while (source.openCalls.get() < 2 && System.currentTimeMillis() < windowDeadline) {
            Thread.sleep(2)
        }
        val opensDuringFirstOpen = source.openCalls.get()
        val firstOpenStillHeldIt = source.insideOpenInProgress

        // Now let the first open finish, and the third start have the device.
        source.releaseOpen.countDown()
        first.join(WAIT_SECONDS * 1000L)
        val takeDeadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (frames.sumOf { it.size } < FULL_TAKE_SAMPLES &&
            System.currentTimeMillis() < takeDeadline
        ) {
            Thread.sleep(5)
        }
        capture.stop()

        assertTrue(
            "the first start had already left open() before the window opened, so " +
                "there was never an overlap for the third start to miss and the " +
                "window proved nothing (events ${source.openEvents})",
            firstOpenStillHeldIt,
        )
        assertEquals(
            "the third start reached source.open() while the first was still inside " +
                "its own open: ${source.openCalls.get()} opens for two starts that both " +
                "got past the running flag, entry/exit order ${source.openEvents}. Two " +
                "opens in flight on one source means two captures reading it, and " +
                "whichever start loses the race to teardown closes the device out from " +
                "under the winner's take. The running flag cannot be what stopped this, " +
                "because the stop() above already dropped it and the third start got " +
                "past it.",
            1,
            opensDuringFirstOpen,
        )
        assertEquals(
            "the device saw a peak of ${source.peakOpenConcurrency.get()} opens inside " +
                "it at once (events ${source.openEvents}); one microphone has one open at " +
                "a time",
            1,
            source.peakOpenConcurrency.get(),
        )
        assertTrue(
            "the third start never finished, so the overlap check proved nothing",
            thirdFinished.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        assertNull(
            "the first start threw ${firstStart.get()}. A start whose device was taken " +
                "away by a stop has to unwind quietly rather than fault the caller for a " +
                "race it did not cause",
            firstStart.get(),
        )
        assertNull(
            "the third start() threw ${thirdStart.get()}. A stop separated it from the " +
                "first start, so it is a second full take and not a refusal",
            thirdStart.get(),
        )
        assertEquals(
            "the third start delivered ${frames.sumOf { it.size }} samples but the device " +
                "produced $FULL_TAKE_SAMPLES, so waiting for the gate cost it audio or it " +
                "never really opened the device at all",
            FULL_TAKE_SAMPLES,
            frames.sumOf { it.size },
        )
        assertNull(
            "the capture reported a failure for a take that ran cleanly: " +
                "${capture.failure}. Waiting for the previous open to finish is not a " +
                "failure, and a gate that reported the give-up instead of serialising " +
                "the opens would be failing a caller for waiting a moment",
            capture.failure,
        )
        assertFalse(
            "isCapturing is true after the third start's session was stopped",
            capture.isCapturing,
        )
    }

    @Test
    fun `a start that never gets the device gives up in bounded time and says so`() {
        // A device whose open() never returns must not turn the next start into
        // a wait forever. The give-up is reported the way a stuck session
        // thread is reported — through failure, where a caller reads it — so a
        // caller that silently got no take and no explanation is not left
        // guessing.
        val source = GatedSource(script = speech(FULL_TAKE_SAMPLES))
        val capture = MicCapture(source = source, joinTimeoutMs = GIVE_UP_TIMEOUT_MS)

        Thread({ capture.start(AudioListener { }) }, "d-stuck-starter").start()
        assertTrue(
            "the first start never reached open(), so there is no hung device to " +
                "give up on",
            source.insideOpen.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        // Answerable while a start is inside the open — that is the whole of the
        // first test, and a give-up that made stop() block here would trade one
        // hang for another.
        capture.stop()

        val secondStart = AtomicReference<Throwable?>(null)
        val gaveUpAfterMs = System.currentTimeMillis()
        Thread({
            try {
                capture.start(AudioListener { })
            } catch (e: Throwable) {
                secondStart.set(e)
            }
        }, "d-giver-upper").start()
        val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (secondStart.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        val elapsed = System.currentTimeMillis() - gaveUpAfterMs

        source.releaseOpen.countDown()

        assertNotNull(
            "the second start() neither got the device nor gave up after " +
                "${elapsed}ms; a hung open() must be bounded by joinTimeoutMs " +
                "($GIVE_UP_TIMEOUT_MS), not waited on forever",
            secondStart.get(),
        )
        assertTrue(
            "the give-up took ${elapsed}ms, which is not bounded by " +
                "joinTimeoutMs ($GIVE_UP_TIMEOUT_MS)",
            elapsed < GIVE_UP_TIMEOUT_MS * 20,
        )
        assertNotNull(
            "the give-up was not recorded in failure, so a caller reading failure " +
                "would see a null and believe the last take ended cleanly",
            capture.failure,
        )
        assertFalse(
            "isCapturing is true after a start that gave up and a stop that had " +
                "already returned",
            capture.isCapturing,
        )
    }

    @Test
    fun `the stop that loses the race does not return before the teardown is done`() {
        // Two stops at once are legal — a caller that stops twice is easy to
        // write, and the class says stop is safe at any point. Exactly one of
        // them does the teardown. The other must not return early: a caller that
        // reads "stop() returned" as "the microphone is shut" and then starts a
        // session of its own is racing a join it was told had finished.
        //
        // Made deterministic by holding the teardown open rather than by timing
        // it. The listener is parked inside onFrame, so the winning stop() is
        // provably inside its join (the device is closed by then) and stays
        // there until this test releases it. The losing stop() is started only
        // after that is observable, so which stop wins is not in question: it is
        // the one already inside the teardown.
        val source = FakeMicSource(script = silenceThenSpeech(totalMs = 500))
        val release = CountDownLatch(1)
        val parked = CountDownLatch(1)
        val capture = MicCapture(source = source, joinTimeoutMs = JOIN_TIMEOUT_MS)
        capture.start(AudioListener {
            parked.countDown()
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
        })
        assertTrue(
            "the listener was never reached, so there is no parked dispatcher for " +
                "a stop to be waiting on",
            parked.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        // Counted down by the winning stop() on its way out, so the test can
        // tell "the join gave up and the teardown finished" from "the test
        // released the dispatcher first and there was never a stuck thread".
        val winnerReturned = CountDownLatch(1)
        val winner = Thread({
            try {
                capture.stop()
            } finally {
                winnerReturned.countDown()
            }
        }, "e-winner")
        winner.start()
        val closedBy = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (source.closeCalls == 0 && System.currentTimeMillis() < closedBy) {
            Thread.sleep(2)
        }
        assertEquals(
            "the first stop() never closed the device, so it never got as far as " +
                "the join and this test is not in the state it is about",
            1,
            source.closeCalls,
        )

        val loserReturned = CountDownLatch(1)
        Thread({
            capture.stop()
            loserReturned.countDown()
        }, "e-loser").start()

        // A bounded window in which the loser must NOT come back. The winner is
        // parked in its join and cannot finish until this test releases the
        // listener, so a loser that returns inside this window has returned
        // before the teardown it is waiting on is done — which is the fault.
        assertFalse(
            "the losing stop() returned while the winning stop() was still " +
                "joining a parked dispatcher. A stop() that returns has told the " +
                "caller the microphone is shut; returning before the teardown " +
                "that is actually happening is a promise the caller then races.",
            loserReturned.await(LOSER_WINDOW_MS, TimeUnit.MILLISECONDS),
        )

        // Now prove the winner really did give up on the parked dispatcher, and
        // only THEN let it out. The loser has to be released inside the
        // winner's join for the stuck-thread record to exist at all, but with a
        // bare countDown() the release lands ~LOSER_WINDOW_MS after the loser
        // started while the winner's join expires JOIN_TIMEOUT_MS after it
        // closed — about 50ms of slack in which a loaded machine can get the
        // dispatcher home first and leave `capture.failure` null, failing a test
        // that is about ordering. So the teardown is observed to have finished
        // its join (the winner has returned) before the release, which makes
        // the record a fact about the code rather than about the schedule.
        assertTrue(
            "the winning stop() never returned while its dispatcher was " +
                "parked, so the join never gave up and this test is not in the " +
                "state it is about",
            winnerReturned.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        release.countDown()
        assertTrue(
            "the losing stop() never returned after the teardown finished",
            loserReturned.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        assertEquals(
            "the device was closed ${source.closeCalls} times across two stop() " +
                "calls; a teardown that runs twice releases the same resources twice",
            1,
            source.closeCalls,
        )
        assertFalse(
            "isCapturing is still true once both stops have returned",
            capture.isCapturing,
        )
        assertNotNull(
            "the parked dispatcher was not reported as a stuck session thread, so " +
                "this test's teardown never actually had to wait for anything",
            capture.failure,
        )
    }

    private companion object {
        /**
         * A take that is a whole number of frames, so "every sample arrived" is
         * a statement about the take rather than about where the test stopped
         * reading it.
         */
        const val FULL_TAKE_SAMPLES = 4 * MicCapture.DEFAULT_FRAME_SAMPLES

        /**
         * How long after stop() returns the racing open() is released.
         *
         * Long enough that the open is still in flight when the stop comes back,
         * short enough to keep the suite quick. It is a gate a third thread
         * opens, not a condition any assertion waits on: the assertions about
         * the state at that moment all run before it.
         */
        const val GATE_RELEASE_MS = 200L

        /**
         * How long the third start is watched for reaching the device while the
         * first is still inside its own open.
         *
         * A window that ends on the SECOND open arriving, so a gate that does
         * not hold is caught at once. Long enough that a loaded machine gives
         * the third start a fair chance to be scheduled and prove it CAN get
         * there — which is what makes the absence of a second open a statement
         * about the gate rather than about the thread never being run.
         */
        const val NO_OVERLAP_WINDOW_MS = 750L

        /**
         * How long a start is allowed to wait on the device gate in the
         * overlapping-starts test.
         *
         * Sized against [NO_OVERLAP_WINDOW_MS], not against the suite: the
         * first open is held for the length of that window and released the
         * moment it ends, so the third start's wait on the gate is however long
         * the first took to release plus a scheduling hand-off. Four times the
         * window is generous for that and still short enough that a gate which
         * genuinely gave up would say so inside the test's own bounds rather
         * than being mistaken for a slow machine.
         */
        const val GATE_WAIT_MS = 4 * NO_OVERLAP_WINDOW_MS

        /** The bound a hung open() is held to. */
        const val GIVE_UP_TIMEOUT_MS = 250L

        /** Short enough that a parked dispatcher is given up on promptly. */
        const val JOIN_TIMEOUT_MS = 150L

        /**
         * How long the losing stop() is watched for an early return.
         *
         * A ceiling on the wait, not the wait itself. It MUST be shorter than
         * [JOIN_TIMEOUT_MS], and that is the whole reason it is not a larger
         * number: the winning stop() gives up on its parked dispatcher after
         * joinTimeoutMs and returns, so a window longer than that would be
         * asserting about a teardown that has provably finished — the loser
         * would come home correctly and be failed for it. The two constants
         * are a matched pair and moving one without the other breaks the test.
         */
        const val LOSER_WINDOW_MS = 100L
    }
}

/**
 * A device whose [open] blocks until the test releases it, counting how many
 * opens are inside it at once and recording each one's entry and exit against
 * the thread that made it.
 *
 * Three things live here because they are the same property of the same seam: an
 * open takes real time (tens of milliseconds on a phone, a permission prompt on
 * a cold start), only a caller that can HOLD an open open can put a test inside
 * that window or see whether two opens overlap, and an overlap cannot be checked
 * from outside at all — two starts that both returned "successfully" and a
 * source that reports one open tell a caller nothing about whether the
 * microphone was really shared.
 *
 * The per-thread event log is what makes the order of two opens a fact rather
 * than an inference. A peak count alone says two were open at once and not which
 * one was late; the log says which thread entered when and which left when, so a
 * test can assert that one start's open finished before the next one began, and
 * can put that order in a failure message.
 *
 * The spent script holds the device open rather than reporting a dead one, so a
 * take that ends here is a take that ended because the test stopped it, and
 * [MicCapture.failure] is null for a reason that is not the fixture's.
 */
private class GatedSource(
    script: FloatArray,
    override val sampleRateHz: Int = 16_000,
    override val channelCount: Int = 1,
) : MicSource {

    private val delegate = FakeMicSource(script = script, holdsOpenWhenScriptSpent = true)
    private val inside = AtomicInteger(0)

    /** Counted down once [open] is under way and waiting. */
    val insideOpen = CountDownLatch(1)

    /** Counted down by the test to let [open] finish. */
    val releaseOpen = CountDownLatch(1)

    val openCalls = AtomicInteger(0)
    val peakOpenConcurrency = AtomicInteger(0)

    private val events = CopyOnWriteArrayList<String>()

    /**
     * "thread entered" / "thread left" for every [open], in the order the device
     * saw them.
     *
     * Deliberately a flat list rather than a map: the claim under test is about
     * ORDER between two threads, and a map keyed by thread would lose the
     * interleaving that is the whole point.
     */
    val openEvents: List<String> get() = events.toList()

    /**
     * True while a thread is inside [open] and blocked on [releaseOpen].
     *
     * Read by a test that has to know the window was still open when it looked,
     * so a window that found nothing to complain about because the first open
     * had already returned cannot pass by saying nothing.
     */
    @Volatile
    var insideOpenInProgress: Boolean = false
        private set

    override fun open() {
        openCalls.incrementAndGet()
        peakOpenConcurrency.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
        insideOpenInProgress = true
        events.add("${Thread.currentThread().name} entered")
        insideOpen.countDown()
        try {
            releaseOpen.await(WAIT_SECONDS, TimeUnit.SECONDS)
        } finally {
            insideOpenInProgress = false
            events.add("${Thread.currentThread().name} left")
            inside.decrementAndGet()
        }
        delegate.open()
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int =
        delegate.read(buffer, offset, lengthInShorts)

    override fun close() = delegate.close()
}
