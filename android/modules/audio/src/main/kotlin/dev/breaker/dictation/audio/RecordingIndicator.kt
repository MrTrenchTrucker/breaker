package dev.breaker.dictation.audio

/**
 * The module's half of the mic-indicator requirement: it knows when the
 * microphone is live and tells whoever is showing the indicator.
 *
 * The UI is not this module's to draw, so this is deliberately the smallest
 * thing that can carry the fact: a flag plus a listener list. A screen that
 * binds to [isRecording] shows the indicator for exactly as long as the
 * microphone is actually open.
 *
 * The privacy rule this serves is the reason it is not optional bookkeeping:
 * the indicator may never read "not recording" while the mic is open, and may
 * never read "recording" once it is closed. Every transition therefore goes
 * through [markRecordingStarted] / [markRecordingStopped], including the ones
 * that happen because a capture failed.
 *
 * Thread-safe. Listeners are notified on the thread that made the change; for
 * MicCapture that is the thread that called start() or stop(), never its
 * capture or dispatch thread. A listener that touches the UI must hop to the
 * main thread itself.
 */
class RecordingIndicator {
    private val lock = Any()
    private val listeners = LinkedHashSet<(Boolean) -> Unit>()

    @Volatile
    private var recording: Boolean = false

    /** True while the microphone is open. Read this to drive the indicator. */
    val isRecording: Boolean
        get() = recording

    /**
     * Follow [isRecording].
     *
     * The listener is called once immediately with the current value, so a
     * screen that binds after recording started still shows the indicator
     * rather than waiting for the next transition to notice.
     */
    fun addListener(listener: (Boolean) -> Unit) {
        val current = synchronized(lock) {
            listeners.add(listener)
            recording
        }
        listener(current)
    }

    /** Stop following [isRecording]. A listener that was never added is ignored. */
    fun removeListener(listener: (Boolean) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    /**
     * The microphone has opened.
     *
     * Called by the capture implementation, and by nothing else. Calling it
     * while already recording is a no-op rather than a second notification, so
     * a listener cannot be told "recording" twice for one session.
     */
    fun markRecordingStarted() = mark(true)

    /**
     * The microphone has closed — cleanly, by a stop, or because the capture
     * failed. The indicator is a statement about the hardware, so every path
     * out of a recording session has to come through here.
     */
    fun markRecordingStopped() = mark(false)

    /**
     * The one transition, told to every listener whatever any of them does.
     *
     * Both guards here are load-bearing, and neither replaces the other:
     *
     * - The flag is set and the listener list is snapshotted under [lock], so
     *   an exception from a listener cannot leave the flag mid-transition. It
     *   is already correct before the first listener runs.
     * - EVERY listener is called, in both directions, and the FIRST exception
     *   is re-thrown only after the whole list has been told. Stopping at the
     *   throw would leave every listener after it showing the previous state —
     *   a screen bound to one of those shows "not recording" for a microphone
     *   that is open, which is the exact abuse this class exists to prevent,
     *   and it would never be told about the stop either.
     *
     * The re-throw is what keeps the failure visible. The capture catches it,
     * records it in [MicCapture.failure] and undoes the session, so a broken
     * listener costs the caller its capture rather than stranding one it
     * cannot restart — and the caller still learns why.
     */
    private fun mark(value: Boolean) {
        val toNotify = synchronized(lock) {
            if (recording == value) return
            recording = value
            listeners.toList()
        }
        var thrown: Throwable? = null
        for (listener in toNotify) {
            try {
                listener(value)
            } catch (e: Throwable) {
                // Kept, not rethrown here: the listeners after this one still
                // have to hear the transition.
                if (thrown == null) thrown = e else thrown!!.addSuppressed(e)
            }
        }
        thrown?.let { throw it }
    }
}
