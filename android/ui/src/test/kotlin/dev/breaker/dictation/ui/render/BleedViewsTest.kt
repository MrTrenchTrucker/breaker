package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate test for [dev.breaker.dictation.ui.render.openForBleed].
 *
 * The bleed views (RuleView, BottomStripesView) draw full-width layers that reach
 * past the host's padding to both edges of the screen. For this to work, the
 * scroll view must NOT clip its children to its padding — `clipToPadding` must
 * be `false`. If it were `true`, the bleed layers would be clipped at the
 * padding edge and would not reach the screen edges.
 */
internal class BleedViewsTest {

    private val source: String by lazy { mainSourceOf("render/BleedViews.kt") }

    private val code: String by lazy { withoutCommentsAndStrings(source) }

    @Test
    fun `openForBleed sets clipToPadding to false`() {
        val flat = code.replace(Regex("""\s+"""), "")
        assertTrue(
            "openForBleed must set clipToPadding = false, but the code does not contain it",
            flat.contains("clipToPadding=false"),
        )
    }

    @Test
    fun `openForBleed does not set clipToPadding to true`() {
        val flat = code.replace(Regex("""\s+"""), "")
        assertFalse(
            "openForBleed must not set clipToPadding = true — that would clip the bleed layers",
            flat.contains("clipToPadding=true"),
        )
    }

    @Test
    fun `openForBleed sets isFillViewport to true`() {
        val flat = code.replace(Regex("""\s+"""), "")
        assertTrue(
            "openForBleed must set isFillViewport = true",
            flat.contains("isFillViewport=true"),
        )
    }

    @Test
    fun `the clipToPadding assignment appears inside openForBleed`() {
        // Find the openForBleed function and verify clipToPadding=false is within it
        val openForBleedStart = code.indexOf("openForBleed")
        assertTrue("openForBleed function not found", openForBleedStart >= 0)

        // Find the next function after openForBleed
        val nextFun = code.indexOf("internal fun", openForBleedStart + 1)
        val openForBleedBody = if (nextFun >= 0) {
            code.substring(openForBleedStart, nextFun)
        } else {
            code.substring(openForBleedStart)
        }

        val flat = openForBleedBody.replace(Regex("""\s+"""), "")
        assertTrue(
            "clipToPadding=false must appear inside openForBleed",
            flat.contains("clipToPadding=false"),
        )
    }
}
