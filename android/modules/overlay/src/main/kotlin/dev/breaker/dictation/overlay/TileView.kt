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
 * The floating tile as one Android view. It draws one [TileFace] and nothing else: a background with
 * small corners, the stand-in microphone glyph with a ring around it, and, by the face's shape, either
 * the level meter with the cancel and send buttons, or the app's notice text.
 *
 * T4: overlay spoofing. The view asks the system to drop touches that arrive while another window
 * covers it ([setFilterTouchesWhenObscured]), so a window laid over the tile cannot feed it taps.
 * The tile is tap-only: it holds no text input and never takes focus.
 *
 * Every colour comes from the face's [TileLook], every position from [TileLayout] and every glyph
 * shape from [TileGlyph], so this class holds no colour value of its own. It runs no timer and no
 * animation: it draws when [applyFace] is called, or when the system asks. The description the app
 * gave for the tile is passed on to accessibility services as the view's content description.
 *
 * Touches are forwarded to the [TouchSink] in screen coordinates (the raw position) and the view
 * decides nothing itself: telling a tap from a drag, and which button was hit, is the sink's job.
 *
 * Only the finger that went down is followed. Its pointer id is remembered on the down event, moves
 * are read from that pointer alone, and the gesture ends when that finger lifts (or on a cancel). A
 * second finger that lands, moves or lifts changes nothing, so the tile never jumps to another finger.
 *
 * Call it from the UI looper only.
 *
 * **What this class cannot prove.** Nothing here runs without a device, so none of this is verified:
 * how the glyph, the ring, the meter and the buttons look at a real density, how the notice text
 * wraps and is cut, what an accessibility service reads out, whether the system really drops covered
 * touches, how a drag feels, or any vendor difference in how overlay windows deliver touches. Only
 * the text of this file is checked on a plain JVM.
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
    private val cornerRadiusPx: Float = TileMetrics.CORNER_RADIUS_DP * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** Id of the pointer that owns the current gesture, or [NO_POINTER] when none does. */
    private var activePointerId: Int = NO_POINTER

    init {
        setFilterTouchesWhenObscured(true)
        contentDescription = face.description
    }

    /** Draw [face] from now on, and redraw now. A null description clears the content description. */
    fun applyFace(face: TileFace) {
        this.face = face
        contentDescription = face.description
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val face = this.face
        val look = face.look
        val s = if (face.shape == TileShape.COLLAPSED) height else width / 3
        if (s <= 0) return

        paint.style = Paint.Style.FILL
        paint.color = look.background
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadiusPx, cornerRadiusPx, paint)

        val mic = TileLayout.micCell(face.shape, s)
        drawGlyph(canvas, mic, look)
        drawRing(canvas, mic, look.ring)
        when (face.shape) {
            TileShape.COLLAPSED -> Unit
            TileShape.NOTICE -> drawNotice(canvas, s, face)
            TileShape.RECORDING -> {
                drawMeter(canvas, s, face)
                drawCancel(canvas, TileLayout.cancelCell(s), look.control)
                drawSend(canvas, TileLayout.sendCell(s), look.control)
            }
        }
    }

    /** The glyph's rectangles scaled into [cell], each in the colour of its role. */
    private fun drawGlyph(canvas: Canvas, cell: TileRect, look: TileLook) {
        paint.style = Paint.Style.FILL
        val w = cell.width.toFloat()
        val h = cell.height.toFloat()
        for (rect in TileGlyph.rects) {
            paint.color = when (rect.role) {
                GlyphRole.BODY -> look.glyph
                GlyphRole.GRILLE -> look.glyphOutline
                GlyphRole.OUTLINE -> look.glyphOutline
            }
            canvas.drawRect(
                cell.left + rect.left * w,
                cell.top + rect.top * h,
                cell.left + rect.right * w,
                cell.top + rect.bottom * h,
                paint,
            )
        }
    }

    /** The ring just inside the edge of [cell]. */
    private fun drawRing(canvas: Canvas, cell: TileRect, color: Int) {
        val stroke = TileMetrics.RING_DP * density
        val half = stroke / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = color
        canvas.drawRoundRect(
            cell.left + half,
            cell.top + half,
            cell.right - half,
            cell.bottom - half,
            cornerRadiusPx,
            cornerRadiusPx,
            paint,
        )
    }

    /** The meter: one box per segment, the first lit ones in the lit colour and the rest in the unlit colour. */
    private fun drawMeter(canvas: Canvas, s: Int, face: TileFace) {
        val gap = TileMetrics.SEGMENT_GAP_PX.toFloat()
        paint.style = Paint.Style.FILL
        for ((index, cell) in TileLayout.segmentRects(face.segments, TileLayout.meterRect(s)).withIndex()) {
            val left = cell.left + gap
            val top = cell.top + gap
            val right = cell.right - gap
            val bottom = cell.bottom - gap
            if (right <= left || bottom <= top) continue
            paint.color = if (index < face.litSegments) face.look.litSegment else face.look.unlitSegment
            canvas.drawRect(left, top, right, bottom, paint)
        }
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
        paint.strokeWidth = CONTROL_STROKE_DP * density
        paint.color = color
    }

    /** The app's notice across the strip: wrapped to the strip, at most two lines, the end cut with an ellipsis. */
    private fun drawNotice(canvas: Canvas, s: Int, face: TileFace) {
        val text = face.notice ?: return
        val area = TileLayout.noticeRect(s)
        val pad = area.height / NOTICE_PAD_DIVISOR
        val textWidth = area.width - 2 * pad
        if (textWidth <= 0) return
        textPaint.color = face.look.control
        textPaint.textSize = area.height * NOTICE_TEXT_FRACTION
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textWidth)
            .setMaxLines(NOTICE_MAX_LINES)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate((area.left + pad).toFloat(), area.top + (area.height - layout.height) / 2f)
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
