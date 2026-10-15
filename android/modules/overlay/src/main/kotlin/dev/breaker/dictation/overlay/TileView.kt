package dev.breaker.dictation.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

/**
 * The floating tile as one Android view: it draws one [TileFace] (the microphone with its glowing
 * outline and its six grille slots, then the buttons or the notice text) and nothing else.
 *
 * T4: overlay spoofing. The view asks the system to drop touches that arrive while another window
 * covers it ([setFilterTouchesWhenObscured]). The tile is tap-only: no text input, no focus.
 * Every colour comes from the face's [TileLook], every position from [TileLayout] and every shape of
 * the picture from [TileArtDraw]. Touches go to the [TouchSink] in screen coordinates and the view decides
 * nothing itself; only the finger that went down is followed. Call it from the UI looper only.
 * **What this class cannot prove.** Nothing here runs without a device: how it looks at real
 * density, how the notice text wraps, what an accessibility service reads, how a drag feels,
 * whether the system really drops covered touches, or any vendor difference in how overlay windows deliver touches.
 */
internal class TileView(
    context: Context,
    private val sink: TouchSink,
    initialFace: TileFace,
) : View(context) {

    /** What the view draws now. */
    var face: TileFace = initialFace
        private set

    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val artDraw = TileArtDraw()

    /** Extra stroke width in pixels for the control strokes: the halo pass sets it, and it is 0 otherwise. */
    private var haloExtraPx: Float = 0f

    /** Id of the pointer that owns the current gesture, or [NO_POINTER] when none does. */
    private var activePointerId: Int = NO_POINTER

    /** Pulse inputs: the app's wish, attach, window visibility, screen state, and the ring's alpha now. */
    private var wanted = false
    private var attached = false
    private var windowVisible = true
    private var screenOn = true
    private var pulseAlpha = 1f
    private val pulse = ArmedPulse { alpha -> pulseAlpha = alpha; invalidate() }
    private val busyPulse = busyPulse { alpha -> if (busyWanted) pulseAlpha = alpha; invalidate() }
    private var busyWanted = false
    init {
        setFilterTouchesWhenObscured(true)
        contentDescription = face.description
    }

    /** Draw [face] from now on, and redraw now. A null description clears the content description. */
    fun applyFace(face: TileFace) {
        this.face = face
        contentDescription = face.description
        busyWanted = face.state == TileState.MIC_BUSY || face.state == TileState.SENDING
        refreshPulse()
        invalidate()
    }

    /** Ask for the armed ring's pulse while [on] is true; it runs only while the other conditions hold. */
    fun setPulse(on: Boolean) {
        wanted = on
        refreshPulse()
    }

    /** Ask for the busy ring's slow pulse while [on] is true; same run rules as the armed pulse. */
    fun setBusyPulse(on: Boolean) {
        busyWanted = on
        refreshPulse()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        refreshPulse()
    }

    override fun onDetachedFromWindow() {
        attached = false
        refreshPulse()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == View.VISIBLE
        refreshPulse()
    }

    override fun onScreenStateChanged(screenState: Int) {
        super.onScreenStateChanged(screenState)
        screenOn = screenState != View.SCREEN_STATE_OFF
        refreshPulse()
    }

    private fun refreshPulse() {
        if (pulseShouldRun(wanted, attached, windowVisible, screenOn, ArmedPulse.animationsOn())) pulse.start() else pulse.stop()
        if (pulseShouldRun(busyWanted, attached, windowVisible, screenOn, ArmedPulse.animationsOn())) busyPulse.start() else busyPulse.stop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val face = this.face
        val look = face.look
        val s = if (face.shape == TileShape.COLLAPSED) height else width / 3
        if (s <= 0) return

        val mic = TileLayout.micCell(face.shape, s)
        artDraw.drawGlow(canvas, mic, paint, look.ring, pulseAlpha, density)
        drawRing(canvas, mic, look.ring)
        artDraw.drawArt(canvas, mic, paint, face, pulseAlpha)
        when (face.shape) {
            TileShape.COLLAPSED -> Unit
            TileShape.NOTICE -> drawNotice(canvas, s, face)
            TileShape.RECORDING -> {
                haloExtraPx = 2 * ArtMetrics.HALO_DP * density
                drawCancel(canvas, TileLayout.cancelCell(s), look.background)
                drawSend(canvas, TileLayout.sendCell(s), look.background)
                haloExtraPx = 0f
                drawCancel(canvas, TileLayout.cancelCell(s), look.control)
                drawSend(canvas, TileLayout.sendCell(s), look.control)
            }
        }
    }

    /** The outline band of the microphone: its silhouette stroked RING_DP wide in the ring colour at the pulse alpha. */
    private fun drawRing(canvas: Canvas, cell: TileRect, color: Int) {
        val stroke = TileMetrics.RING_DP * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = ringDrawColor(color, pulseAlpha)
        artDraw.strokeOutline(canvas, cell, paint)
    }

    /** The cancel button: a cross made of two strokes, inset in [cell]. */
    private fun drawCancel(canvas: Canvas, cell: TileRect, color: Int) {
        strokeControl(color)
        val pad = cell.width * CONTROL_INSET
        val left = cell.left + pad
        val top = cell.top + pad
        val right = cell.right - pad
        val bottom = cell.bottom - pad
        canvas.drawLine(left, top, right, bottom, paint)
        canvas.drawLine(right, top, left, bottom, paint)
    }

    /** The send button: a check mark made of two strokes, inside [cell]. */
    private fun drawSend(canvas: Canvas, cell: TileRect, color: Int) {
        strokeControl(color)
        val w = cell.width.toFloat()
        val h = cell.height.toFloat()
        val footX = cell.left + w * CHECK_FOOT_X
        val footY = cell.top + h * CHECK_FOOT_Y
        canvas.drawLine(cell.left + w * CHECK_START_X, cell.top + h * CHECK_START_Y, footX, footY, paint)
        canvas.drawLine(footX, footY, cell.left + w * CHECK_END_X, cell.top + h * CHECK_END_Y, paint)
    }

    private fun strokeControl(color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = CONTROL_STROKE_DP * density + haloExtraPx
        paint.color = color
    }

    /** The app's notice across the strip: wrapped to the strip, at most two lines, the end cut with an ellipsis. */
    private fun drawNotice(canvas: Canvas, s: Int, face: TileFace) {
        val text = face.notice ?: return
        val area = TileLayout.noticeRect(s)
        val pad = area.height / NOTICE_PAD_DIVISOR
        val textWidth = area.width - 2 * pad
        if (textWidth <= 0) return
        textPaint.textSize = area.height * NOTICE_TEXT_FRACTION
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textWidth)
            .setMaxLines(NOTICE_MAX_LINES)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate((area.left + pad).toFloat(), area.top + (area.height - layout.height) / 2f)
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = 2 * ArtMetrics.HALO_DP * density
        textPaint.color = face.look.background
        layout.draw(canvas)
        textPaint.style = Paint.Style.FILL
        textPaint.color = face.look.control
        layout.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                activePointerId = event.getPointerId(index)
                sink.onTouchDown(event.getRawX(index), event.getRawY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0) {
                    sink.onTouchMove(event.getRawX(index), event.getRawY(index))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == activePointerId) {
                    activePointerId = NO_POINTER
                    sink.onTouchUp(event.getRawX(index), event.getRawY(index))
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(activePointerId)
                activePointerId = NO_POINTER
                if (index >= 0) {
                    sink.onTouchUp(event.getRawX(index), event.getRawY(index))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointerId = NO_POINTER
                sink.onTouchCancel()
            }
        }
        return true
    }

    private companion object {
        const val NO_POINTER = -1

        /** Thickness of the strokes of the cancel and send buttons. */
        const val CONTROL_STROKE_DP = 3

        /** Space kept free on each side of the cross, as a fraction of the button's width. */
        const val CONTROL_INSET = 0.28f

        /** The check mark's three points as fractions of the button's width and height. */
        const val CHECK_START_X = 0.24f
        const val CHECK_START_Y = 0.54f
        const val CHECK_FOOT_X = 0.42f
        const val CHECK_FOOT_Y = 0.72f
        const val CHECK_END_X = 0.76f
        const val CHECK_END_Y = 0.30f

        /** The notice text's size as a fraction of the strip's height, and its side padding as a part of it. */
        const val NOTICE_TEXT_FRACTION = 0.36f
        const val NOTICE_PAD_DIVISOR = 4
        const val NOTICE_MAX_LINES = 2
    }
}
