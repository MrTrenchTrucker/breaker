package dev.breaker.dictation.ui.render

import android.content.Context
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
import dev.breaker.dictation.ui.screen.BackArrow
import dev.breaker.dictation.ui.screen.BottomStripes
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Dot
import dev.breaker.dictation.ui.screen.Rule
import dev.breaker.dictation.ui.screen.CollapsibleText
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.render.controls.SwitchView
import dev.breaker.dictation.ui.screen.MenuIcon
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.Spacer
import dev.breaker.dictation.ui.screen.Switch
import dev.breaker.dictation.ui.screen.home.HomeGeometry
import dev.breaker.dictation.ui.screen.SegmentedRow
import dev.breaker.dictation.ui.screen.SwitchRow
import dev.breaker.dictation.ui.screen.TagPill
import dev.breaker.dictation.ui.screen.TextAlign
import dev.breaker.dictation.ui.screen.TouchGap
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
 * radius. Every distance comes from the token scale, read by name through [spaceDp]:
 * a box's pad and gap are its only spacing, and a row carries none of its own,
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
            is MenuIcon -> menuIconOf(node, theme, onIntent)
            is Switch -> switchOf(node, theme, onIntent)
            is Rule -> ruleOf(node, theme)
            is BottomStripes -> bottomStripesOf(node, theme)
            is BackArrow -> backArrowOf(node, theme, onIntent)
            is Spacer -> spacerOf(node, theme)
            is Dot -> dotOf(node, theme)
            is TouchGap -> touchGapOf(theme)
        }.apply { tag = node.id }

    /**
     * A layout in the box's background slot, holding its children in order. A horizontal box
     * shares its width equally among its children, unless it is spread: then each child keeps
     * its own width and the free width sits between them. A collapsible text in it gets its
     * toggle linked.
     */
    private fun boxOf(node: Box, theme: Theme, onIntent: (ScreenIntent) -> Unit): View =
        LinearLayout(context).apply {
            orientation = if (node.horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (node.outline == null && node.edge == null) {
                setBackgroundColor(theme.argbOf(node.background))
            } else {
                background = cardOf(node, theme)
            }
            node.onClick?.let { intent -> setOnClickListener { onIntent(intent) } }
            layoutParams = rowParams()
            val side = pixelsOf(theme, node.pad)
            setPadding(side, side, side, side)
            node.children.forEachIndexed { index, child ->
                addView(placedChild(child, index, node, theme, onIntent))
                if (index < spreadSeparatorCount(node.children.size) && node.horizontal && node.spread) addView(separatorOf())
            }
            node.children.filterIsInstance<CollapsibleText>().forEach { linkToggle(this, it.id) }
        }

    /**
     * The view for [child] in [box], placed in order. In a horizontal box a spacer takes its size as
     * a width, and in a spread row every other child keeps its own width. Each child after the first is
     * pushed from the one before it by the box's gap; the gap is set after the child's layout parameters,
     * so the margin is not replaced, and a spread row sets no gap because its free width sits between
     * the children instead.
     */
    private fun placedChild(child: Node, index: Int, box: Box, theme: Theme, onIntent: (ScreenIntent) -> Unit): View {
        val view = childOf(child, box.horizontal, theme, onIntent)
        if (child is Spacer && box.horizontal) {
            view.layoutParams = LinearLayout.LayoutParams(pixelsOf(theme, child.size), ViewGroup.LayoutParams.WRAP_CONTENT)
        } else if (box.horizontal && box.spread) {
            view.layoutParams = wrapParams()
        }
        val params = view.layoutParams as? LinearLayout.LayoutParams
        if (params != null && index > 0 && !(box.horizontal && box.spread)) {
            val step = pixelsOf(theme, box.gap)
            if (box.horizontal) {
                params.setMarginStart(step)
            } else {
                params.topMargin = step
            }
        }
        return view
    }

    /** The size of [space] in pixels on this screen: the scale in dp times the screen density. */
    private fun pixelsOf(theme: Theme, space: Space): Int =
        (spaceDp(theme.metrics, space) * context.resources.displayMetrics.density).toInt()

    /** An empty view as tall as the node's size in a column. It is announced to no one. */
    private fun spacerOf(node: Spacer, theme: Theme): View =
        View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pixelsOf(theme, node.size))
        }

    /** The empty view between two children of a spread row: weight one, so it takes the free width, and announced to no one. */
    private fun separatorOf(): View =
        View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    /** The small square of a [Dot]: one step S of the scale on each side, in its slot colour. It is not a control. */
    private fun dotOf(node: Dot, theme: Theme): View {
        val side = pixelsOf(theme, Space.S)
        return DotView(context, theme.argbOf(node.color), side).apply {
            layoutParams = LinearLayout.LayoutParams(side, side)
        }
    }

    /** The empty block that balances the menu icon: the same touch-target square, so the wordmark stays centred. */
    private fun touchGapOf(theme: Theme): View =
        TouchGapView(context, touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), context.resources.displayMetrics.density))
            .apply { layoutParams = wrapParams() }

    /**
     * A text view in the label's slot, family and alignment.
     *
     * The text appearance is set first because an appearance carries its own
     * colour, which the slot colour then replaces.
     */
    private fun labelOf(node: Label, theme: Theme): View =
        TextView(context).apply {
            setTextAppearance(appearanceOf(node.role))
            typeface = typefaceOf(node.role, theme, node.italic)
            setTextColor(theme.argbOf(node.color))
            textAlignment = alignmentOf(node.align)
            text = displayText(node.text, node.caps)
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
        if (horizontal) view.layoutParams = if (wrapsInRow(node)) wrapParams() else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return view
    }

    /** The kinds that keep their own width in a horizontal box, rather than sharing the width equally. */
    private fun wrapsInRow(node: Node): Boolean =
        node is MenuIcon || node is Switch || node is BackArrow || node is TagPill || node is Rule || node is Dot ||
            node is TouchGap || (node is Action && node.kit == KitLook.TEXT)

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
            KitLook.RAISED -> Button3dView(context, node.text, theme, node.compact)
            KitLook.NEUTRAL -> NeutralButtonView(context, node.text, theme, node.compact)
            KitLook.DANGER -> DeleteButtonView(context, node.text, theme, node.compact)
            KitLook.OUTLINED -> OutlinedButtonView(context, node.text, theme, node.compact)
            KitLook.TEXT -> TextRowView(context, node.text, theme)
        }
        view.isEnabled = node.enabled
        if (node.enabled) view.setOnClickListener { onIntent(node.intent) }
        view.layoutParams = rowParams()
        return view
    }

    /** The view text alignment that matches a node alignment. */
    private fun alignmentOf(align: TextAlign): Int = when (align) {
        TextAlign.START -> View.TEXT_ALIGNMENT_VIEW_START
        TextAlign.CENTER -> View.TEXT_ALIGNMENT_CENTER
        TextAlign.END -> View.TEXT_ALIGNMENT_VIEW_END
    }

    /**
     * The menu icon: the three lines the art draws, in the text slot, touch-target
     * sized and tappable. A tap reports the node's intent.
     */
    private fun menuIconOf(node: MenuIcon, theme: Theme, onIntent: (ScreenIntent) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), resources.displayMetrics.density)
            minimumHeight = touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), resources.displayMetrics.density)
            HomeGeometry.topBar(0f).menuLines.forEachIndexed { index, line ->
                addView(View(context).apply {
                    setBackgroundColor(theme.argbOf(PaletteSlot.TEXT))
                    layoutParams = LinearLayout.LayoutParams(
                        (line.width * resources.displayMetrics.density).toInt(),
                        (line.height * resources.displayMetrics.density).toInt(),
                    ).apply {
                        if (index > 0) topMargin = ((HomeGeometry.MENU_STEP_DP - HomeGeometry.MENU_H_DP) * resources.displayMetrics.density).toInt()
                    }
                })
            }
            contentDescription = node.description
            setOnClickListener { onIntent(node.intent) }
            layoutParams = wrapParams()
        }

    /**
     * The switch: the kit's SwitchView with the card art's track and knob dims.
     * A tap reports the node's intent.
     */
    private fun switchOf(node: Switch, theme: Theme, onIntent: (ScreenIntent) -> Unit): View {
        val dims = HomeGeometry.statusCard().switch
        return SwitchView(
            context,
            node.description,
            dims.track.width,
            dims.track.height,
            theme,
        ).apply {
            on = node.on
            onToggled = { onIntent(node.intent) }
            layoutParams = rowParams()
        }
    }

    /** The fill of a box with an outline or an edge bar: its colours, and the widths the card art gives in pixels. */
    private fun cardOf(node: Box, theme: Theme): CardDrawable {
        val density = context.resources.displayMetrics.density
        return CardDrawable(
            fill = theme.argbOf(node.background),
            outline = node.outline?.let { theme.argbOf(it) },
            outlineWidth = HomeGeometry.BOX_OUTLINE_DP * density,
            edge = node.edge?.let { theme.argbOf(it) },
            edgeWidth = HomeGeometry.ACCENT_BAR_W_DP * density,
        )
    }

    /** A full-width rule in its slot, as thick as the Home rule. */
    private fun ruleOf(node: Rule, theme: Theme): View =
        RuleView(context, theme.argbOf(node.color), (HomeGeometry.RULE_H_DP * context.resources.displayMetrics.density).toInt())
            .apply { layoutParams = rowParams() }

    /** The brand stripes, with weight one so that a short screen puts them at the bottom. */
    private fun bottomStripesOf(node: BottomStripes, theme: Theme): View =
        BottomStripesView(context, theme, node.plain).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

    /** The drawn back arrow, at the touch-target size, in the text slot. */
    private fun backArrowOf(node: BackArrow, theme: Theme, onIntent: (ScreenIntent) -> Unit): View =
        BackArrowView(
            context,
            node.intent,
            theme.argbOf(PaletteSlot.TEXT),
            touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), context.resources.displayMetrics.density),
            onIntent,
        ).apply { layoutParams = wrapParams(); contentDescription = node.description }

    /** The colour of [slot] in this theme as the packed ARGB int views take. The only place a token colour becomes an Int. */
    private fun Theme.argbOf(slot: PaletteSlot): Int = color(slot).argb
}

/**
 * The first view under [root] whose tag is [id], the root included, or null.
 *
 * The one lookup of a tagged view that the renderer and the two host views share.
 */
internal fun findTagged(root: View, id: String): View? {
    if (root.tag == id) return root
    val group = root as? ViewGroup ?: return null
    for (index in 0 until group.childCount) {
        val found = findTagged(group.getChildAt(index), id)
        if (found != null) return found
    }
    return null
}
