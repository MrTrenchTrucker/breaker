package dev.breaker.dictation

import dev.breaker.dictation.core.port.Clock

/**
 * The app's [Clock] adapter: the real system clock.
 *
 * Plain JVM — it calls [System.currentTimeMillis] and nothing else — so it can
 * be exercised on the test JVM without an emulator. The app owns its real
 * adapters; this is the one that turns the core [Clock] port into the device
 * wall clock, the same seat as the file-backed credential-reference holder.
 */
object SystemClockAdapter : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
