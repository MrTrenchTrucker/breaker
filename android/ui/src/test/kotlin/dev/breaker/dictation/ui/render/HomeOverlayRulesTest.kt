package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Source-text rules for the drawer overlay in HomeHostView.kt: the page is an inner scroll view that
 * takes the host's padding in its one forward; the menu check comes before the action; the overlay is
 * added only while the menu is open; the dim takes the dim's alpha and the panel the panel's width;
 * the page is opened for bleed; the dim and the panel are layered over the page in that order. Each
 * rule reads the text with comments and strings taken out, holds on the real file and fires on an
 * edited copy.
 */
class HomeOverlayRulesTest {
    private val host = mainSourceOf("render/HomeHostView.kt")

    private val whitespace = Regex("""\s+""")

    /** [text] with comments and strings taken out and every run of whitespace removed. */
    private fun flat(text: String): String = withoutCommentsAndStrings(text).replace(whitespace, "")

    /** Whether [text] holds the code [part], compared with comments, strings and whitespace taken out. */
    private fun holds(text: String, part: String): Boolean = flat(text).contains(flat(part))

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue(
            "ui: overlay: the text to change is not in the source exactly once: $old",
            Regex(Regex.escape(old)).findAll(text).count() == 1,
        )
        return text.replace(old, new)
    }

    @Test
    fun `the menu check comes before the action and only closes the menu`() {
        val part = """
            if (intent is ScreenIntent.Home && state.menuOpen && intent.action !in drawerActions) {
                draw(merged(handler.handle(ScreenIntent.Home(HomeActions.CLOSE_MENU))))
                return
            }
            if (intent is ScreenIntent.Home) act(intent.action)
        """.trimIndent()
        assertTrue("overlay: the real host holds the menu check", holds(host, part))
        val removed = replaceOnce(host, "state.menuOpen && intent.action !in drawerActions", "false")
        assertTrue("overlay: the check removed is reported", !holds(removed, part))
        val either = replaceOnce(host, "state.menuOpen && intent.action !in drawerActions", "state.menuOpen || intent.action !in drawerActions")
        assertTrue("overlay: the check with or is reported", !holds(either, part))
    }

    @Test
    fun `the page takes the host's padding in the one forward`() {
        val part = "page?.setPadding(left, top, right, bottom)"
        assertTrue("overlay: the real host forwards its padding", holds(host, part))
        val zero = replaceOnce(host, part, "page?.setPadding(0, 0, 0, 0)")
        assertTrue("overlay: a padding of zero is reported", !holds(zero, part))
        val commented = replaceOnce(host, part, "// page?.setPadding(left, top, right, bottom)")
        assertTrue("overlay: a forward in a comment only is reported", !holds(commented, part))
    }

    @Test
    fun `the overlay is added only while the menu is open`() {
        val part = "if (nodes.isEmpty()) { if (overlay.parent === this) removeView(overlay) return }"
        assertTrue("overlay: the real host takes the overlay off when there are no drawer nodes", holds(host, part))
        val always = replaceOnce(host, "if (nodes.isEmpty()) {", "if (false) {")
        assertTrue("overlay: the overlay always on is reported", !holds(always, part))
        val kept = replaceOnce(host, "if (overlay.parent === this) removeView(overlay)", "")
        assertTrue("overlay: the overlay never taken off is reported", !holds(kept, part))
    }

    @Test
    fun `the dim takes the dim's alpha and the panel takes the panel's width`() {
        val dimPart = "dim.alpha = HomeGeometry.DRAWER_DIM"
        val panelPart = "panel.layoutParams = LinearLayout.LayoutParams((HomeGeometry.PANEL_W_DP * resources.displayMetrics.density).toInt()"
        assertTrue("overlay: the real dim takes the alpha", holds(host, dimPart))
        assertTrue("overlay: the real panel takes the width", holds(host, panelPart))
        assertTrue("overlay: a full dim is reported", !holds(replaceOnce(host, dimPart, "dim.alpha = 1f"), dimPart))
        assertTrue("overlay: a commented alpha is reported", !holds(replaceOnce(host, dimPart, "// $dimPart"), dimPart))
        assertTrue(
            "overlay: a full-width panel is reported",
            !holds(replaceOnce(host, "(HomeGeometry.PANEL_W_DP * resources.displayMetrics.density).toInt(), ViewGroup", "ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup"), panelPart),
        )
        assertTrue(
            "overlay: a doubled panel width is reported",
            !holds(replaceOnce(host, "(HomeGeometry.PANEL_W_DP * resources.displayMetrics.density).toInt()", "(HomeGeometry.PANEL_W_DP * resources.displayMetrics.density * 2).toInt()"), panelPart),
        )
    }

    @Test
    fun `the page is opened for bleed in the init block`() {
        val part = "scroll.openForBleed() page = scroll"
        assertTrue("overlay: the real page is opened for bleed", holds(host, part))
        assertTrue("overlay: a page not opened is reported", !holds(replaceOnce(host, "        scroll.openForBleed()\n", ""), part))
        assertTrue("overlay: a commented opening is reported", !holds(replaceOnce(host, "        scroll.openForBleed()\n", "        // scroll.openForBleed()\n"), part))
    }

    @Test
    fun `the dim and the panel are layered over the page in that order`() {
        val part = "overlay.addView(layerOf(dimRoot)) overlay.addView(layerOf(panelRoot))"
        assertTrue("overlay: the real layers are in order", holds(host, part))
        assertTrue(
            "overlay: the panel layer alone is reported",
            !holds(replaceOnce(host, "        overlay.addView(layerOf(dimRoot))\n", ""), part),
        )
        assertTrue(
            "overlay: the layers swapped are reported",
            !holds(replaceOnce(host, "        overlay.addView(layerOf(dimRoot))\n        overlay.addView(layerOf(panelRoot))\n", "        overlay.addView(layerOf(panelRoot))\n        overlay.addView(layerOf(dimRoot))\n"), part),
        )
    }
}
