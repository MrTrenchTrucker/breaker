package dev.breaker.dictation.host

import dev.breaker.dictation.ui.DownloadControl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The completion of a model delete draws the settings control again, both when the delete removed the files and
 * when it was refused, and it does nothing when no settings control is shown. The control is a fake whose refresh
 * counts its calls. Nothing here has been compiled or run: every expectation is a hand trace.
 */
class ModelDeleteRedrawTest {

    private class CountingControl : DownloadControl {
        var refreshes: Int = 0

        override fun refresh() {
            refreshes += 1
        }

        override fun focusDownload(): Boolean = false
    }

    @Test
    fun `a finished delete draws the settings control again once`() {
        val control = CountingControl()
        val redraw = redrawOnDelete { control }
        redraw(true)
        assertEquals("app: a finished delete must draw the settings control once", 1, control.refreshes)
    }

    @Test
    fun `a refused delete also draws the settings control again once`() {
        val control = CountingControl()
        val redraw = redrawOnDelete { control }
        redraw(false)
        assertEquals("app: a refused delete must draw the settings control once, with no change", 1, control.refreshes)
    }

    @Test
    fun `a missing settings control is left alone by a delete`() {
        val redraw = redrawOnDelete { null }
        redraw(true)
        redraw(false)
    }
}
