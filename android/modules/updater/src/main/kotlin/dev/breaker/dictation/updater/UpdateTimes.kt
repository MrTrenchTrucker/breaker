package dev.breaker.dictation.updater

/**
 * The app's store of two times, in milliseconds since the epoch: when the last update check ran,
 * and when the last update was installed. The app supplies the store; this module only names it.
 */
public interface UpdateTimes {
    /** The time of the last update check, or null when the app never checked. */
    fun lastCheckAtMs(): Long?

    /** Stores the time of an update check. */
    fun recordCheckAtMs(atMs: Long)

    /** Stores the time of an update install. */
    fun recordUpdateAtMs(atMs: Long)
}
