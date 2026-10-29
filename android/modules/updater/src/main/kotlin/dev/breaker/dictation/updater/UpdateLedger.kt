package dev.breaker.dictation.updater

/**
 * Keeps the three times the update check needs, in epoch milliseconds.
 *
 * The definitive-check time moves only when a check reaches an answer (up to date or available);
 * a check that fails leaves it where it was. The update time moves when an update is installed.
 * The attempt time moves on every check attempt, whatever its result. Each value is null until it
 * has been set. The interface holds no logic: an implementation stores the values, and the
 * schedule decision reads them through the caller.
 */
internal interface UpdateLedger {
    /** The time of the last check that reached an answer, or null when none has. */
    fun lastDefinitiveCheckMillis(): Long?

    /** The time of the last installed update, or null when none has been installed. */
    fun lastUpdateMillis(): Long?

    /** The time of the last check attempt of any kind, or null when none has been made. */
    fun lastAttemptMillis(): Long?

    /** Records that a check reached an answer at [atMillis]. */
    fun recordDefinitiveCheck(atMillis: Long)

    /** Records an installed update at [atMillis]. */
    fun recordUpdate(atMillis: Long)

    /** Records a check attempt at [atMillis], whether or not it succeeded. */
    fun recordAttempt(atMillis: Long)
}
