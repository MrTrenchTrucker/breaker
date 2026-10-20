package dev.breaker.dictation.overlay

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The action a virtual accessibility node represents: cancel the recording or send it, matching
 * [MicAccessibility.CANCEL_ID] and [MicAccessibility.SEND_ID].
 */
internal enum class MicA11yAction { CANCEL, SEND }

/**
 * One virtual accessibility child of the recording mic: an [id], the words a screen reader reads for
 * it ([label]), which [action] tapping it runs and its [bounds] in window pixels.
 */
internal data class MicA11yNode(
    val id: Int,
    val label: String,
    val action: MicA11yAction,
    val bounds: TileRect,
)

/**
 * The pure model of the recording mic's two accessibility children (cancel on the left, send on the
 * right), with no platform names. [nodes] gives them their ids, labels, actions and bounds for a window of side [s] pixels at [density].
 */
internal object MicAccessibility {

    /** The id of the cancel (stop) accessibility child. */
    const val CANCEL_ID = 1

    /** The id of the send (check) accessibility child. */
    const val SEND_ID = 2

    /** The smallest bound, in dp, every child rectangle must reach on both axes at this density. */
    const val MIN_TARGET_DP = 48f

    /**
     * The two virtual children for a window of side [s] pixels in the language ([cancelLabel],
     * [sendLabel]) and device density. Outside RECORDING there are no accessibility children, so an
     * empty list; RECORDING returns exactly CANCEL then SEND.
     */
    fun nodes(shape: TileShape, s: Int, density: Float, cancelLabel: String, sendLabel: String): List<MicA11yNode> =
        if (shape != TileShape.RECORDING) {
            emptyList()
        } else {
            listOf(
                MicA11yNode(CANCEL_ID, cancelLabel, MicA11yAction.CANCEL, boundsOf(ControlLayout.cancel(s), density)),
                MicA11yNode(SEND_ID, sendLabel, MicA11yAction.SEND, boundsOf(ControlLayout.send(s), density)),
            )
        }

    /** The id meaning no virtual child (nothing hovered, nothing focused). */
    const val NO_ID = Int.MIN_VALUE

    /** The id of the child whose bounds hold the view-local point ([x], [y]), or [NO_ID]; the first child wins an overlap. */
    fun hit(nodes: List<MicA11yNode>, x: Int, y: Int): Int = nodes.firstOrNull { it.bounds.contains(x, y) }?.id ?: NO_ID

    /**
     * The action a child ran: CANCEL invokes [onCancel] and SEND invokes [onSend]; any other or null
     * callback is skipped (a no-op).
     */
    fun run(action: MicA11yAction, onCancel: (() -> Unit)?, onSend: (() -> Unit)?) {
        when (action) {
            MicA11yAction.CANCEL -> onCancel?.invoke()
            MicA11yAction.SEND -> onSend?.invoke()
        }
    }

    /**
     * The window-pixel bounds of [p], grown symmetrically around its own centre so that both width and
     * height are at least [MIN_TARGET_DP] scaled to this density, never shrinking a side already larger.
     * Returned as whole Int pixels: the near edge is rounded down (floor) and the far edge rounded up
     * (ceil), so every pixel between the original float edges is held and both axes reach the target.
     */
    private fun boundsOf(p: ControlPlacement, density: Float): TileRect {
        val cx = (p.left + p.right) / 2f
        val cy = (p.top + p.bottom) / 2f
        val w = maxOf((p.right - p.left), MIN_TARGET_DP * density)
        val h = maxOf((p.bottom - p.top), MIN_TARGET_DP * density)
        return TileRect(
            left = floor(cx - w / 2f).toInt(),
            top = floor(cy - h / 2f).toInt(),
            right = ceil(cx + w / 2f).toInt(),
            bottom = ceil(cy + h / 2f).toInt(),
        )
    }
}

/** The two things the mic remembers for a screen reader: the child under the finger and the child with accessibility focus. */
internal class MicA11yState {
    private var hovered = MicAccessibility.NO_ID

    /** The child with accessibility focus, or [MicAccessibility.NO_ID]. */
    var focused = MicAccessibility.NO_ID
        private set

    /** Forget both, as when the mic changes shape and its children go. */
    fun reset() {
        hovered = MicAccessibility.NO_ID
        focused = MicAccessibility.NO_ID
    }

    /** Move the hover to [now]; returns what to send in order: a child id and whether it is entered (else exited). */
    fun hoverTo(now: Int): List<Pair<Int, Boolean>> {
        val prev = hovered
        hovered = now
        return when {
            prev == now -> emptyList()
            prev == MicAccessibility.NO_ID -> listOf(now to true)
            now == MicAccessibility.NO_ID -> listOf(prev to false)
            else -> listOf(prev to false, now to true)
        }
    }

    /** Give [id] accessibility focus ([gains]) or take it away; only the focused child can lose it. */
    fun focus(id: Int, gains: Boolean) {
        if (gains) focused = id else if (focused == id) focused = MicAccessibility.NO_ID
    }
}
