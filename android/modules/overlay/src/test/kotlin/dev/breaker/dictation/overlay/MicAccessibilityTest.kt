package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

/**
 * MicAccessibility: the pure logic of the recording mic's two accessibility children. (a) non-recording
 * shapes expose none; (b) RECORDING exposes cancel then send with distinct, unswapped labels and ids;
 * (c) every child bound reaches 48 dp both ways for each density and tile size and contains the centre of
 * its ControlLayout placement; (d) cancel sits left of send without overlap; (e) run routes only its own
 * callback. Every assertion message is prefixed `android_overlay: ` like the neighbours.
 */
internal class MicAccessibilityTest {

    private val densities = listOf(1.0f, 2.0f, 2.75f)
    private val sizes = listOf(96, 144, 200)
    private val cancelLabel = "cancel words"
    private val sendLabel = "send words"

    /** A failure here means COLLAPSED exposes accessibility children; it should expose none. */
    @Test
    fun `collapsed exposes no accessibility children`() {
        assertEquals("android_overlay: collapsed tile exposes no accessibility nodes", emptyList<MicA11yNode>(), MicAccessibility.nodes(TileShape.COLLAPSED, 144, 2f, cancelLabel, sendLabel))
    }

    /** A failure here means NOTICE exposes accessibility children; it should expose none. */
    @Test
    fun `notice exposes no accessibility children`() {
        assertEquals("android_overlay: notice window exposes no accessibility nodes", emptyList<MicA11yNode>(), MicAccessibility.nodes(TileShape.NOTICE, 144, 2f, cancelLabel, sendLabel))
    }

    /** A failure here means RECORDING does not expose exactly two children in cancel-then-send order. */
    @Test
    fun `recording exposes cancel then send`() {
        val nodes = MicAccessibility.nodes(TileShape.RECORDING, 144, 2f, cancelLabel, sendLabel)
        assertEquals("android_overlay: recording exposes exactly two accessibility nodes", 2, nodes.size)
        assertEquals("android_overlay: the first child is cancel", MicA11yAction.CANCEL, nodes[0].action)
        assertEquals("android_overlay: the second child is send", MicA11yAction.SEND, nodes[1].action)
        assertEquals("android_overlay: the first child id is the cancel constant", MicAccessibility.CANCEL_ID, nodes[0].id)
        assertEquals("android_overlay: the second child id is the send constant", MicAccessibility.SEND_ID, nodes[1].id)
    }

    /** A failure here means the two children's labels are swapped against their actions. */
    @Test
    fun `cancel carries the cancel label and send the send label`() {
        val nodes = MicAccessibility.nodes(TileShape.RECORDING, 200, 1f, cancelLabel, sendLabel)
        assertEquals("android_overlay: the first (cancel) node carries the cancel label", "cancel words", nodes[0].label)
        assertEquals("android_overlay: the second (send) node carries the send label", "send words", nodes[1].label)
    }

    /** A failure here means some child's own bounds are below the minimum target size on either axis. */
    @Test
    fun `every child bound reaches at least 48 dp both ways`() {
        for (density in densities) {
            for (s in sizes) {
                for (node in MicAccessibility.nodes(TileShape.RECORDING, s, density, cancelLabel, sendLabel)) {
                    assertTrue("android_overlay: ${node.label} bound width is at least 48 dp ($density x $s)", node.bounds.width >= ceil(MicAccessibility.MIN_TARGET_DP * density).toInt())
                    assertTrue("android_overlay: ${node.label} bound height is at least 48 dp ($density x $s)", node.bounds.height >= ceil(MicAccessibility.MIN_TARGET_DP * density).toInt())
                }
            }
        }
    }

    /** A failure here means a child bound does not contain the centre of its ControlLayout placement. */
    @Test
    fun `each node bound contains the centre of its placement`() {
        for (s in sizes) {
            val cancel = ControlLayout.cancel(s)
            val send = ControlLayout.send(s)
            val nodes = MicAccessibility.nodes(TileShape.RECORDING, s, 2f, cancelLabel, sendLabel)
            assertTrue("android_overlay: the cancel node bound contains the cancel placement centre", nodes[0].bounds.contains(floor((cancel.left + cancel.right) / 2f).toInt(), floor((cancel.top + cancel.bottom) / 2f).toInt()))
            assertTrue("android_overlay: the send node bound contains the send placement centre", nodes[1].bounds.contains(floor((send.left + send.right) / 2f).toInt(), floor((send.top + send.bottom) / 2f).toInt()))
        }
    }

    /** A failure here means cancel sits to the right of send or the two bounds overlap. */
    @Test
    fun `cancel bound is left of send bound and they do not overlap`() {
        val s = 144
        val density = 2f
        val nodes = MicAccessibility.nodes(TileShape.RECORDING, s, density, cancelLabel, sendLabel)
        assertEquals("android_overlay: the cancel node is first", MicA11yAction.CANCEL, nodes[0].action)
        assertEquals("android_overlay: the send node is second", MicA11yAction.SEND, nodes[1].action)
        assertTrue("android_overlay: the cancel bound starts strictly left of the send bound", nodes[0].bounds.left < nodes[1].bounds.left)
        assertFalse("android_overlay: the cancel and send bounds do not overlap", overlaps(nodes[0].bounds, nodes[1].bounds))
    }

    /** A failure here means run(CANCEL) invokes onCancel in addition to onSend. */
    @Test
    fun `run with cancel invokes only onCancel`() {
        var cancels = 0
        var sends = 0
        MicAccessibility.run(MicA11yAction.CANCEL, { cancels++ }, { sends++ })
        assertEquals("android_overlay: run(CANCEL) calls onCancel once", 1, cancels)
        assertEquals("android_overlay: run(CANCEL) does not call onSend", 0, sends)
    }

    /** A failure here means run(SEND) invokes onSend in addition to onCancel. */
    @Test
    fun `run with send invokes only onSend`() {
        var cancels = 0
        var sends = 0
        MicAccessibility.run(MicA11yAction.SEND, { cancels++ }, { sends++ })
        assertEquals("android_overlay: run(SEND) calls onSend once", 1, sends)
        assertEquals("android_overlay: run(SEND) does not call onCancel", 0, cancels)
    }

    /** A failure here means null callbacks throw instead of being a no-op. */
    @Test
    fun `run with null callbacks does nothing`() {
        MicAccessibility.run(MicA11yAction.CANCEL, null, null)
        MicAccessibility.run(MicA11yAction.SEND, null, null)
    }


    /** A failure here means a point inside a child bound does not hit that child, or a point outside every bound hits one. */
    @Test
    fun `hit finds the child under a point and none elsewhere`() {
        val nodes = MicAccessibility.nodes(TileShape.RECORDING, 144, 2f, cancelLabel, sendLabel)
        val c = nodes[0].bounds
        val d = nodes[1].bounds
        assertEquals("android_overlay: a point in the cancel bound hits cancel", MicAccessibility.CANCEL_ID, MicAccessibility.hit(nodes, c.left, c.top))
        assertEquals("android_overlay: a point in the send bound hits send", MicAccessibility.SEND_ID, MicAccessibility.hit(nodes, d.right - 1, d.bottom - 1))
        assertEquals("android_overlay: a point past the far edge hits nothing", MicAccessibility.NO_ID, MicAccessibility.hit(nodes, d.right, d.bottom))
        assertEquals("android_overlay: no children, no hit", MicAccessibility.NO_ID, MicAccessibility.hit(emptyList(), c.left, c.top))
    }

    /** True when the two rectangles share any pixel; a false result means they are side by side with no common area. */
    private fun overlaps(a: TileRect, b: TileRect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
