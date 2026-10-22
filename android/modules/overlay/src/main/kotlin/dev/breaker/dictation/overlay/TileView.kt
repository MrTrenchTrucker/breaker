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
        val s = if (face.shape == TileShape.NOTICE) width / 3 else height
        if (s <= 0) return

        val mic = TileLayout.micCell(face.shape, s)
        artDraw.drawGlow(canvas, mic, paint, look.ring, pulseAlpha, density, GlowSpec.TILE, look.ring)
        drawRing(canvas, mic, look.ring)
        artDraw.drawArt(canvas, mic, paint, face, pulseAlpha)
        when (face.shape) {
            TileShape.COLLAPSED -> Unit
            TileShape.NOTICE -> drawNotice(canvas, s, face)
            TileShape.RECORDING -> {
                artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)
                artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.send, look)
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

        /** The notice text's size as a fraction of the strip's height, and its side padding as a part of it. */
        const val NOTICE_TEXT_FRACTION = 0.36f
        const val NOTICE_PAD_DIVISOR = 4
        const val NOTICE_MAX_LINES = 2
    }
}
