package dev.breaker.dictation.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * The three download calls of the model step control have default bodies, so a view that
 * overrides only the older calls still compiles, and the calls do nothing to it.
 */
class ModelStepControlDefaultsTest {
    /** Overrides only the calls that have no default, and counts the redraws it is asked for. */
    private class Bare : ModelStepControl {
        var refreshes = 0

        override fun refresh() {
            refreshes++
        }

        override fun focusDownload(): Boolean = false

        override fun showSwitchRefused(sentence: String) {}
    }

    /** Overrides the progress call and keeps every pair of numbers it receives. */
    private class Recording : ModelStepControl {
        val progress = mutableListOf<Pair<Int, Int>>()

        override fun refresh() {}

        override fun focusDownload(): Boolean = false

        override fun showSwitchRefused(sentence: String) {}

        override fun downloadProgress(doneMb: Int, totalMb: Int) {
            progress.add(doneMb to totalMb)
        }
    }

    @Test
    fun `the three download calls do nothing and throw nothing on a view that does not override them`() {
        val control = Bare()
        control.downloadProgress(12, 365)
        control.downloadProgress(0, 0)
        control.downloadChecking()
        control.downloadStopped()
        assertEquals("the redraws the calls caused", 0, control.refreshes)
    }

    @Test
    fun `a view that overrides the progress call receives the numbers unchanged`() {
        val control = Recording()
        control.downloadProgress(12, 365)
        control.downloadProgress(0, 0)
        assertEquals(listOf(12 to 365, 0 to 0), control.progress)
    }
}
