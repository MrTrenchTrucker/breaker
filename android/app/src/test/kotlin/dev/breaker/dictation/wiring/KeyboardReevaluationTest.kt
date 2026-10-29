package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A keyboard reading that arrives inside the debounce window is held, and the newest held reading is
 * re-evaluated once, when the window ends. The fake delayed post runs only when a test advances it, so each
 * test decides when the window closes. The coordinator here is built over the rig's own fakes, with the
 * fake delayed post passed in.
 */
internal class KeyboardReevaluationTest {

    private class Setup {
        val rig = TileRig(arm = false)
        val delayed = FakeDelayedPost()
        val coordinator = TileCoordinator(
            rig.tile, rig.main, rig.background, rig.take, rig.model, rig.notice, rig.opener,
            delayed = delayed,
        )

        /** Brings the tile up through a keyboard show, so the session records that it raised the tile. */
        fun raisedByKeyboard(): Setup {
            rig.tile.showResult = TileShow.NO_PERMISSION
            coordinator.onArmedChanged(true)
            rig.tile.showResult = TileShow.SHOWN
            coordinator.onKeyboardReading(true, true, 1000L)
            rig.settle()
            assertTrue("the keyboard show must raise the tile", rig.tile.shown)
            return this
        }

        fun hides(): Int = rig.tile.calls.count { it == "hide" }
    }

    @Test
    fun `a close inside the window hides the tile when the window ends`() {
        val s = Setup().raisedByKeyboard()
        s.coordinator.onKeyboardReading(false, false, 1100L)
        s.rig.settle()
        assertEquals("the close inside the window holds one post", 1, s.delayed.posts.size)
        assertEquals("the post is due when the window ends, 150 ms after the close", 150L, s.delayed.posts.single().dueAt)
        assertTrue("the tile stays up while the window is open", s.rig.tile.shown)
        assertEquals("no hide before the window ends", 0, s.hides())
        s.delayed.advance(150L)
        s.rig.settle()
        assertEquals("the held close hides the tile once, when the window ends", 1, s.hides())
        assertFalse("the tile is down after the window ends", s.rig.tile.shown)
    }

    @Test
    fun `a take that begins while a re-evaluation is held acts the reading at once and holds its hide to the take end`() {
        val s = Setup().raisedByKeyboard()
        s.coordinator.onKeyboardReading(false, false, 1100L)
        s.rig.settle()
        assertEquals("one post is held before the take begins", 1, s.delayed.posts.size)
        s.coordinator.onBegin()
        s.rig.settle()
        assertEquals("the take begins once", 1, s.rig.take.beginCalls)
        assertEquals("no post is left live through the take", 0, s.delayed.live())
        assertEquals("the flushed hide is held while the take records", 0, s.hides())
        assertTrue("the tile stays up while the take records", s.rig.tile.shown)
        s.delayed.advance(150L)
        s.rig.settle()
        assertFalse("the flushed post never runs", s.delayed.posts.single().fired)
        assertEquals("nothing hides while the take records", 0, s.hides())
        s.rig.take.sessionState = DictationState.IDLE
        s.coordinator.onTakeEnded()
        s.rig.settle()
        assertEquals("the held hide applies once at take end, the keyboard is still gone", 1, s.hides())
        assertFalse("the tile is down after the take end", s.rig.tile.shown)
    }

    @Test
    fun `a take that begins after a keyboard back reading holds no hide at take end`() {
        val s = Setup().raisedByKeyboard()
        s.coordinator.onKeyboardReading(false, false, 1100L)
        s.coordinator.onKeyboardReading(true, true, 1200L)
        s.rig.settle()
        assertEquals("the back reading replaces the held one, one post is held", 1, s.delayed.posts.size)
        s.coordinator.onBegin()
        s.rig.settle()
        assertEquals("the take begins once", 1, s.rig.take.beginCalls)
        assertEquals("no post is left live through the take", 0, s.delayed.live())
        assertTrue("the tile stays up while the take records", s.rig.tile.shown)
        s.rig.take.sessionState = DictationState.IDLE
        s.coordinator.onTakeEnded()
        s.rig.settle()
        assertEquals("the keyboard is back, so no hide at take end", 0, s.hides())
        assertTrue("the tile stays up after the take end", s.rig.tile.shown)
    }

    @Test
    fun `two readings inside one window re-evaluate once with the newest reading`() {
        val s = Setup().raisedByKeyboard()
        s.coordinator.onKeyboardReading(false, false, 1050L)
        s.coordinator.onKeyboardReading(true, true, 1100L)
        s.rig.settle()
        assertEquals("the second reading inside the window adds no post", 1, s.delayed.posts.size)
        assertEquals("the post stays due when the first window ends", 200L, s.delayed.posts.single().dueAt)
        s.delayed.advance(200L)
        s.rig.settle()
        assertEquals("the newest reading says the keyboard is back, so nothing hides", 0, s.hides())
        assertTrue("the tile stays up", s.rig.tile.shown)
    }

    @Test
    fun `a newest reading that closes the keyboard hides the tile after an earlier reading that opened it`() {
        val s = Setup().raisedByKeyboard()
        s.coordinator.onKeyboardReading(true, true, 1050L)
        s.coordinator.onKeyboardReading(false, false, 1100L)
        s.rig.settle()
        assertEquals("one post is held for the window", 1, s.delayed.posts.size)
        s.delayed.advance(200L)
        s.rig.settle()
        assertEquals("the newest reading says the keyboard is gone, so the tile hides once", 1, s.hides())
        assertFalse("the tile is down", s.rig.tile.shown)
    }

    @Test
    fun `at most one re-evaluation is pending through a run of readings in one window`() {
        val s = Setup().raisedByKeyboard()
        s.coordinator.onKeyboardReading(false, false, 1050L)
        s.coordinator.onKeyboardReading(true, true, 1080L)
        s.coordinator.onKeyboardReading(false, false, 1120L)
        s.coordinator.onKeyboardReading(true, true, 1200L)
        s.rig.settle()
        assertEquals("one post was made for the window", 1, s.delayed.posts.size)
        assertEquals("one post is live", 1, s.delayed.live())
    }

    @Test
    fun `switching the tile off cancels a held re-evaluation`() {
        val s = Setup().raisedByKeyboard()
        s.coordinator.onKeyboardReading(false, false, 1100L)
        s.rig.settle()
        s.coordinator.onArmedChanged(false)
        s.rig.settle()
        assertTrue("the switch off cancels the held post", s.delayed.posts.single().cancelled)
        val callsBefore: Int = s.rig.tile.calls.size
        s.delayed.advance(150L)
        s.rig.settle()
        assertFalse("the cancelled post never runs", s.delayed.posts.single().fired)
        assertEquals("nothing reaches the tile after the switch off", callsBefore, s.rig.tile.calls.size)
    }

    @Test
    fun `a reading after the window applies the held re-evaluation first and then holds its own`() {
        val s = Setup().raisedByKeyboard()
        s.coordinator.onKeyboardReading(false, false, 1100L)
        s.coordinator.onKeyboardReading(true, true, 1300L)
        s.rig.settle()
        assertEquals("the held close hides the tile when a reading arrives after its window", 1, s.hides())
        assertFalse("the tile is down after that hide", s.rig.tile.shown)
        assertTrue("the held post is cancelled when it is run early", s.delayed.posts[0].cancelled)
        assertEquals("the reading after the window is held in a new window", 2, s.delayed.posts.size)
        assertEquals("the new window ends 200 ms after that reading", 200L, s.delayed.posts[1].dueAt)
        s.delayed.advance(200L)
        s.rig.settle()
        assertTrue("the held keyboard show brings the tile back when its window ends", s.rig.tile.shown)
        assertEquals("the second show leaves the one hide in place", 1, s.hides())
    }

    @Test
    fun `a keyboard show during a take clears a held hide, and a swallowed gone reading hides once at the window end`() {
        val s = Setup().raisedByKeyboard()
        s.coordinator.onBegin()
        s.rig.settle()
        assertEquals("the take records", 1, s.rig.take.beginCalls)
        s.coordinator.onKeyboardReading(false, false, 2000L)
        s.rig.settle()
        assertTrue("the gone hide is held while the take records", s.rig.tile.shown)
        assertEquals("no hide while the take records", 0, s.hides())
        s.coordinator.onKeyboardReading(true, true, 2300L)
        s.rig.settle()
        assertTrue("the back show is refused during the take and the tile stays up", s.rig.tile.shown)
        s.coordinator.onKeyboardReading(false, false, 2400L)
        s.rig.settle()
        assertEquals("the gone reading inside the window is held, no hide yet", 0, s.hides())
        s.rig.take.sessionState = DictationState.IDLE
        s.coordinator.onTakeEnded()
        s.rig.settle()
        assertEquals("the take end applies no hide, because the back show cleared the held hide", 0, s.hides())
        assertTrue("the tile stays up after the take end", s.rig.tile.shown)
        s.delayed.advance(150L)
        s.rig.settle()
        assertEquals(
            "the window end hides once: the raised mark survived the back show and the keyboard is gone",
            1,
            s.hides(),
        )
        assertFalse("the tile is down after the window end", s.rig.tile.shown)
    }

    @Test
    fun `a held hide applied at take end leaves no raised mark, so a later gone reading does not hide again`() {
        val s = Setup().raisedByKeyboard()
        s.coordinator.onBegin()
        s.rig.settle()
        s.coordinator.onKeyboardReading(false, false, 2000L)
        s.rig.settle()
        assertEquals("the hide is held while the take records", 0, s.hides())
        s.rig.take.sessionState = DictationState.IDLE
        s.coordinator.onTakeEnded()
        s.rig.settle()
        assertEquals("the held hide applies once at take end", 1, s.hides())
        s.coordinator.onKeyboardReading(true, false, 3000L)
        s.rig.settle()
        assertEquals("a keyboard with no editable focus does nothing", 1, s.hides())
        s.coordinator.onKeyboardReading(false, false, 4000L)
        s.rig.settle()
        assertEquals("the released raised mark makes the later gone reading a no-op", 1, s.hides())
    }

    @Test
    fun `switching the tile off with a held hide leaves no raised mark, so a later gone reading does nothing`() {
        val s = Setup().raisedByKeyboard()
        s.coordinator.onBegin()
        s.rig.settle()
        s.coordinator.onKeyboardReading(false, false, 2000L)
        s.rig.settle()
        assertEquals("the hide is held while the take records", 0, s.hides())
        s.coordinator.onArmedChanged(false)
        s.rig.settle()
        assertEquals("the switch off takes the tile down once", 1, s.hides())
        s.coordinator.onArmedChanged(true)
        s.rig.settle()
        assertTrue("the tile is shown again after the switch comes back on", s.rig.tile.shown)
        s.coordinator.onKeyboardReading(true, true, 3000L)
        s.rig.settle()
        s.coordinator.onKeyboardReading(false, false, 4000L)
        s.rig.settle()
        assertEquals("the switch-off released the raised mark, so the later gone reading does nothing", 1, s.hides())
    }
}
