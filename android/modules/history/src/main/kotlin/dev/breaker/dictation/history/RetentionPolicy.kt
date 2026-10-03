package dev.breaker.dictation.history

import java.time.Duration
import java.time.Instant

/**
 * When a transcription stops being kept (F28), by the ADR-010 precise rule.
 *
 * The window is a **fixed 90 days**: 90 x 24 hours, counted backwards from the
 * current instant. There is no time zone in it and no calendar month: the
 * cutoff is [WINDOW] subtracted from an [Instant], and nothing else. By design
 * the phone and the server both compute the cutoff from UTC instants with this
 * same 90-day rule, so each can work out the same cutoff from the same instant
 * with nothing but a standard library. "3 months" in the requirements means
 * this rule.
 *
 * The rule is fixed on purpose, and this class takes nothing to configure it. A
 * window a caller could pass in is a window the phone and the server could
 * disagree about.
 *
 * The boundary is a half-open interval and is spelled out here because getting
 * it wrong is how history loses data:
 *
 * - a transcription created **at** the cutoff instant is still inside the
 *   window and is **kept**;
 * - a transcription created **one millisecond before** the cutoff is outside
 *   the window and is **purged**.
 *
 * The rule is evaluated to the millisecond, because that is how a transcription's
 * creation time is stored (epoch milliseconds). The shipped purge takes its
 * cutoff from [cutoff] and applies the SQL predicate `created_at < cutoff`.
 * [isExpired] is the Kotlin form of that predicate, kept in agreement with it by
 * tests; it is modelled and tested, not shipped.
 */
internal class RetentionPolicy {

    /**
     * The instant the window closes: [now] moved back [WINDOW].
     *
     * Everything created before this is expired; everything created at or after
     * it is kept.
     */
    fun cutoff(now: Instant): Instant = now.minus(WINDOW)

    /**
     * True when a transcription created at [createdAt] has fallen out of the
     * window. Strictly before the cutoff; a row created at the cutoff is kept.
     *
     * The Kotlin form of the SQL predicate, kept in agreement with it by tests.
     * The shipped purge applies the SQL predicate, not this function: it is
     * modelled and tested, not shipped.
     */
    fun isExpired(createdAt: Instant, now: Instant): Boolean =
        RetentionBoundary.isExpired(createdAt.toEpochMilli(), cutoff(now).toEpochMilli())

    /**
     * When a tombstone itself may be dropped.
     *
     * A tombstone is the record that a row was deleted so the delete can reach
     * the other devices and the server. It is measured from the moment of the
     * delete, not from when the text was created, and it is kept for the same
     * [WINDOW]: long enough that a device offline for the whole window still
     * learns about the delete instead of keeping a copy forever. That 90-day
     * tombstone window, measured from the delete, is a design choice of this
     * module: the spec sets a lifetime for transcriptions and none for
     * tombstones. Tombstone expiry is a separate, explicit call: the
     * transcription purge writes tombstones for the rows it removes but never
     * expires them.
     */
    fun tombstoneCutoff(now: Instant): Instant = cutoff(now)

    companion object {
        /** F28: transcriptions are kept for 90 days, exactly 90 x 24 hours. */
        val WINDOW: Duration = Duration.ofDays(90)
    }
}
