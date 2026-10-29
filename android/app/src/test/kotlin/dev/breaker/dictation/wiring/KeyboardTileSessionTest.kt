package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure [KeyboardTileSession] tests: the session state machine with no coordinator or Android type, so every
 * gate can be pinned from a plain JVM. Each test pins one session rule; the first pins the first-reading baseline.
 */
class KeyboardTileSessionTest {

    @Test
    fun `first reading acts without debounce`() {
        val s = KeyboardTileSession()
        assertEquals(KeyboardDecision.Show, s.onKeyboardReading(true, true, 1000L))
        assertEquals((true to true), s.lastPair())
        assertEquals(1000L, s.anchorMs())
        assertTrue("app: the session is active after the first reading", s.keyboardSessionActive)
    }

    @Test
    fun `an unchanged pair after an acting pair returns no-op and keeps the anchor`() {
        val s = KeyboardTileSession()
        s.onKeyboardReading(true, true, 1000L)
        assertEquals(KeyboardDecision.NoOp, s.onKeyboardReading(true, true, 1400L))
        assertEquals("app: the anchor is unchanged by an unchanged pair", 1000L, s.anchorMs())
    }

    @Test
    fun `a change inside the window returns no-op and leaves the state untouched`() {
        val s = KeyboardTileSession()
        assertEquals(KeyboardDecision.NoOp, s.onKeyboardReading(false, false, 0))
        assertEquals(KeyboardDecision.NoOp, s.onKeyboardReading(true, true, 100))
        assertNull("app: the previous pair stays null until an acting change", s.lastPair())
        assertEquals("app: no anchor is set before an acting change", 0L, s.anchorMs())
    }

    @Test
    fun `an acting change records the pair for the gate`() {
        val s = KeyboardTileSession()
        assertEquals(KeyboardDecision.Show, s.onKeyboardReading(true, true, 1000L))
        assertEquals("app: an acting change records its pair for the unchanged-pair gate", (true to true), s.lastPair())
        assertEquals(KeyboardDecision.NoOp, s.onKeyboardReading(true, true, 2000L))
    }

    @Test
    fun `the anchor is set on acting change and gates a later in-window change`() {
        val s = KeyboardTileSession()
        s.onKeyboardReading(true, true, 5000L)
        assertEquals(5000L, s.anchorMs())
        assertEquals(KeyboardDecision.NoOp, s.onKeyboardReading(false, false, 5100))
        assertEquals("app: the anchor is unchanged by an in-window change", 5000L, s.anchorMs())
    }

    @Test
    fun `a keyboard-open reading without a field starts the session`() {
        val s = KeyboardTileSession()
        assertEquals(KeyboardDecision.NoOp, s.onKeyboardReading(true, false, 1000))
        assertTrue("app: ime present with no field marks the session active", s.keyboardSessionActive)

        val inactive = KeyboardTileSession()
        assertEquals(KeyboardDecision.NoOp, inactive.onKeyboardReading(false, false, 0))
        assertFalse("app: a session that only sees ime gone stays inactive", inactive.keyboardSessionActive)
    }

    @Test
    fun `a close while active resets the flags on the next acted reading`() {
        val s = KeyboardTileSession()
        s.onKeyboardReading(true, true, 1000)
        assertTrue(s.keyboardSessionActive)
        s.setCloseWhileActive(true)
        assertEquals(KeyboardDecision.NoOp, s.onKeyboardReading(false, false, 4000))
        assertFalse("app: the session is no longer active", s.keyboardSessionActive)
        assertFalse("app: the raised flag is reset after a session end", s.keyboardRaised)
        assertFalse("app: the closed-during-session flag is reset after a session end", s.closedDuringSession)
    }

    @Test
    fun `reporting a show then an ime gone clears the raised flag`() {
        val s = KeyboardTileSession()
        s.reportShown(false, true)
        assertTrue("app: reporting a raise sets the raised flag", s.keyboardRaised)
        s.onKeyboardReading(false, false, 400)
        assertFalse("app: an acted ime gone clears the raised flag", s.keyboardRaised)
    }

    @Test
    fun `reporting a non-show does not mark the tile as raised`() {
        val active = KeyboardTileSession()
        assertEquals(KeyboardDecision.NoOp, active.onKeyboardReading(false, false, 0))
        active.reportShown(true, true)
        assertFalse("app: reporting a show that was already shown does not raise", active.keyboardRaised)

        val raised = KeyboardTileSession()
        raised.reportShown(false, true)
        assertTrue("app: reporting a first raise sets the raised flag", raised.keyboardRaised)
    }

    @Test
    fun `an acting ime gone ends the session and it does not restart on a stale pair`() {
        val s = KeyboardTileSession()
        assertEquals(KeyboardDecision.Show, s.onKeyboardReading(true, true, 1000L))
        assertTrue(s.keyboardSessionActive)
        assertEquals(KeyboardDecision.NoOp, s.onKeyboardReading(false, false, 5000))
        assertFalse("app: the session is no longer active after an acted ime gone", s.keyboardSessionActive)
    }
}
