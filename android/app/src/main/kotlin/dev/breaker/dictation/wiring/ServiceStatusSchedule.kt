package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.Clock

/**
 * The foreground rule of the service-status ask (ADR-044).
 *
 * The ask happens only while the launcher activity is resumed: one ask on every onResume, then one
 * every [INTERVAL_MS] while it stays resumed. onPause takes the queued block back, so nothing runs
 * while the app is not resumed, and [asked] — the caller's gate (sync on, a live session, an address
 * configured) — decides whether a resume asks at all. There is no scheduler, no alarm, no
 * WorkManager and no service: the only wake-up is one block the injected [DelayedPost] queues, and
 * the clock is the authority on whether the two hours have passed.
 */
internal class ServiceStatusSchedule(
    private val asker: ServiceStatusAsker,
    private val asked: () -> Boolean,
    private val post: DelayedPost,
    private val clock: Clock,
    private val onAnswer: (ServiceStatusAnswer) -> Unit,
) {
    private var resumed: Boolean = false
    private var pending: PostHandle? = null
    private var lastAskAt: Long = 0L

    /** The launcher activity came to the foreground. */
    fun onResume() {
        if (resumed) return
        resumed = true
        if (asked()) askNow()
    }

    /** The launcher activity left the foreground: the queued block is taken back. */
    fun onPause() {
        resumed = false
        pending?.cancel()
        pending = null
    }

    private fun askNow() {
        lastAskAt = clock.nowEpochMillis()
        onAnswer(asker.ask())
        arm()
    }

    /** Queues the next ask [INTERVAL_MS] after the last one, by the clock. */
    private fun arm() {
        pending = post.postDelayed(askAt() - clock.nowEpochMillis()) { tick() }
    }

    /** The queued block: ask again only once the clock has reached the two-hour mark. */
    private fun tick() {
        pending = null
        if (!resumed) return
        if (clock.nowEpochMillis() < askAt()) {
            arm()
            return
        }
        askNow()
    }

    private fun askAt(): Long = lastAskAt + INTERVAL_MS

    private companion object {
        /** The re-ask interval while resumed: two hours. */
        const val INTERVAL_MS: Long = 2 * 60 * 60 * 1000L
    }
}
