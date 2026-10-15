package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.breaker.dictation.ui.controls.ControlPart
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.controls.ControlsPalette
import dev.breaker.dictation.ui.controls.drawnLabel
import dev.breaker.dictation.ui.render.controls.Button3dView
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.SegmentCell
import dev.breaker.dictation.ui.screen.SegmentedRow
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/**
 * A row of choices in an outlined track. The chosen cell is a raised primary face; the others are
 * flat words in capitals, and a hairline divides two neighbours that are both unchosen. Each cell
 * reports its intent when it is tapped.
 */
internal class SegmentedRowView(
    context: Context,
    row: SegmentedRow,
    private val theme: Theme,
    onIntent: (ScreenIntent) -> Unit,
) : LinearLayout(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        orientation = LinearLayout.HORIZONTAL
        // a group draws nothing of its own unless asked, and the track is drawn in onDraw
        setWillNotDraw(false)
        row.cells.forEachIndexed { index, cell ->
            if (index > 0 && !row.cells[index - 1].selected && !cell.selected) addView(dividerView())
            addView(cellView(cell, onIntent), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** A chosen cell is a raised primary face; any other cell is a flat word that reports its intent. */
    private fun cellView(cell: SegmentCell, onIntent: (ScreenIntent) -> Unit): View {
        val view: View = if (cell.selected) {
            Button3dView(context, cell.text, theme)
        } else {
            TextView(context).apply {
                text = drawnLabel(cell.text)
                setTextColor(theme.color(PaletteSlot.TEXT).argb)
                typeface = Typeface.DEFAULT
                gravity = Gravity.CENTER
                minHeight = touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)
            }
        }
        view.setOnClickListener { onIntent(cell.intent) }
        return view
    }

    /** A one pixel line in the trim colour, between two cells that are both unchosen. */
    private fun dividerView(): View = View(context).apply {
        setBackgroundColor(theme.color(PaletteSlot.TRIM).argb)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(density, density)
        val w = width / density
        val h = height / density
        if (w > 0f && h > 0f) {
            val corner = ControlsFactors.CORNER_DP
            paint.style = Paint.Style.FILL
            paint.color = ControlsPalette.partColor(ControlPart.FACE_SURFACE, theme)
            canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ControlsGeometry.OUTLINE_DP
            paint.color = ControlsPalette.partColor(ControlPart.OUTLINE, theme)
            canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)
        }
        canvas.restore()
    }
}
