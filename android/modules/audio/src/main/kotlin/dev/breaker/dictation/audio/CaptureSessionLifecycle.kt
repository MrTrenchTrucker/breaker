package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * How one capture session is brought up and torn down against its neighbours.
 *
 * [MicCapture] owns what a take IS - its ring buffer, its conversion pipeline,
 * and the flags that describe it. This owns WHEN a take touches the device and
 * WHEN it lets go of it: a start that finds a device already opening, a stop
 * that lands inside that open, a second stop arriving while the first is still
 * joining. Every field below exists because of a race between two callers and
 * none of them is about a take's content — [deviceGate] is one microphone, one
 * [MicSource.open] at a time; [stopEpoch] records a stop as a COUNT, because a
 * start still inside the open has no session thread for a stop to take and the
 * start it raced has to be able to see it at all; [teardownInFlight] is the
 * teardown a second [stop] must wait for, so two legal stops both mean "the
 * microphone is shut" by the time both return. They are one responsibility,
 * and keeping them off the take's own state keeps each half readable.
 *
 * Internal on purpose. Nothing outside this module has any business sequencing
 * a start against a stop.
 */
internal class CaptureSessionLifecycle(
    private val source: MicSource,
    private val indicator: RecordingIndicator,
    /** Frame size handed to the listener, in samples. */
    private val frameSize: Int,
    /** How long a wait for a peer call is bounded to before it is given up on. */
    private val joinTimeoutMs: Long,
    /** The owning capture's session flag. Shared, not copied: both halves must see one take. */
    private val running: AtomicBoolean,
    /**
     * The owning capture's stop request, shared for the same reason. Set to ask
     * the capture thread to end; that thread drops [running] after recovering
     * the take's tail. See [MicCapture].
     */
    private val stopRequested: AtomicBoolean,
    /** The owning capture's session number. Shared, for the same reason. */
    private val session: AtomicLong,
    /** The owning capture's failure record. Shared, for the same reason. */
    private val failureRef: AtomicReference<Throwable?>,
) {

    /**
     * The take this lifecycle last opened, if any.
     *
     * Read by the owning capture before it does anything else, so a stop that
     * has already been answered is not mistaken for one that lands during that
     * call. A stop the caller made before a start is not a stop OF that start,
     * and the capture must still come up.
     */
    fun stopEpochSnapshot(): Long = stopEpoch.get()

    /**
     * Open the device and publish the session, or unwind because a stop got
     * there first.
     *
     * Everything from taking the device gate to the last of the publication
     * happens here, under that gate, so a start cannot leave a window in which a
     * second start reaches the device.
     *
     * Returns quietly when a stop landed inside the open: the caller issued two
     * legal calls and did nothing wrong, so the race is this implementation's
     * problem to absorb rather than the caller's to be told about.
     */
    fun openAndPublish(
        listener: AudioListener,
        epochAtEntry: Long,
        current: Long,
        mine: PcmRingBuffer,
        pipeline: CapturePcmPipeline,
    ) {
        // The device gate is taken BEFORE the open and released only once the
        // handshake below has either published this session or closed what this
        // call opened. Holding it across both is what makes "one microphone,
        // one open at a time" true rather than merely likely: a start that
        // arrives while a previous open is still in flight waits here instead
        // of opening the same device a second time.
        val gotGate = try {
            deviceGate.tryLock(joinTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!gotGate) {
            // Bounded by joinTimeoutMs, exactly as a stuck session thread is.
            // A device whose open never returns must not turn the next start
            // into a wait forever, and the give-up is reported where a caller
            // reads it rather than thrown at a thread that is already gone.
            // The flag is dropped on this path too, and only while this call is
            // still the current session. It was raised by the compareAndSet
            // at entry, before this call ever reached the device, so a give-up
            // that left it standing would hand the caller a capture it can
            // neither read nor restart — the same strand a stop used to leave.
            if (session.get() == current) running.set(false)
            val gaveUp = IllegalStateException(
                "another capture is still opening the device after " +
                    "${joinTimeoutMs}ms; this start gave up rather than wait " +
                    "forever on an open that never returns",
            )
            failureRef.compareAndSet(null, gaveUp)
            throw gaveUp
        }
        try {
            markLiveAndOpen(current)
            // The handshake, taken under the same lock stop() takes the threads
            // under: a stop that has already been through this lock is one this
            // start must not spin a session up behind, and a stop that has not is
            // one that will find the threads this start is about to publish. There
            // is no window between the check and the publication, so there is no
            // ordering of the two calls that loses a stop.
            val stopped = synchronized(teardownLock) {
                if (stopEpoch.get() == epochAtEntry) {
                    false
                } else {
                    // A stop landed inside open(). The caller has already been told
                    // the capture is stopped, so it must stay stopped: close what
                    // this call opened, mark the indicator dark, and spawn nothing.
                    // Every stop drops `running` on its way out — including one that
                    // found no session thread to take, which is the only kind there
                    // can be here — and it is set again below only so the state does
                    // not depend on which of the two happened to run first.
                    captureThread = null
                    dispatchThread = null
                    true
                }
            }
            if (stopped) {
                // Only while this call is still the current session. A start
                // issued after the stop that landed here has already claimed the
                // next session and set the flag FOR IT, and clearing it now would
                // end that take before its first frame — the mirror of the hole
                // this closes, on the other side of the race.
                if (session.get() == current) running.set(false)
                closeQuietly()
                indicator.markRecordingStopped()
                return
            }

            // The listener is passed to the thread rather than read from a field:
            // a dispatcher holds the listener of the take it was started for and
            // cannot be pointed at a later take's listener by a field somebody else
            // reassigns.
            val capture = CaptureLoop(
                source = source,
                pipeline = pipeline,
                running = running,
                stopRequested = stopRequested,
                session = session,
                failureRef = failureRef,
                readBufferSamples = readBufferSamples,
            )
            val dispatch = DispatchLoop(
                frameSize = frameSize,
                running = running,
                session = session,
                failureRef = failureRef,
            )
            synchronized(teardownLock) {
                captureThread = Thread({ capture.run(mine, current) }, MicCapture.CAPTURE_THREAD_NAME).apply {
                    isDaemon = true
                    start()
                }
                dispatchThread = Thread({ dispatch.run(mine, listener, current) }, MicCapture.DISPATCH_THREAD_NAME).apply {
                    isDaemon = true
                    start()
                }
            }
        } finally {
            deviceGate.unlock()
        }
    }

    /**
     * Mark the indicator live and open the device, as ONE guarded step.
     *
     * The mark goes live BEFORE the device is opened, so there is no window in
     * which the microphone is open and the screen says nothing.
     *
     * Both calls are under one `try`, and that is load-bearing. The mark calls
     * somebody else's code — on Android a view touched off the main thread
     * throws — and a throw from it used to escape `start()` with the session
     * flag still up and the indicator live: every later start refused as
     * "already running" and every stop finding no session threads to join, so
     * the capture was stuck until the process died. Under one guard a broken
     * listener is indistinguishable, to the session, from a device that would
     * not open, and the path below undoes the session either way.
     */
    private fun markLiveAndOpen(current: Long) {
        try {
            indicator.markRecordingStarted()
            source.open()
        } catch (e: Throwable) {
            // Only while this call is still the current session: a start issued
            // after a stop already claimed the next session and raised the flag
            // FOR IT, and clearing it now would end that take before its first
            // frame.
            if (session.get() == current) running.set(false)
            // Recorded where a caller reads it, not only thrown: capture runs on
            // threads of its own, so an exception raised on one of them has
            // nowhere to go, and a caller that only saw a null would believe the
            // microphone opened cleanly. compareAndSet, so an EARLIER real
            // failure is the one reported — a listener throwing on the way to
            // recording must not overwrite a device failure already recorded.
            // And under the session guard, so a straggler from a take that has
            // been replaced cannot stamp its own failure on the next take, which
            // reads a clean failure as its own history.
            if (session.get() == current) failureRef.compareAndSet(null, e)
            // The indicator is the one thing this path still owes: the mark above
            // set it live before the listener threw, and a screen left showing
            // "recording" for a microphone nobody holds is the exact abuse the
            // indicator exists to prevent. A throw from the way DOWN is attached
            // to the one already on its way out rather than replacing it — the
            // caller still learns the original cause, and the second is not lost
            // either.
            try {
                indicator.markRecordingStopped()
            } catch (down: Throwable) {
                e.addSuppressed(down)
            }
            throw e
        }
    }

    /**
     * Close the microphone and wait for the session to finish.
     *
     * See the KDoc on [MicCapture.stop] for the contract this implements; it
     * lives there because it is the contract a caller reads. What follows is the
     * ordering that keeps the promise.
     */
    fun stop() {
        // Taken under a lock and nulled in one step, so two stops racing each
        // other tear down once: the second finds no session and does nothing.
        // The count is bumped in the same critical section, so it is bumped by
        // every stop — including one that finds no session to join, which is
        // exactly the stop a start racing this one has to be able to see.
        //
        // A second stop that finds the teardown already claimed does not leave
        // with an empty list and return: it takes the latch the winner will
        // count down and waits on it below, so "stop() returned" still means
        // the teardown it was promised is over.
        var waitFor: CountDownLatch? = null
        var ownedByThisThread = false
        val owns = synchronized(teardownLock) {
            stopEpoch.incrementAndGet()
            val inFlight = teardownInFlight
            val inFlightOwner = teardownOwner
            if (inFlight != null && inFlightOwner === Thread.currentThread()) {
                // This call IS the teardown, re-entered. A listener on the
                // indicator is free to call stop() from inside
                // markRecordingStopped(), and the indicator runs its listeners
                // on the thread that made the change -- so that stop() arrives
                // here on THIS thread, while this teardown is still running and
                // is waiting for the very call stack it is inside.
                //
                // Waiting would be a deadlock wearing a disguise: the latch is
                // counted down in this teardown's own finally, which cannot run
                // until the callback that made this call returns. So it would
                // not merely be slow, it would burn the whole joinTimeoutMs and
                // then record a failure about "another stop() still tearing the
                // session down" — which is a lie, because there is no other
                // stop, and this one is the teardown.
                ownedByThisThread = true
                null
            } else if (inFlight != null) {
                waitFor = inFlight
                null
            } else {
                CountDownLatch(1).also {
                    teardownInFlight = it
                    teardownOwner = Thread.currentThread()
                }
            }
        }
        if (ownedByThisThread) {
            // The teardown this call is already inside is the caller's own, and
            // it is going to finish: this teardown IS this thread, still a few
            // lines above on its way out. There is nothing to wait for and
            // nothing to report — the work is being done by the very call that
            // made this one, so returning quietly is the whole truth of it.
            return
        }
        if (owns == null) {
            // The teardown is somebody else's. Wait for it rather than race it:
            // the caller has two legal stops outstanding and is entitled to have
            // both of them mean "the microphone is shut".
            val finished = try {
                waitFor!!.await(joinTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!finished) {
                failureRef.compareAndSet(
                    null,
                    IllegalStateException(
                        "another stop() was still tearing the session down after " +
                            "${joinTimeoutMs}ms; the microphone may still be closing",
                    ),
                )
            }
            return
        }
        // The take this teardown is tearing down, read in the same critical
        // section that takes its threads. A start racing this one claims the
        // NEXT session number before it touches anything, so a difference means
        // this stop is finishing against a take that has already been replaced —
        // and anything this teardown records afterwards would be stamped on the
        // new take's history instead.
        val stopSession = session.get()
        val threads = synchronized(teardownLock) {
            listOfNotNull(captureThread, dispatchThread).also {
                captureThread = null
                dispatchThread = null
            }
        }
        try {
            // Ask the capture thread to end; `running` is deliberately NOT
            // dropped here on the path that has a capture thread. It is the
            // signal the dispatcher reads to decide the take is finished, and
            // the take is not finished until that thread has recovered the
            // resampler's held-back tail and written it to the ring. Dropping it
            // here let the dispatcher finish on an empty ring before the tail
            // arrived, and the take came up short by exactly the tail — 15
            // samples of a 400 ms take at 16 kHz — whenever the dispatcher's
            // read lost that race. Which thread won was a coin flip, so the
            // symptom was a flake.
            stopRequested.set(true)
            if (threads.isEmpty()) {
                // No session thread exists to end the take and drop the flag,
                // so this stop is the one that must: a stop that has returned
                // has told the caller the capture is stopped.
                running.set(false)
                return
            }
            closeQuietly()

            // Giving up here is legitimate: a listener that blocks past the
            // timeout leaves its dispatcher running, and that dispatcher is
            // harmless because the buffer it is reading belongs to a take that
            // nobody writes to again. Interrupting it instead would be a way to
            // make the symptom go away, but it cuts short a listener that is
            // mid-callback and ends a capture that was about to deliver a frame.
            // A capture that ended by itself is joined here too, which is what
            // puts the tail inside the caller's timeline.
            val stuck = threads
                .filter { it.isAlive }
                .filterNot { joinWithin(it) }
            // The joins above are the normal end of a take: the capture thread
            // has drained its tail and dropped the flag itself. A thread that
            // was given up on may not have, and stop() returning means
            // isCapturing is false, so the flag is dropped here too.
            running.set(false)
            // The indicator is told dead before the stuck-thread record is made,
            // but a throw from it must not cost this teardown its last statement.
            // The mark calls the screen's code, and that code is outside this
            // module's control — a listener throwing here used to skip the record
            // entirely, so the one failure that says the microphone may still be
            // open was the one failure a throwing listener could suppress. Kept,
            // not rethrown here, and raised after the finally below has released
            // the teardown latch, so "stop() returned" still means the teardown
            // is over for the stop that may be waiting on it.
            var listenerFailure: Throwable? = null
            try {
                indicator.markRecordingStopped()
            } catch (e: Throwable) {
                listenerFailure = e
            }
            if (stuck.isNotEmpty()) {
                // compareAndSet, so a real failure recorded before this one keeps
                // the field rather than being overwritten by the record of a
                // session that did not stop.
                failureRef.compareAndSet(
                    null,
                    IllegalStateException(
                        "capture did not stop within ${joinTimeoutMs}ms; " +
                            "the session thread(s) ${stuck.map { it.name }} are still running",
                    ),
                )
            }
            listenerFailure?.let {
                if (session.get() == stopSession) failureRef.compareAndSet(null, it)
                throw it
            }
        } finally {
            // Cleared and counted down in ONE critical section, so a stop
            // arriving between the two cannot see the slot empty, claim
            // ownership of a teardown that is already over, and run a second
            // one against a session the first has finished with.
            synchronized(teardownLock) {
                teardownInFlight = null
                teardownOwner = null
                owns.countDown()
            }
        }
    }

    private fun joinWithin(thread: Thread): Boolean = try {
        thread.join(joinTimeoutMs)
        !thread.isAlive
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun closeQuietly() {
        try {
            source.close()
        } catch (e: Throwable) {
            failureRef.compareAndSet(null, e)
        }
    }

    /**
     * How many stops have been asked for.
     *
     * [stop] cannot act on a start that is still inside the device's open,
     * because there is no session thread to take and join yet. So a stop is
     * recorded here as a count rather than only as an action, and a start that
     * finds the count has moved under it knows the caller has already been told
     * the capture is stopped and acts on it before it spawns anything. Claimed
     * under [teardownLock] and read under the same lock, so this is not a flag a
     * start can step over between its check and its threads.
     */
    private val stopEpoch = AtomicLong(0)

    /**
     * The device gate: one microphone, one [MicSource.open] at a time.
     *
     * A stop that has dropped `running` releases the session, so the next start
     * can come up — and it may do so while the previous start is still inside
     * the device's open, which is exactly the window a stop lands in. Two opens
     * on one source means two captures reading it, and whichever start loses the
     * race to the teardown closes the device out from under the winner's take.
     *
     * So the gate is held across the open AND the handshake that follows it, so
     * a second start cannot touch the device until the first has finished
     * opening it and either published a session or closed what it opened. It is
     * waited for with a bound rather than held outright: a device whose open
     * never returns must not turn the next start into a wait forever, so the
     * give-up is recorded in [MicCapture.failure] exactly as a stuck session
     * thread is.
     */
    private val deviceGate = ReentrantLock()

    /**
     * The teardown a second [stop] must wait for, or null when none is running.
     *
     * Claimed and cleared under [teardownLock]. A stop that finds no session
     * because ANOTHER stop is already tearing one down must not return until
     * that teardown has finished: a caller that reads "stop() returned" as "the
     * microphone is shut" would otherwise be racing a close and a join it was
     * told had happened.
     */
    private var teardownInFlight: CountDownLatch? = null

    /**
     * The thread that took [teardownInFlight], or null when none is in flight.
     *
     * Claimed and cleared under [teardownLock] alongside the latch, so the two
     * cannot disagree about whether a teardown is running. It exists for one
     * case a latch alone cannot see: the indicator calls its listeners on the
     * thread that made the change, so a listener that calls [stop] from inside
     * `markRecordingStopped()` re-enters this class on the very thread already
     * inside a teardown — the one whose `finally` counts the latch down. Awaiting
     * that latch is not a slow wait, it is a wait that cannot succeed, and its
     * timeout would record a failure about a second stop that does not exist.
     * Identity distinguishes that call from a genuine second stop on another
     * thread, which must still wait: a stop() on the owning thread returns at
     * once instead of waiting.
     */
    private var teardownOwner: Thread? = null

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var dispatchThread: Thread? = null

    /** Serialises taking the session threads, so only one stop() tears down. */
    private val teardownLock = Any()

    private companion object {
        private const val READ_BUFFER_MULTIPLIER = 4

        private val readBufferSamples: Int =
            MicCapture.DEFAULT_FRAME_SAMPLES * READ_BUFFER_MULTIPLIER
    }
}
