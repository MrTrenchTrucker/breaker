package dev.breaker.dictation.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * The floating tile as one Android view: a rectangle with a small corner
 * radius and the stand-in microphone glyph drawn on top of it.
 *
 * T4: overlay spoofing. The view asks the system to drop touches that arrive
 * while another window covers it ([setFilterTouchesWhenObscured]), so a window
 * laid over the tile cannot feed it taps. The tile is tap-only: it holds no
 * text input and never takes focus.
 *
 * Every colour comes from the [TileColors] it is given, and every shape comes
 * from [TileGlyph], so this class holds no colour value of its own. Touches are
 * forwarded to the [TouchSink] in screen coordinates (the raw position) and
 * the tile decides nothing itself: telling a tap from a drag is the sink's job.
 *
 * Only the finger that went down is followed. Its pointer id is remembered on
 * the down event, moves are read from that pointer alone, and the gesture ends
 * when that finger lifts (or on a cancel). A second finger that lands, moves or
 * lifts changes nothing, so the tile never jumps to another finger.
 *
 * Call it from the UI looper only.
 *
 * **What this class cannot prove.** Nothing here runs without a device, so none
 * of this is verified: how the glyph looks at a real density, whether the
 * system really drops covered touches, how a drag feels, or any vendor
 * difference in how overlay windows deliver touches. Only the text of this file
 * is checked on a plain JVM.
 */
internal class TileView(
    context: Context,
    private val sink: TouchSink,
    colors: TileColors,
) : View(context) {

    private var currentColors: TileColors = colors
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cornerRadiusPx: Float =
        TileMetrics.CORNER_RADIUS_DP * context.resources.displayMetrics.density

    /** Id of the pointer that owns the current gesture, or [NO_POINTER] when none does. */
    private var activePointerId: Int = NO_POINTER

    init {
        setFilterTouchesWhenObscured(true)
    }

    /** Use [colors] from the next draw on, and redraw now. */
    fun applyColors(colors: TileColors) {
        currentColors = colors
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        paint.style = Paint.Style.FILL
        paint.color = currentColors.background
        canvas.drawRoundRect(0f, 0f, w, h, cornerRadiusPx, cornerRadiusPx, paint)

        for (rect in TileGlyph.rects) {
            paint.color = when (rect.role) {
                GlyphRole.BODY -> currentColors.glyph
                GlyphRole.GRILLE -> currentColors.outline
                GlyphRole.OUTLINE -> currentColors.outline
            }
            canvas.drawRect(rect.left * w, rect.top * h, rect.right * w, rect.bottom * h, paint)
        }
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
    }
}
