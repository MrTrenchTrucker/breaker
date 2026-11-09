package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession

/**
 * A main-thread-only flag: the app owns it and Home reads it through a reader the app passes in.
 *
 * Holds whether the session is live, changed on the main thread only. There is no lock and no thread: the
 * owner reads and writes it between draws on one thread. It holds no reference to a vault or an activity; what
 * derives the value from the session lives elsewhere ([sessionLiveFrom]). A reader the app builds over this lets
 * Home redraw when the value changes.
 */
class SessionLiveState(private val initial: Boolean) {
    private var live = initial

    /** Whether the session is currently live. Main-thread only. */
    fun liveNow(): Boolean = live

    /** Sets whether the session is live, a no-op when it does not change. Main-thread only. */
    fun setLive(value: Boolean) {
        if (live != value) live = value
    }
}

/**
 * Derives the session-live flag from the addressed session, one pure place the state comes from the session.
 *
 * Pure over its [addressed] lambda: it touches no file and holds no vault, so a test can answer it directly. It
 * is true exactly when the addressed session is not null; what reads the store happens in the caller's lambda. A
 * stored session answers true, a fresh install with none answers false.
 */
internal fun sessionLiveFrom(addressed: () -> AddressedSession?): Boolean = addressed() != null
