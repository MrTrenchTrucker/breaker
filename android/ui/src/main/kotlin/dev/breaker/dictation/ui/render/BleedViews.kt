package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import dev.breaker.dictation.ui.screen.home.HomeGeometry
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme
import dev.breaker.shared.tokens.ThemeMode

/*
 * Full-width layers inside a padded host.
 *
 * A host pads its content, and the rule and the stripes reach past that padding to both
 * edges of the screen. Each view measures to the full width and moves left by the host's
 * left padding. The stripes also move down by the host's bottom padding, so they reach the
 * bottom edge of the screen. Each view unclips the boxes between it and the scroll view, and
 * the host calls openForBleed so that its own padding does not clip them.
 */

/** A full-width line in one colour, [thickness] pixels tall. */
internal class RuleView(context: Context, colour: Int, private val thickness: Int) : View(context) {
    init {
        setBackgroundColor(colour)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        unclipAncestors()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val host = enclosingScroll()
        val parentWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val width = if (host == null) parentWidth else bleedWidth(parentWidth, host.paddingLeft, host.paddingRight)
        setMeasuredDimension(width, resolveSize(thickness, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val host = enclosingScroll()
        translationX = if (host == null) 0f else bleedShift(host.paddingLeft).toFloat()
    }
}

/**
 * The brand stripes at the foot of the screen. The art is drawn up to the view's bottom edge, and the
 * view is moved down by the host's bottom padding, so the art ends at the edge of the screen.
 */
internal class BottomStripesView(context: Context, private val theme: Theme, private val plain: Boolean) : View(context) {
    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        unclipAncestors()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val host = enclosingScroll()
        val parentWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val width = if (host == null) parentWidth else bleedWidth(parentWidth, host.paddingLeft, host.paddingRight)
        setMeasuredDimension(width, resolveSize(artHeightPx(), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val host = enclosingScroll()
        translationX = if (host == null) 0f else bleedShift(host.paddingLeft).toFloat()
        translationY = if (host == null) 0f else host.paddingBottom.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stripes = if (plain) HomeGeometry.plainStripes() else HomeGeometry.stripes()
        val top = stripes.first().y
        val lift = height / density - (stripes.last().y + stripes.last().height - top)
        val primary = theme.color(PaletteSlot.PRIMARY).argb
        val soft = softStripeColor(primary, theme.mode == ThemeMode.DARK)
        canvas.save()
        canvas.scale(density, density)
        stripes.forEachIndexed { index, stripe ->
            paint.color = if (plain) theme.color(plainStripeSlot(index)).argb else if (isPrimaryStripe(index)) primary else soft
            val y = stripe.y - top + lift
            val path = Path().apply {
                moveTo(stripe.x + stripe.slant, y)
                lineTo(stripe.x + stripe.slant + stripe.width, y)
                lineTo(stripe.x + stripe.width, y + stripe.height)
                lineTo(stripe.x, y + stripe.height)
                close()
            }
            canvas.drawPath(path, paint)
        }
        canvas.restore()
    }

    /** The height of the art in pixels, from the top of its first stripe to the bottom of its last. */
    private fun artHeightPx(): Int {
        val stripes = if (plain) HomeGeometry.plainStripes() else HomeGeometry.stripes()
        return ((stripes.last().y + stripes.last().height - stripes.first().y) * density).toInt()
    }
}

/**
 * Lets the content of this scroll host reach past its padding: the padding no longer clips what is
 * drawn, the content fills the viewport, and the column inside does not clip its children.
 */
internal fun ScrollView.openForBleed() {
    clipToPadding = false
    isFillViewport = true
    val column = if (childCount > 0) getChildAt(0) as? ViewGroup else null
    column?.clipChildren = false
}

/**
 * The scroll view this view bleeds through, or null when there is none: the nearest one with horizontal
 * padding, else the outermost one (see [bleedScrollIndex]).
 */
private fun View.enclosingScroll(): ScrollView? {
    val scrolls = scrollsAbove()
    val index = bleedScrollIndex(scrolls.map { it.paddingLeft + it.paddingRight }) ?: return null
    return scrolls[index]
}

/** Every scroll view above this view, nearest first. */
private fun View.scrollsAbove(): List<ScrollView> {
    val found = mutableListOf<ScrollView>()
    var current = parent as? View
    while (current != null) {
        if (current is ScrollView) found.add(current)
        current = current.parent as? View
    }
    return found
}

/**
 * Stops every view between this one and the scroll view it bleeds through from clipping its children, so a
 * layer nested in a box still draws past the box's edge. When that scroll view is not the first one above
 * this view, the scroll view also opens its padding, so the layer can draw into it. Nothing changes when
 * there is no scroll view above this one.
 */
private fun View.unclipAncestors() {
    val scrolls = scrollsAbove()
    val index = bleedScrollIndex(scrolls.map { it.paddingLeft + it.paddingRight }) ?: return
    val target = scrolls[index]
    val chain = mutableListOf<ViewGroup>()
    var current = parent as? ViewGroup
    while (current != null && current !== target) {
        chain.add(current)
        current = current.parent as? ViewGroup
    }
    if (current === target) chain.forEach { it.clipChildren = false }
    if (index > 0) target.clipToPadding = false
}
