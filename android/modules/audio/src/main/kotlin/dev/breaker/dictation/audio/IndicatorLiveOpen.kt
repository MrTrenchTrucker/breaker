package dev.breaker.dictation.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The first step of bringing a take up: tell the indicator the microphone is
 * live, then open the device, as one guarded step that undoes itself on a
 * failure.
 *
 * It holds the same indicator, source and shared session state the
 * [CaptureSessionLifecycle] that owns it holds, so a failure here is recorded
 * against the same take the lifecycle is starting.
 */
internal class IndicatorLiveOpen(
    private val indicator: RecordingIndicator,
    private val source: MicSource,
    private val running: AtomicBoolean,
    private val session: AtomicLong,
    private val failureRef: AtomicReference<Throwable?>,
) {

    /**
     * Mark the indicator live and open the device, as ONE guarded step.
     *
     * The mark goes live BEFORE the device is opened, so there is no window in
     * which the microphone is open and the screen says nothing.
     *
     * Both calls are under one `try`, and that is load-bearing. The mark calls
     * somebody else's code - on Android a view touched off the main thread
     * throws - and a throw from it used to escape `start()` with the session
     * flag still up and the indicator live: every later start refused as
     * "already running" and every stop finding no session threads to join, so
     * the capture was stuck until the process died. Under one guard a broken
     * listener is indistinguishable, to the session, from a device that would
     * not open, and the path below undoes the session either way.
     */
    internal fun markLiveAndOpen(current: Long) {
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
            // failure is the one reported - a listener throwing on the way to
            // recording must not overwrite a device failure already recorded.
            // And under the session guard, so a straggler from a take that has
            // been replaced cannot stamp its own failure on the next take, which
            // reads a clean failure as its own history.
            if (session.get() == current) failureRef.compareAndSet(null, e)
            // The indicator is the one thing this path still owes: the mark above
            // set it live before the listener threw, and a screen left showing
            // "recording" for a microphone nobody holds is the exact abuse the
            // indicator exists to prevent. A throw from the way DOWN is attached
            // to the one already on its way out rather than replacing it - the
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
}
