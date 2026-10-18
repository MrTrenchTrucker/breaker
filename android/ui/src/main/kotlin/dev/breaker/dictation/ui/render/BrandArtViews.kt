package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import dev.breaker.dictation.ui.render.controls.half
import dev.breaker.dictation.ui.screen.home.HomeGeometry
import dev.breaker.dictation.ui.screen.home.HomeNodeIds
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme
import dev.breaker.shared.tokens.ThemeMode

/**
 * The brand art the host draws: the top-bar logo, the big mic art, the bottom
 * stripes and the card's lip. Each is drawn from the brand VectorDrawables and
 * the HomeGeometry numbers, with colours from the theme; no colour and no
 * distance is written down here.
 */

/** The top-bar logo: the brand vector, sized to the bar's height. */
internal fun topBarLogo(context: Context, theme: Theme): ImageView {
    val dark = theme.mode == ThemeMode.DARK
    val name = if (dark) "breaker_logo_dark" else "breaker_logo_light"
    val id = context.resources.getIdentifier(name, "drawable", context.packageName)
    return ImageView(context).apply {
        setImageResource(id)
        // Shares the top bar's width with the menu icon, so the logo sits in the middle; its height is the logo's.
        layoutParams = LinearLayout.LayoutParams(
            0,
            (HomeGeometry.LOGO_H_DP * context.resources.displayMetrics.density).toInt(),
            1f,
        )
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

/** The drawer's logo header: the brand logo at the header height, its width from its own shape. */
internal fun drawerLogo(context: Context, theme: Theme): ImageView {
    val dark = theme.mode == ThemeMode.DARK
    val name = if (dark) "breaker_logo_dark" else "breaker_logo_light"
    val id = context.resources.getIdentifier(name, "drawable", context.packageName)
    return ImageView(context).apply {
        setImageResource(id)
        adjustViewBounds = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            (HomeGeometry.DRAWER_LOGO_H_DP * context.resources.displayMetrics.density).toInt(),
        )
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

/** The big mic art: the brand vector at MIC_SCALE, with a glow on dark. */
internal class MicArtDrawable(
    private val ctx: Context,
    private val theme: Theme,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val micId = ctx.resources.getIdentifier("breaker_mic_tile", "drawable", ctx.packageName)
    private val mic = HomeGeometry.bigMic()

    override fun getIntrinsicHeight(): Int =
        (ctx.resources.getDrawable(micId, ctx.theme).intrinsicHeight * mic.scale).toInt()

    override fun draw(canvas: Canvas) {
        val drawable = ctx.resources.getDrawable(micId, ctx.theme)
        val width = (drawable.intrinsicWidth * mic.scale).toInt()
        val height = (drawable.intrinsicHeight * mic.scale).toInt()
        val area = bounds
        canvas.save()
        canvas.translate(
            (area.left + ((area.width() - width) shr 1)).toFloat(),
            (area.top + ((area.height() - height) shr 1)).toFloat(),
        )
        if (theme.mode == ThemeMode.DARK) {
            val glowRadius = half(width.toFloat())
            val gradient = RadialGradient(
                half(width.toFloat()), half(height.toFloat()), glowRadius,
                theme.color(PaletteSlot.PRIMARY).argb, 0,
                Shader.TileMode.CLAMP,
            )
            paint.shader = gradient
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        canvas.restore()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }
}

/** The bottom stripes: one parallelogram per HomeGeometry.stripes() entry. */
internal class StripesView(context: Context, private val theme: Theme) : View(context) {
    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val stripes = HomeGeometry.stripes()
        val last = stripes.last()
        val height = (last.y + last.height - stripes.first().y).toInt()
        setMeasuredDimension(
            resolveSize(ViewGroup.LayoutParams.MATCH_PARENT, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(density, density)
        HomeGeometry.stripes().forEachIndexed { index, stripe ->
            paint.color = theme.color(if (index % (1 + 1) == 0) PaletteSlot.ACCENT else PaletteSlot.PRIMARY).argb
            val path = android.graphics.Path().apply {
                moveTo(stripe.x + stripe.slant, stripe.y)
                lineTo(stripe.x + stripe.slant + stripe.width, stripe.y)
                lineTo(stripe.x + stripe.width, stripe.y + stripe.height)
                lineTo(stripe.x, stripe.y + stripe.height)
                close()
            }
            canvas.drawPath(path, paint)
        }
        canvas.restore()
    }
}

/** The card's lip: the accent bar down the left edge. */
internal class CardLipView(context: Context, private val theme: Theme) : View(context) {
    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(density, density)
        val bar = HomeGeometry.statusCard().accentBar
        paint.color = theme.color(PaletteSlot.ACCENT).argb
        canvas.drawRect(bar.x, bar.y, bar.x + bar.width, bar.y + bar.height, paint)
        canvas.restore()
    }
}

/**
 * Applies the brand art to the rendered view tree: the top-bar logo, the mic
 * art, the stripes and the card's lip.
 */
internal fun applyBrandArt(root: View, context: Context, theme: Theme) {
    findTagged(root, HomeNodeIds.TOP_BAR_WORD)?.let { word ->
        val parent = word.parent as? ViewGroup
        if (parent != null) {
            val index = parent.indexOfChild(word)
            val logo = topBarLogo(context, theme)
            parent.removeViewAt(index)
            parent.addView(logo, index)
        }
    }

    findTagged(root, HomeNodeIds.DRAWER_WORD)?.let { word ->
        val parent = word.parent as? ViewGroup
        if (parent != null) {
            val index = parent.indexOfChild(word)
            parent.removeViewAt(index)
            parent.addView(drawerLogo(context, theme), index)
        }
    }

    findTagged(root, HomeNodeIds.MIC)?.let { mic ->
        val art = MicArtDrawable(context, theme)
        mic.background = art
        mic.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, art.intrinsicHeight)
    }

    val stripeTags = listOf(
        "${HomeNodeIds.STRIPES}.1",
        "${HomeNodeIds.STRIPES}.2",
        "${HomeNodeIds.STRIPES}.3",
        "${HomeNodeIds.STRIPES}.4",
    )
    val stripeBoxes = stripeTags.mapNotNull { findTagged(root, it) }
    if (stripeBoxes.isNotEmpty()) {
        val first = stripeBoxes.first()
        val parent = first.parent as? ViewGroup
        if (parent != null) {
            val index = parent.indexOfChild(first)
            stripeBoxes.forEach { parent.removeView(it) }
            val stripes = StripesView(context, theme)
            stripes.layoutParams = first.layoutParams
            parent.addView(stripes, index)
        }
    }

    findTagged(root, "${HomeNodeIds.CARD}.lip")?.let { lip ->
        val parent = lip.parent as? ViewGroup
        if (parent != null) {
            val index = parent.indexOfChild(lip)
            val cardLip = CardLipView(context, theme)
            cardLip.layoutParams = lip.layoutParams
            parent.removeViewAt(index)
            parent.addView(cardLip, index)
        }
    }
}
