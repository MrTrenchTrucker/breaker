package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * Two checks the neighbours let through. (a) The 48 dp floor: on a tile so small that its controls are
 * under 48 dp, every bound must still be lifted to 48 dp and no further (the larger sizes of
 * MicAccessibilityTest never reach the floor). (b) The click site in ScreenMicNodeProvider: a click on a child must
 * hand [MicAccessibility.run] the child's action, then onCancel, then onSend, in that order.
 */
internal class MicAccessibilityFloorTest {

    private val densities = listOf(1.0f, 2.0f, 2.75f)
    private val smallSizes = listOf(24, 40)

    private fun assertFires(what: String, problems: List<String>) =
        assertTrue("android_overlay: $what must be reported, got $problems", problems.isNotEmpty())

    /** The click-site rule: run(node.action, view.onCancel, view.onSend), the callbacks in this order. */
    private val clickRule = Regex("""MicAccessibility\s*\.\s*run\s*\(\s*node\s*\.\s*action\s*,\s*view\s*\.\s*onCancel\s*,\s*view\s*\.\s*onSend\s*\)""")

    private fun clickProblems(source: String): List<String> =
        if (clickRule.containsMatchIn(SourceText.code(source))) emptyList() else listOf("the click site does not run (node.action, onCancel, onSend) in order")

    /** A failure here means the floor constant itself was changed from the 48 dp the platform asks for. */
    @Test
    fun `the floor constant is 48 dp`() {
        assertEquals("android_overlay: MIN_TARGET_DP is 48 dp", 48f, MicAccessibility.MIN_TARGET_DP, 0f)
    }

    /** A failure here means a small tile's child bound is below 48 dp, or was grown past it. */
    @Test
    fun `small tile bounds are lifted to exactly 48 dp`() {
        for (density in densities) {
            val floor = ceil(48f * density).toInt()  // the literal 48 on purpose: the constant must not grade itself
            for (s in smallSizes) {
                val nodes = MicAccessibility.nodes(TileShape.RECORDING, s, density, "c", "s")
                assertEquals("android_overlay: two children at $density x $s", 2, nodes.size)
                for (node in nodes) {
                    assertTrue("android_overlay: ${node.label} width ${node.bounds.width} is at least $floor ($density x $s)", node.bounds.width >= floor)
                    assertTrue("android_overlay: ${node.label} height ${node.bounds.height} is at least $floor ($density x $s)", node.bounds.height >= floor)
                    assertTrue("android_overlay: ${node.label} width ${node.bounds.width} is lifted to the floor, not past it ($density x $s)", node.bounds.width <= floor + 2)
                    assertTrue("android_overlay: ${node.label} height ${node.bounds.height} is lifted to the floor, not past it ($density x $s)", node.bounds.height <= floor + 2)
                }
            }
        }
    }

    /** A failure here means ScreenMicNodeProvider's click site no longer passes the cancel callback before the send callback. */
    @Test
    fun `click site passes action then cancel then send`() {
        assertEquals("android_overlay: the correct click site is quiet", emptyList<String>(),
            clickProblems("AccessibilityNodeInfo.ACTION_CLICK -> MicAccessibility.run(node.action, view.onCancel, view.onSend)"))
        assertEquals("android_overlay: a spaced click site is quiet", emptyList<String>(),
            clickProblems("MicAccessibility . run( node.action , view.onCancel , view.onSend )"))
        assertFires("swapped callbacks", clickProblems("MicAccessibility.run(node.action, view.onSend, view.onCancel)"))
        assertFires("a fixed action", clickProblems("MicAccessibility.run(MicA11yAction.CANCEL, view.onCancel, view.onSend)"))
        assertFires("a commented-out call", clickProblems("// MicAccessibility.run(node.action, view.onCancel, view.onSend)"))
        assertEquals("android_overlay: ScreenMicNodeProvider.kt must run the child's action, then onCancel, then onSend", emptyList<String>(),
            clickProblems(ModuleFiles.mainTexts().getValue("ScreenMicNodeProvider.kt")))
    }
}
