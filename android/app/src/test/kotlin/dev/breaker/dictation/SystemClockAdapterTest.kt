package dev.breaker.dictation

import org.junit.Assert.assertTrue
import org.junit.Test

class SystemClockAdapterTest {

    @Test
    fun `nowEpochMillis is a recent system clock value`() {
        val before = System.currentTimeMillis()
        val now = SystemClockAdapter.nowEpochMillis()
        val after = System.currentTimeMillis()
        assertTrue(
            "the clock must read the system wall clock (got $now, expected within [$before, $after])",
            now >= before && now <= after,
        )
    }

    @Test
    fun `nowEpochMillis is non-decreasing`() {
        val a = SystemClockAdapter.nowEpochMillis()
        val b = SystemClockAdapter.nowEpochMillis()
        assertTrue("the clock must not go backwards (got $a then $b)", b >= a)
    }
}
