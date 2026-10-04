package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TextAlign
import dev.breaker.dictation.ui.screen.TrimStripe
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/**
 * Turns a described screen into Android views.
 *
 * Every colour comes from the [Theme] through [argbOf]; there is no colour
 * value in this file. Every size comes from the theme's metrics: an action is
 * at least the touch-target size in both directions and has the token corner
 * radius. Rows carry no padding, margin or spacing of their own, so they touch,
 * and text is drawn at the platform's default size for its role.
 *
 * The result is a plain vertical layout. It does not scroll and does not
 * handle window insets: the view that hosts it does both.
 */
internal class ScreenRenderer(private val context: Context) {
    /**
     * Builds the views for [screen] in [theme].
     *
     * An action that is enabled reports its intent to [onIntent] when clicked;
     * an action that is disabled reports nothing.
     */
    fun render(screen: Screen, theme: Theme, onIntent: (ScreenIntent) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.argbOf(PaletteSlot.BACKGROUND))
            screen.nodes.forEach { addView(viewOf(it, theme, onIntent)) }
        }

    /** The view for [node]. It carries its own layout parameters, set once. */
    private fun viewOf(node: Node, theme: Theme, onIntent: (ScreenIntent) -> Unit): View =
        when (node) {
            is Box -> boxOf(node, theme, onIntent)
            is Label -> labelOf(node, theme)
            is Action -> actionOf(node, theme, onIntent)
            is TrimStripe -> stripeOf(theme)
        }

    /** A vertical layout in the box's background slot, holding its children in order. */
    private fun boxOf(node: Box, theme: Theme, onIntent: (ScreenIntent) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.argbOf(node.background))
            layoutParams = rowParams()
            node.children.forEach { addView(viewOf(it, theme, onIntent)) }
        }

    /**
     * A text view in the label's slot, family and alignment.
     *
     * The text appearance is set first because an appearance carries its own
     * colour, which the slot colour then replaces.
     */
    private fun labelOf(node: Label, theme: Theme): View =
        TextView(context).apply {
            setTextAppearance(appearanceOf(node.role))
            typeface = typefaceOf(node.role, theme)
            setTextColor(theme.argbOf(node.color))
            textAlignment = alignmentOf(node.align)
            text = node.text
            layoutParams = rowParams()
        }

    /**
     * A button on the surface fill with the token corner radius.
     *
     * The size floor is the touch-target token in both directions. The fill is
     * the surface slot for every emphasis because that is the fill the text
     * colours of [actionTextSlot] are guaranteed to be readable on.
     */
    private fun actionOf(node: Action, theme: Theme, onIntent: (ScreenIntent) -> Unit): View =
        Button(context).apply {
            val density = context.resources.displayMetrics.density
            setTextAppearance(appearanceOf(TypeRole.BODY))
            isAllCaps = false
            typeface = typefaceOf(TypeRole.DISPLAY, theme)
            setTextColor(theme.argbOf(actionTextSlot(node.emphasis, node.enabled)))
            background = GradientDrawable().apply {
                setColor(theme.argbOf(PaletteSlot.SURFACE))
                cornerRadius = theme.metrics.cornerRadiusDp * density
            }
            minHeight = touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), density)
            minWidth = touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), density)
            gravity = Gravity.CENTER
            text = node.text
            isEnabled = node.enabled
            if (node.enabled) setOnClickListener { onIntent(node.intent) }
            layoutParams = rowParams()
        }

    /**
     * A one pixel line in the trim colour.
     *
     * The tokens have no stripe thickness, so the line is the platform's
     * hairline: one pixel. A screen reader skips it.
     */
    private fun stripeOf(theme: Theme): View =
        View(context).apply {
            setBackgroundColor(theme.argbOf(PaletteSlot.TRIM))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }

    /** Full width, as tall as its content. */
    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** The platform text appearance for a role: large for display text, medium otherwise. */
    private fun appearanceOf(role: TypeRole): Int = when (role) {
        TypeRole.DISPLAY -> android.R.style.TextAppearance_DeviceDefault_Large
        TypeRole.BODY -> android.R.style.TextAppearance_DeviceDefault_Medium
        TypeRole.MONO -> android.R.style.TextAppearance_DeviceDefault_Medium
    }

    /**
     * The typeface for a role, looked up by the family name the tokens give.
     *
     * No font file is bundled. The system resolves the name; a name it does
     * not know gives the platform default typeface.
     */
    private fun typefaceOf(role: TypeRole, theme: Theme): Typeface {
        val family = when (role) {
            TypeRole.DISPLAY -> theme.type.displayFamily
            TypeRole.BODY -> theme.type.bodyFamily
            TypeRole.MONO -> theme.type.monoFamily
        }
        return Typeface.create(family, Typeface.NORMAL)
    }

    /** The view text alignment that matches a node alignment. */
    private fun alignmentOf(align: TextAlign): Int = when (align) {
        TextAlign.START -> View.TEXT_ALIGNMENT_VIEW_START
        TextAlign.CENTER -> View.TEXT_ALIGNMENT_CENTER
        TextAlign.END -> View.TEXT_ALIGNMENT_VIEW_END
    }

    /** The colour of [slot] in this theme as the packed ARGB int views take. The only place a token colour becomes an Int. */
    private fun Theme.argbOf(slot: PaletteSlot): Int = color(slot).argb
}
