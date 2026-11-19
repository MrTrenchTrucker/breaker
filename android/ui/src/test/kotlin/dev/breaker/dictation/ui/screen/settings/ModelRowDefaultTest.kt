package dev.breaker.dictation.ui.screen.settings

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model row the settings screen uses when the host passes none: the speech model is ready,
 * there is no download line, and there are no per-model rows.
 */
class ModelRowDefaultTest {

    @Test
    fun `the default model row says the speech model is ready`() {
        assertTrue(ModelRow.READY.ready)
    }

    @Test
    fun `the default model row carries no download line`() {
        assertNull(ModelRow.READY.line)
    }

    @Test
    fun `the default model row carries no offered model rows`() {
        assertTrue(ModelRow.READY.rows.isEmpty())
    }
}
