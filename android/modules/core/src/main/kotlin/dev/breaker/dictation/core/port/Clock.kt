package dev.breaker.dictation.core.port

/**
 * The current time, in epoch milliseconds.
 *
 * The domain never calls the system clock directly. Taking the time as a port
 * keeps timestamps reproducible in tests and keeps every time-dependent rule
 * (retention, the daily update check) testable without waiting a day.
 */
fun interface Clock {
    fun nowEpochMillis(): Long
}
