package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure decision rule and its debounce window by calling [decision] directly, so every case is
 * testable on a plain JVM without a coordinator or Android types. One @Test per branch of the six-argument
 * function: the baseline (no keyboard, not raised -> NO_OP), keyboard up plus focus plus not closed (SHOW),
 * keyboard up plus focus but closed during the session (NO_OP — stays hidden this session), keyboard up with
 * no focus (NO_OP), ime gone while a raise was recorded (HIDE), and ime gone without a prior raise (NO_OP).
 */
class KeyboardTileDecisionTest {

    private fun dec(ime: Boolean, focused: Boolean, raised: Boolean, closed: Boolean, now: Long, last: Long): KeyboardDecision =
        decision(ime, focused, raised, closed, now, last)

    @Test
    fun `no keyboard no focus raises nothing`() {
        assertEquals(KeyboardDecision.NoOp, dec(false, false, false, false, 1000L, 0L))
    }

    @Test
    fun `keyboard up with focus and not closed shows`() {
        assertEquals(KeyboardDecision.Show, dec(true, true, false, false, 1000L, 500L))
    }

    @Test
    fun `keyboard up with focus but closed during the session stays hidden this session`() {
        assertEquals(KeyboardDecision.NoOp, dec(true, true, false, true, 1000L, 500L))
    }

    @Test
    fun `keyboard up without a focused editable field shows neither`() {
        assertEquals(KeyboardDecision.NoOp, dec(true, false, false, false, 1000L, 500L))
    }

    @Test
    fun `ime gone after a raise hides the tile`() {
        assertEquals(KeyboardDecision.Hide, dec(false, false, true, false, 1000L, 500L))
    }

    @Test
    fun `ime gone without a prior raise is restored to no show`() {
        assertEquals(KeyboardDecision.NoOp, dec(false, false, false, false, 1000L, 500L))
    }

    @Test
    fun `a change inside the debounce window is swallowed`() {
        assertEquals(KeyboardDecision.NoOp, dec(true, true, false, false, 700L, 500L))
    }

    @Test
    fun `a change after the window acts`() {
        assertEquals(KeyboardDecision.Show, dec(true, true, false, false, 800L, 500L))
    }

    @Test
    fun `exactly 249ms gap is swallowed`() {
        assertEquals(KeyboardDecision.NoOp, dec(true, true, false, false, 749L, 500L))
    }

    @Test
    fun `exactly 250ms gap acts`() {
        assertEquals(KeyboardDecision.Show, dec(true, true, false, false, 750L, 500L))
    }
}
