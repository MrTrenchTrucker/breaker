package dev.breaker.dictation.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import dev.breaker.shared.tokens.ThemeMode

/**
 * The app-screen mic: the tile's microphone for an app screen, with no floating
 * window, no drag, no close target and no notice strip (ADR-026).
 *
 * The app pushes a state ([setState]), a level ([setLevel]), a theme ([setTheme]) and a
 * description ([setDescription]). A tap on the microphone calls [onTap] (idle or failed),
 * [onBegin] (armed) or [onSend] (recording); the cancel button calls [onCancel]. Made with
 * [create]; call everything from the main (UI) looper.
 */
class ScreenMic private constructor(
    private val view: ScreenMicView,
) {

    /** The Android view to place on an app screen. */
    fun asView(): View = view

    /** Show [state]; the mic never changes it by itself. */
    fun setState(state: TileState) = view.setModelState(state)

    /** Show the sound [level] from 0.0 (silence) to 1.0 (full); only a recording mic shows it. */
    fun setLevel(level: Float) = view.setModelLevel(level)
    /** Set the theme the mic is drawn in. */
    fun setTheme(mode: ThemeMode) = view.setModelTheme(mode)
    /** Set the words that describe the mic to a screen reader; null for none. */
    fun setDescription(text: String?) = view.setModelDescription(text)

    companion object {
        /**
         * Make a mic for an app screen.
         *
         * @param context any context.
         * @param theme the theme the mic is first drawn in.
         * @param label the words a screen reader reads for the mic.
         * @param onTap what the app does when the microphone is tapped while idle or failed.
         * @param onBegin what the app does when the microphone is tapped while armed.
         * @param onCancel what the app does when the cancel button is tapped while recording.
         * @param onSend what the app does when the send button or the microphone is tapped while recording.
         * @param cancelLabel the words a screen reader reads for the cancel button (the app passes its own constant).
         * @param sendLabel the words a screen reader reads for the send button (the app passes its own constant).
         */
        fun create(
            context: Context,
            theme: ThemeMode,
            label: String,
            cancelLabel: String,
            sendLabel: String,
            onTap: () -> Unit,
            onBegin: (() -> Unit)? = null,
            onCancel: (() -> Unit)? = null,
            onSend: (() -> Unit)? = null,
        ): ScreenMic {
            val slopPx = ViewConfiguration.get(context).scaledTouchSlop
            val view = ScreenMicView(context, theme, label, cancelLabel, sendLabel, onTap, onBegin, onCancel, onSend, slopPx)
            return ScreenMic(view)
        }
    }
}

/**
 * The view behind [ScreenMic]: it draws the mic art with the tile's own drawing and
 * routes taps through the tile's routing, with no window, no drag, no close target
 * and no notice strip.
 */
internal class ScreenMicView(
    context: Context,
    initialTheme: ThemeMode,
    label: String,
    private val cancelLabel: String,
    private val sendLabel: String,
    internal val onTap: () -> Unit,
    internal val onBegin: (() -> Unit)?,
    internal val onCancel: (() -> Unit)?,
    internal val onSend: (() -> Unit)?,
    slopPx: Int,
) : View(context), ScreenMicProbe {

    internal val model = TileModel()
    private var theme: ThemeMode = initialTheme
    private val interpreter = TouchInterpreter(slopPx)
    internal val gesture = TileGesture()
    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val artDraw = TileArtDraw()

    /** Id of the pointer that owns the current gesture, or -1 when none does. */
    private var activePointerId: Int = -1

    /** Which virtual child a touch-exploring finger is over and which has accessibility focus. */
    private val a11y = MicA11yState()

    init {
        contentDescription = label
    }

    /** The app pushes [state]; see [TileModel.setState]. */
    fun setModelState(state: TileState) {
        val oldShape = model.shape
        model.setState(state)
        if (model.shape != oldShape) {
            interpreter.cancel()
            a11y.reset()
            requestLayout()
            sendA11y(AccessibilityNodeProvider.HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        invalidate()
    }

    /** The app pushes the sound [level]; see [TileModel.setLevel]. */
    fun setModelLevel(level: Float) {
        model.setLevel(level)
        invalidate()
    }

    /** The app pushes the theme; the mic is redrawn in the new colours. */
    fun setModelTheme(mode: ThemeMode) {
        theme = mode
        invalidate()
    }

    /** The app pushes the description for accessibility; null for none. */
    fun setModelDescription(text: String?) {
        model.setDescription(text)
        contentDescription = text
        sendA11y(AccessibilityNodeProvider.HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    /** Debug-probe handle on the mic: push [state] to the mic exactly the way the app pushes state (the same setModelState path, on the main thread); the push always succeeds, so this always returns true. */
    override fun probeSetState(state: TileState): Boolean = run { setModelState(state); true }

    /** Debug-probe handle on the mic's touch path; storing [log] resumes it, null stops it. */
    override fun probeTouchLog(log: ((String) -> Unit)?) = ScreenMicProbeKit.logSink(log)

    /** Report [type] for the host or a virtual child; a content change is a subtree change. */
    private fun sendA11y(virtualId: Int, type: Int) {
        val event = AccessibilityEvent.obtain(type)
        event.setSource(this, virtualId)
        event.packageName = context.packageName
        event.className = BUTTON_CLASS
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        parent?.requestSendAccessibilityEvent(this, event)
    }

    /** With touch exploration on, a finger over a child hovers that child (enter/exit events); elsewhere the host decides. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!manager.isTouchExplorationEnabled) return super.dispatchHoverEvent(event)
        val over = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) MicAccessibility.NO_ID
        else MicAccessibility.hit(a11yNodes(), event.x.toInt(), event.y.toInt())
        for ((id, enters) in a11y.hoverTo(over)) sendA11y(id, if (enters) AccessibilityEvent.TYPE_VIEW_HOVER_ENTER else AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        return over != MicAccessibility.NO_ID || super.dispatchHoverEvent(event)
    }

    /** The mic's virtual children now, empty when it is not recording. */
    private fun a11yNodes(): List<MicA11yNode> =
        MicAccessibility.nodes(model.shape, height, density, cancelLabel, sendLabel)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val suggestedHeight = MeasureSpec.getSize(heightMeasureSpec)
        val tileSize = if (suggestedHeight > 0) suggestedHeight else (TileMetrics.TILE_SIZE_DP * density).toInt()
        val size = TileLayout.windowSize(model.shape, tileSize)
        setMeasuredDimension(size.width, size.height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val face = model.face(theme)
        val look = face.look
        val s = height
        if (s <= 0) return
        val mic = TileLayout.micCell(face.shape, s)
        artDraw.drawGlow(canvas, mic, paint, look.ring, 1f, density)
        drawRing(canvas, mic, look.ring)
        artDraw.drawArt(canvas, mic, paint, face, 1f)
        if (face.shape == TileShape.RECORDING) {
            artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)
            artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.send, look)
        }
    }

    /** The outline band of the microphone: its silhouette stroked RING_DP wide in the ring colour. */
    private fun drawRing(canvas: Canvas, cell: TileRect, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = TileMetrics.RING_DP * density
        paint.color = color
        artDraw.strokeOutline(canvas, cell, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                activePointerId = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                interpreter.down(x, y)
                gesture.begin(x, y, model.shape)
                ScreenMicProbeKit.downLog(model.shape, model.state, x, y, height)
            }
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(activePointerId)
                val wasTap = if (index >= 0) {
                    interpreter.up(event.getX(index), event.getY(index)) == TouchEvent.Tap
                } else false
                activePointerId = -1
                if (wasTap) {
                    route()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                interpreter.cancel()
            }
        }
        return true
    }

    /** Run what a tap means for the state and for the part of the view the finger went down on. */
    private fun route() {
        ScreenMicProbeKit.route(this)
    }

    /** The accessibility provider backing this view: one host node plus, while recording, two clickable virtual children. */
    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = Provider(this)

    /** The accessibility provider for this view: one host node plus, while recording, two clickable virtual children. */
    private class Provider(private val view: ScreenMicView) : AccessibilityNodeProvider() {

        override fun createAccessibilityNodeInfo(virtualId: Int): AccessibilityNodeInfo? =
            if (virtualId == HOST_VIEW_ID) hostNode() else nodeFor(virtualId)?.let { childNode(it) }

        /** The host node is the view itself: its usual fields, the button class name and a click action. */
        private fun hostNode() = AccessibilityNodeInfo.obtain(view).also { info ->
            view.onInitializeAccessibilityNodeInfo(info)
            info.className = BUTTON_CLASS
            info.text = view.contentDescription
            info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
            for (node in view.a11yNodes()) info.addChild(view, node.id)
        }

        /** A child node: its label, its bounds on screen, a click and the focus action that fits its focus state. */
        private fun childNode(node: MicA11yNode): AccessibilityNodeInfo {
            val info = AccessibilityNodeInfo.obtain(view, node.id)
            info.setParent(view)
            info.className = BUTTON_CLASS
            info.text = node.label
            val onScreen = IntArray(2)
            view.getLocationOnScreen(onScreen)
            val b = node.bounds
            info.setBoundsInScreen(Rect(b.left + onScreen[0], b.top + onScreen[1], b.right + onScreen[0], b.bottom + onScreen[1]))
            info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
            info.isAccessibilityFocused = view.a11y.focused == node.id
            info.addAction(if (info.isAccessibilityFocused) AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            info.isClickable = true
            return info
        }

        /** The virtual child with [virtualId], or null. */
        private fun nodeFor(virtualId: Int): MicA11yNode? = view.a11yNodes().firstOrNull { it.id == virtualId }

        /** A click runs the child's action; focus actions are tracked and reported; the host keeps the platform defaults. */
        override fun performAction(virtualId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualId == HOST_VIEW_ID) {
                if (action != AccessibilityNodeInfo.ACTION_CLICK) return view.performAccessibilityAction(action, arguments)
                ScreenMicProbeKit.hostAction(view)
                return true
            }
            val node = nodeFor(virtualId) ?: return false
            when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> MicAccessibility.run(node.action, view.onCancel, view.onSend)
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    val gains = action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                    view.a11y.focus(virtualId, gains)
                    view.sendA11y(virtualId, if (gains) AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED else AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                }
                else -> return false
            }
            return true
        }
    }
}
/** The class name every accessibility node of the mic reports: a button. */
private val BUTTON_CLASS: String = Button::class.java.name
