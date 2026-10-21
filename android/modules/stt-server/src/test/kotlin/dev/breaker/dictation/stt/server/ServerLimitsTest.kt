package dev.breaker.dictation.stt.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerLimitsTest {

    private val cap = ServerLimits.MAX_MS

    /** Asserts that [build] refuses its values, and that the refusal text carries no digit. */
    private fun refused(build: () -> ServerLimits) {
        try {
            build()
        } catch (expected: IllegalArgumentException) {
            assertTrue("refusal message carries no digit", expected.message.orEmpty().none { it.isDigit() })
            return
        }
        fail("out-of-range limits were accepted")
    }

    @Test
    fun `defaults match the shared numbers`() {
        val d = ServerLimits()
        assertEquals(1_500L, d.connectTimeoutMs)
        assertEquals(10_000L, d.postReplyTimeoutMs)
        assertEquals(30_000L, d.postCallCapMs)
        assertEquals(5_000L, d.pollReadTimeoutMs)
        assertEquals(10_000L, d.pollCallCapMs)
        assertEquals(500L, d.firstPollDelayMs)
        assertEquals(2_000L, d.pollDelayCapMs)
        assertEquals(60_000L, d.baseBudgetMs)
        assertEquals(3, d.clipFactor)
        assertEquals(300_000L, d.maxBudgetMs)
        assertEquals(600, d.maxPolls)
        assertEquals(3, d.maxTransientPollFailures)
    }

    @Test
    fun `companion caps match the shared numbers`() {
        assertEquals(600_000L, ServerLimits.MAX_MS)
        assertEquals(600, ServerLimits.MAX_POLLS)
        assertEquals(10, ServerLimits.MAX_FAILURES)
        assertEquals(10, ServerLimits.MAX_CLIP_FACTOR)
    }

    @Test
    fun `connect timeout accepts the edges and refuses just past them`() {
        assertEquals(1L, ServerLimits(connectTimeoutMs = 1L).connectTimeoutMs)
        assertEquals(cap, ServerLimits(connectTimeoutMs = cap).connectTimeoutMs)
        refused { ServerLimits(connectTimeoutMs = 0L) }
        refused { ServerLimits(connectTimeoutMs = cap + 1L) }
    }

    @Test
    fun `post reply timeout accepts the edges and refuses past them and past the post cap`() {
        assertEquals(1L, ServerLimits(postReplyTimeoutMs = 1L, postCallCapMs = 1L).postReplyTimeoutMs)
        assertEquals(cap, ServerLimits(postReplyTimeoutMs = cap, postCallCapMs = cap).postReplyTimeoutMs)
        refused { ServerLimits(postReplyTimeoutMs = 0L, postCallCapMs = 1L) }
        refused { ServerLimits(postReplyTimeoutMs = cap + 1L, postCallCapMs = cap) }
        refused { ServerLimits(postReplyTimeoutMs = 2L, postCallCapMs = 1L) }
    }

    @Test
    fun `post call cap accepts the edges and refuses just past them`() {
        assertEquals(1L, ServerLimits(postReplyTimeoutMs = 1L, postCallCapMs = 1L).postCallCapMs)
        assertEquals(cap, ServerLimits(postReplyTimeoutMs = 1L, postCallCapMs = cap).postCallCapMs)
        refused { ServerLimits(postReplyTimeoutMs = 1L, postCallCapMs = 0L) }
        refused { ServerLimits(postReplyTimeoutMs = 1L, postCallCapMs = cap + 1L) }
    }

    @Test
    fun `poll read timeout accepts the edges and refuses past them and past the poll cap`() {
        assertEquals(1L, ServerLimits(pollReadTimeoutMs = 1L, pollCallCapMs = 1L).pollReadTimeoutMs)
        assertEquals(cap, ServerLimits(pollReadTimeoutMs = cap, pollCallCapMs = cap).pollReadTimeoutMs)
        refused { ServerLimits(pollReadTimeoutMs = 0L, pollCallCapMs = 1L) }
        refused { ServerLimits(pollReadTimeoutMs = cap + 1L, pollCallCapMs = cap) }
        refused { ServerLimits(pollReadTimeoutMs = 2L, pollCallCapMs = 1L) }
    }

    @Test
    fun `poll call cap accepts the edges and refuses just past them`() {
        assertEquals(1L, ServerLimits(pollReadTimeoutMs = 1L, pollCallCapMs = 1L).pollCallCapMs)
        assertEquals(cap, ServerLimits(pollReadTimeoutMs = 1L, pollCallCapMs = cap).pollCallCapMs)
        refused { ServerLimits(pollReadTimeoutMs = 1L, pollCallCapMs = 0L) }
        refused { ServerLimits(pollReadTimeoutMs = 1L, pollCallCapMs = cap + 1L) }
    }

    @Test
    fun `first poll delay accepts the edges and refuses above the delay cap`() {
        assertEquals(1L, ServerLimits(firstPollDelayMs = 1L).firstPollDelayMs)
        assertEquals(2_000L, ServerLimits(firstPollDelayMs = 2_000L).firstPollDelayMs)
        assertEquals(cap, ServerLimits(firstPollDelayMs = cap, pollDelayCapMs = cap).firstPollDelayMs)
        refused { ServerLimits(firstPollDelayMs = 0L) }
        refused { ServerLimits(firstPollDelayMs = 2_001L) }
        refused { ServerLimits(firstPollDelayMs = cap + 1L, pollDelayCapMs = cap) }
    }

    @Test
    fun `poll delay cap accepts the edges and refuses just past them`() {
        assertEquals(1L, ServerLimits(firstPollDelayMs = 1L, pollDelayCapMs = 1L).pollDelayCapMs)
        assertEquals(cap, ServerLimits(pollDelayCapMs = cap).pollDelayCapMs)
        refused { ServerLimits(firstPollDelayMs = 1L, pollDelayCapMs = 0L) }
        refused { ServerLimits(firstPollDelayMs = 1L, pollDelayCapMs = cap + 1L) }
    }

    @Test
    fun `base budget accepts the edges and refuses just past them`() {
        assertEquals(1L, ServerLimits(baseBudgetMs = 1L, maxBudgetMs = 1L).baseBudgetMs)
        assertEquals(cap, ServerLimits(baseBudgetMs = cap, maxBudgetMs = cap).baseBudgetMs)
        refused { ServerLimits(baseBudgetMs = 0L) }
        refused { ServerLimits(baseBudgetMs = cap + 1L, maxBudgetMs = cap) }
    }

    @Test
    fun `max budget accepts the edges and refuses below the base budget and past the cap`() {
        assertEquals(1L, ServerLimits(baseBudgetMs = 1L, maxBudgetMs = 1L).maxBudgetMs)
        assertEquals(cap, ServerLimits(maxBudgetMs = cap).maxBudgetMs)
        assertEquals(60_000L, ServerLimits(maxBudgetMs = 60_000L).maxBudgetMs)
        refused { ServerLimits(maxBudgetMs = 0L) }
        refused { ServerLimits(maxBudgetMs = cap + 1L) }
        refused { ServerLimits(maxBudgetMs = 59_999L) }
    }

    @Test
    fun `clip factor accepts 0 and 10 and refuses just outside them`() {
        assertEquals(0, ServerLimits(clipFactor = 0).clipFactor)
        assertEquals(10, ServerLimits(clipFactor = 10).clipFactor)
        refused { ServerLimits(clipFactor = -1) }
        refused { ServerLimits(clipFactor = 11) }
    }

    @Test
    fun `max polls accepts 1 and MAX_POLLS and refuses 0 and one past MAX_POLLS`() {
        assertEquals(1, ServerLimits(maxPolls = 1).maxPolls)
        assertEquals(ServerLimits.MAX_POLLS, ServerLimits(maxPolls = ServerLimits.MAX_POLLS).maxPolls)
        refused { ServerLimits(maxPolls = 0) }
        refused { ServerLimits(maxPolls = ServerLimits.MAX_POLLS + 1) }
    }

    @Test
    fun `transient failure limit accepts 0 and MAX_FAILURES and refuses just outside them`() {
        assertEquals(0, ServerLimits(maxTransientPollFailures = 0).maxTransientPollFailures)
        assertEquals(10, ServerLimits(maxTransientPollFailures = 10).maxTransientPollFailures)
        refused { ServerLimits(maxTransientPollFailures = -1) }
        refused { ServerLimits(maxTransientPollFailures = 11) }
    }

    @Test
    fun `budget for an empty or negative clip is the base budget`() {
        val limits = ServerLimits()
        assertEquals(60_000L, limits.budgetFor(0L))
        assertEquals(60_000L, limits.budgetFor(-1L))
        assertEquals(60_000L, limits.budgetFor(Long.MIN_VALUE))
    }

    @Test
    fun `budget for a small clip adds clip factor times the clip length`() {
        assertEquals(60_003L, ServerLimits().budgetFor(1L))
        assertEquals(90_000L, ServerLimits().budgetFor(10_000L))
    }

    @Test
    fun `budget for a large clip is capped at the max budget`() {
        assertEquals(300_000L, ServerLimits().budgetFor(100_000L))
        assertEquals(300_000L, ServerLimits().budgetFor(1_000_000_000L))
    }

    @Test
    fun `budget at the cap boundary is exact and one millisecond more stays capped`() {
        // room = 300000 - 60000 = 240000, and 240000 / 3 = 80000
        assertEquals(299_997L, ServerLimits().budgetFor(79_999L))
        assertEquals(300_000L, ServerLimits().budgetFor(80_000L))
        assertEquals(300_000L, ServerLimits().budgetFor(80_001L))
    }

    @Test
    fun `budget equal to the base budget still caps a one millisecond clip`() {
        assertEquals(60_000L, ServerLimits(maxBudgetMs = 60_000L).budgetFor(1L))
    }

    @Test
    fun `budget for the largest clip does not overflow`() {
        assertEquals(300_000L, ServerLimits().budgetFor(Long.MAX_VALUE))
        assertEquals(300_000L, ServerLimits(clipFactor = 10).budgetFor(Long.MAX_VALUE))
        assertEquals(60_000L, ServerLimits(clipFactor = 0).budgetFor(Long.MAX_VALUE))
    }
}
