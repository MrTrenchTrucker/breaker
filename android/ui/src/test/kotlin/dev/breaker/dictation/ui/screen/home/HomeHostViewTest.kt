package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate test for [dev.breaker.dictation.ui.render.HomeHostView].
 *
 * The host's `setPadding` is called by `applyInsets` with the bar insets on the
 * host's margins and the gutter as its padding. The `panelTop` field must be set
 * to the `top` parameter, so the drawer panel starts at the host's top edge,
 * which is already below the status bar. If `panelTop` were 0, the drawer panel
 * would start at the very top of the host, under the status bar.
 *
 * `attachMicSlot` places the overlay-built mic in the mic slot: its height must
 * be the ruled slot height for the window's usable height
 * ([HomeGeometry.slotHeightDp] of the host's `usableHeight`), converted from dp
 * to pixels with the display density. A height in bare dp would make the slot
 * too short on any screen denser than 1, and a fixed constant would ignore the
 * window height left after the system bars.
 */
internal class HomeHostViewTest {

    private val source: String by lazy { mainSourceOf("render/HomeHostView.kt") }

    private val code: String by lazy { withoutCommentsAndStrings(source) }

    @Test
    fun `setPadding sets panelTop to top`() {
        val flat = code.replace(Regex("""\s+"""), "")
        assertTrue(
            "setPadding must set panelTop = top, but the code does not contain it",
            flat.contains("panelTop=top"),
        )
    }

    @Test
    fun `setPadding does not set panelTop to zero`() {
        val flat = code.replace(Regex("""\s+"""), "")
        assertFalse(
            "setPadding must not set panelTop = 0 — the drawer would start under the status bar",
            flat.contains("panelTop=0"),
        )
    }

    @Test
    fun `panelTop assignment appears inside setPadding`() {
        val setPaddingStart = code.indexOf("override fun setPadding")
        assertTrue("setPadding function not found", setPaddingStart >= 0)

        val nextOverride = code.indexOf("override fun", setPaddingStart + 1)
        val nextFun = code.indexOf("private fun", setPaddingStart + 1)
        val end = listOf(nextOverride, nextFun).filter { it >= 0 }.minOrNull() ?: code.length
        val setPaddingBody = code.substring(setPaddingStart, end)

        val flat = setPaddingBody.replace(Regex("""\s+"""), "")
        assertTrue(
            "panelTop = top must appear inside setPadding",
            flat.contains("panelTop=top"),
        )
    }

    @Test
    fun `setPadding passes all four padding values to the page`() {
        val flat = code.replace(Regex("""\s+"""), "")
        assertTrue(
            "setPadding must pass left, top, right, bottom to page.setPadding",
            flat.contains("page?.setPadding(left,top,right,bottom)"),
        )
    }

    @Test
    fun `attachMicSlot heights the mic from the usable window height in pixels`() {
        val flat = code.replace(Regex("""\s+"""), "")
        assertTrue(
            "attachMicSlot must height the mic as HomeGeometry.slotHeightDp(usableHeight) * resources.displayMetrics.density",
            flat.contains("(HomeGeometry.slotHeightDp(usableHeight)*resources.displayMetrics.density).toInt()"),
        )
    }

    @Test
    fun `attachMicSlot does not take the mic slot height from the constant`() {
        val attachStart = code.indexOf("private fun attachMicSlot")
        assertTrue("attachMicSlot function not found", attachStart >= 0)

        val nextOverride = code.indexOf("override fun", attachStart + 1)
        val nextFun = code.indexOf("private fun", attachStart + 1)
        val end = listOf(nextOverride, nextFun).filter { it >= 0 }.minOrNull() ?: code.length
        val attachBody = code.substring(attachStart, end)

        val flat = attachBody.replace(Regex("""\s+"""), "")
        assertFalse(
            "attachMicSlot must not height the mic from HomeGeometry.MIC_SLOT_MAX_DP — the slot must follow the usable window height",
            flat.contains("HomeGeometry.MIC_SLOT_MAX_DP"),
        )
    }
}
