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
import dev.breaker.dictation.ui.render.controls.Button3dView
import dev.breaker.dictation.ui.render.controls.DeleteButtonView
import dev.breaker.dictation.ui.render.controls.NeutralButtonView
import dev.breaker.dictation.ui.render.controls.OutlinedButtonView
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.CollapsibleText
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.SegmentedRow
import dev.breaker.dictation.ui.screen.SwitchRow
import dev.breaker.dictation.ui.screen.TagPill
import dev.breaker.dictation.ui.screen.TextAlign
import dev.breaker.dictation.ui.screen.TrimStripe
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.screen.history.Middle
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/** The suffix of a collapsible text's id; its toggle id replaces it. */
private const val TEXT_SUFFIX = ".text"

/** The suffix that names a row's toggle, from the id of its collapsible text. */
private const val TOGGLE_SUFFIX = ".toggle"

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

    /** The view for [node]. It carries its own layout parameters and its id as a tag, set once. */
    private fun viewOf(node: Node, theme: Theme, onIntent: (ScreenIntent) -> Unit): View =
        when (node) {
            is Box -> boxOf(node, theme, onIntent)
            is Label -> labelOf(node, theme)
            is Action -> {
                val kit = node.kit
                if (kit == null) actionOf(node, theme, onIntent) else kitActionOf(kit, node, theme, onIntent)
            }
            is TrimStripe -> stripeOf(theme)
            is TagPill -> TagPillView(context, node.text, node.color, theme).apply { layoutParams = wrapParams() }
            is CollapsibleText -> collapsibleOf(node, theme)
            is SegmentedRow -> SegmentedRowView(context, node, theme, onIntent).apply { layoutParams = rowParams() }
            is SwitchRow -> SwitchRowView(context, node, theme, onIntent).apply { layoutParams = rowParams() }
            is ValueField -> valueFieldView(context, node, theme).apply { layoutParams = rowParams() }
        }.apply { tag = node.id }

    /**
     * A layout in the box's background slot, holding its children in order. A horizontal box
     * shares its width equally among its children; a collapsible text in it gets its toggle linked.
     */
    private fun boxOf(node: Box, theme: Theme, onIntent: (ScreenIntent) -> Unit): View =
        LinearLayout(context).apply {
            orientation = if (node.horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(theme.argbOf(node.background))
            layoutParams = rowParams()
            node.children.forEach { addView(childOf(it, node.horizontal, theme, onIntent)) }
            node.children.filterIsInstance<CollapsibleText>().forEach { linkToggle(this, it.id) }
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

    /** As wide and as tall as its content. */
    private fun wrapParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** The view for [node] inside a box; in a horizontal box each child takes an equal share of the width. */
    private fun childOf(node: Node, horizontal: Boolean, theme: Theme, onIntent: (ScreenIntent) -> Unit): View {
        val view = viewOf(node, theme, onIntent)
        if (horizontal) view.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return view
    }

    /**
     * Ties the collapsible text [textId] in [row] to its toggle, the view tagged with the same id
     * and the suffix replaced. The toggle shows only while the decision names one.
     */
    private fun linkToggle(row: ViewGroup, textId: String) {
        val text = findTagged(row, textId) as? CollapsibleTextView ?: return
        val toggleId = textId.removeSuffix(TEXT_SUFFIX) + TOGGLE_SUFFIX
        text.onMiddle = { middle ->
            val toggle = findTagged(row, toggleId)
            val wanted = if (middle == Middle.NONE) View.GONE else View.VISIBLE
            if (toggle != null && toggle.visibility != wanted) toggle.visibility = wanted
        }
    }

    /** The first view under [root] whose tag is [id], the root included, or null. */
    private fun findTagged(root: View, id: String): View? {
        if (root.tag == id) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            val found = findTagged(group.getChildAt(index), id)
            if (found != null) return found
        }
        return null
    }

    /** A text view that folds to the collapse rule's line count, in the body role and the node's slot colour. */
    private fun collapsibleOf(node: CollapsibleText, theme: Theme): View =
        CollapsibleTextView(context, node.opened).apply {
            setTextAppearance(appearanceOf(TypeRole.BODY))
            typeface = typefaceOf(TypeRole.BODY, theme)
            setTextColor(theme.argbOf(node.color))
            text = node.text
            layoutParams = rowParams()
        }

    /**
     * The kit button for an action whose [kit] names its look. The words and the enabled state come
     * from the node, and a click reports the node's intent while the button is enabled.
     */
    private fun kitActionOf(kit: KitLook, node: Action, theme: Theme, onIntent: (ScreenIntent) -> Unit): View {
        val view: View = when (kit) {
            KitLook.RAISED -> Button3dView(context, node.text, theme)
            KitLook.NEUTRAL -> NeutralButtonView(context, node.text, theme)
            KitLook.DANGER -> DeleteButtonView(context, node.text, theme)
            KitLook.OUTLINED -> OutlinedButtonView(context, node.text, theme)
        }
        view.isEnabled = node.enabled
        if (node.enabled) view.setOnClickListener { onIntent(node.intent) }
        view.layoutParams = rowParams()
        return view
    }

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
