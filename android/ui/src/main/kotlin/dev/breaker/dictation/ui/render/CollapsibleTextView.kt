package dev.breaker.dictation.ui.render

import android.content.Context
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextUtils
import android.view.View
import android.view.View.MeasureSpec
import android.widget.TextView
import dev.breaker.dictation.ui.screen.history.Middle
import dev.breaker.dictation.ui.screen.history.collapse

/**
 * A text view that folds a long text to the collapse rule's line count, unless it is opened.
 *
 * Each measure counts the lines the whole text takes at the width the view is given. The count is
 * a StaticLayout built with this view's own paint, line spacing and break settings, and it goes to
 * [collapse]. The view applies the answer: a line limit and an end ellipsis when folded, neither
 * otherwise. The decision is reported through [onMiddle], so the row can show or hide its toggle.
 * Nothing is kept between measures.
 *
 * The layout copies the TextView's break settings, and the measurement is checked on a device only.
 * The text direction follows this view's layout direction: first-strong right to left when the view
 * is laid out right to left, and first-strong left to right otherwise.
 */
internal class CollapsibleTextView(
    context: Context,
    private val opened: Boolean,
) : TextView(context) {

    /** Receives the middle decision after each measure; the row shows its toggle while it names one. */
    var onMiddle: ((Middle) -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = maxOf(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight, 1)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val direction = if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR
        val drawn = StaticLayout.Builder.obtain(text, 0, text.length, paint, available)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(includeFontPadding)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setBreakStrategy(breakStrategy)
            .setHyphenationFrequency(hyphenationFrequency)
            .setTextDirection(direction)
            .build()
            .lineCount
        val result = collapse(drawn, opened)
        val limit = result.maxLines ?: Int.MAX_VALUE
        if (maxLines != limit) maxLines = limit
        val wanted = if (result.maxLines == null) null else TextUtils.TruncateAt.END
        if (ellipsize != wanted) ellipsize = wanted
        onMiddle?.invoke(result.middle)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
