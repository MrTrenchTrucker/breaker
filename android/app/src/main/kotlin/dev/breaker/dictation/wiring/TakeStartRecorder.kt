package dev.breaker.dictation.wiring

/**
 * Records the window a take started in. One method, so the runner can be tested with a fake: the
 * app cannot construct the commit module's capture object (its constructor is internal to that
 * module), and the composition root adapts it into this instead.
 *
 * capture() is the only abstract member, so SAM conversion still holds. generation() and clearIf()
 * are defaults: every existing fake and recorder keeps compiling and behaving as today, and the
 * composition root overrides both to adapt the commit module's anchor.
 */
fun interface TakeStartRecorder {
    fun capture()

    /** The generation of the anchor held right now; 0L when nothing is held (INACTIVE). */
    fun generation(): Long = 0L

    /** Clears the held anchor only if its generation equals the argument; a no-op otherwise. */
    fun clearIf(generation: Long) { clear() }

    /** Drop the anchor this take captured. A recorder that keeps no anchor has nothing to drop. */
    fun clear() { }
}
