package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingPalette
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A drop on the close target counts only when the target window was actually shown during that drag.
 * The window seam returns false when the platform refuses the add, so a refused drag must save the
 * position instead of closing the tile. [SeamWindow] stands in for that refusal without touching
 * [FakeTileWindow]: it delegates the whole seam and answers showCloseTarget itself.
 *
 * The refusal flag is cleared on every drag end and cancel path, and the latest show result wins, so a
 * later successful show makes the drop close again. These tests run on a plain JVM.
 */
class CloseTargetRefusedDropTest {

    /**
     * The whole tile seam, delegated to a [FakeTileWindow], with showCloseTarget answered from [refuse].
     * The call is still recorded on the fake, so a test can see that the show was attempted.
     */
    private class SeamWindow(
        val fake: FakeTileWindow = FakeTileWindow(),
        var refuse: Boolean = false,
    ) : TileWindow by fake {
        override fun showCloseTarget(x: Int, y: Int, widthPx: Int, heightPx: Int, palette: TruckingPalette): Boolean {
            fake.showCloseTarget(x, y, widthPx, heightPx, palette)
            return !refuse
        }
    }

    /** What one drop did: whether the tile closed, and whether the position was saved. */
    private class Outcome(val closed: Boolean, val saved: Boolean)

    private val window = SeamWindow()
    private val centre = CloseTarget.targetCentreInPixels(window.usableBounds())
    private val half = window.tileSizePx() / 2

    /** The flag lives in one object shared by the whole suite, so every test leaves it cleared. */
    @After
    fun clearTheDragFlag() {
        CloseTarget.onDragCancel(FakeTileWindow())
    }

    /** Drops the collapsed tile with its centre on the target centre and reports the outcome. */
    private fun dropOnTarget(seam: SeamWindow): Outcome {
        var closed = false
        var saved = false
        CloseTarget.onDragEnd(
            seam, true, centre.first - half, centre.second - half,
            { closed = true }, {}, { saved = true },
        )
        return Outcome(closed, saved)
    }

    /** A failure means a refused target still closed the tile, so a drag the user never saw ended the tile. */
    @Test
    fun `a refused show then a drop on the target saves the position`() {
        val seam = SeamWindow(refuse = true)
        CloseTarget.onDragStep(seam, ThemeMode.LIGHT)
        assertEquals("overlay: control: the drag step must attempt the show once", 1, seam.fake.closeTargetShows.size)
        val outcome = dropOnTarget(seam)
        assertTrue("overlay: a drop after a refused show should save the position", outcome.saved)
        assertFalse("overlay: a drop after a refused show should not close the tile", outcome.closed)
        assertEquals("overlay: a refused drop should not remove the tile", 0, seam.fake.removeCount)
        assertTrue("overlay: a refused drop should hide the target", seam.fake.closeTargetHideCount > 0)
    }

    /** A failure means the refusal survived the drop that consumed it, so the next good drag also saved. */
    @Test
    fun `a refused drop clears the refusal for the next drag`() {
        val refused = SeamWindow(refuse = true)
        CloseTarget.onDragStep(refused, ThemeMode.LIGHT)
        assertTrue("overlay: the refused drop should save", dropOnTarget(refused).saved)
        // No drag step here: a shown step would set the flag false and hide a missing reset.
        val next = SeamWindow()
        assertTrue("overlay: the next drop with no drag step should close", dropOnTarget(next).closed)
    }

    /** A failure means a shown target no longer closes the tile, so the close gesture was lost. */
    @Test
    fun `a shown target then a drop on the target closes the tile`() {
        val seam = SeamWindow()
        CloseTarget.onDragStep(seam, ThemeMode.LIGHT)
        val outcome = dropOnTarget(seam)
        assertTrue("overlay: a drop after a shown target should close the tile", outcome.closed)
        assertFalse("overlay: a drop that closes should not save the position", outcome.saved)
        assertEquals("overlay: a closing drop removes the tile once", 1, seam.fake.removeCount)
    }

    /** A failure means an early refusal outweighs a later successful show, so the latest result is not the one used. */
    @Test
    fun `the latest show result wins over an earlier refusal`() {
        val seam = SeamWindow(refuse = true)
        CloseTarget.onDragStep(seam, ThemeMode.LIGHT)
        seam.refuse = false
        CloseTarget.onDragStep(seam, ThemeMode.LIGHT)
        assertEquals("overlay: control: both drag steps must attempt the show", 2, seam.fake.closeTargetShows.size)
        assertTrue("overlay: a show that succeeded after a refusal should close the tile", dropOnTarget(seam).closed)
    }

    /** A failure means a cancelled drag left its refusal behind, so the next good drag saved instead of closing. */
    @Test
    fun `a cancel after a refusal clears the refusal`() {
        val seam = SeamWindow(refuse = true)
        CloseTarget.onDragStep(seam, ThemeMode.LIGHT)
        CloseTarget.onDragCancel(seam)
        // No drag step: the cancel alone must have cleared the refusal.
        val next = SeamWindow()
        assertTrue("overlay: a drop after a cancelled refusal should close", dropOnTarget(next).closed)
    }

    /** A failure means the not-collapsed path left its refusal behind, so the next good drag saved instead of closing. */
    @Test
    fun `a drop of a tile that is not collapsed clears the refusal`() {
        val seam = SeamWindow(refuse = true)
        CloseTarget.onDragStep(seam, ThemeMode.LIGHT)
        var closed = false
        var saved = false
        CloseTarget.onDragEnd(seam, false, 0, 0, { closed = true }, {}, { saved = true })
        assertFalse("overlay: a tile that is not collapsed never closes", closed)
        assertFalse("overlay: a tile that is not collapsed never saves", saved)
        // No drag step: the not-collapsed path alone must have cleared the refusal.
        val next = SeamWindow()
        assertTrue("overlay: a drop after a not-collapsed drop should close", dropOnTarget(next).closed)
    }
}
