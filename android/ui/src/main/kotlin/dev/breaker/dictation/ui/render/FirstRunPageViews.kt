package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.breaker.dictation.ui.render.controls.FullWidthButtonView
import dev.breaker.dictation.ui.render.controls.NeutralButtonView
import dev.breaker.dictation.ui.render.controls.StepMarksView
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.firstrun.FirstRunLayout
import dev.breaker.dictation.ui.screen.firstrun.FirstRunNext
import dev.breaker.dictation.ui.screen.firstrun.FirstRunPages
import dev.breaker.dictation.ui.screen.firstrun.FirstRunPictures
import dev.breaker.dictation.ui.screen.firstrun.FirstRunShown
import dev.breaker.dictation.ui.screen.firstrun.FirstRunTexts
import dev.breaker.dictation.ui.screen.firstrun.FirstRunWords
import dev.breaker.dictation.ui.screen.onboarding.OpenAction
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/*
 * The pieces the first-run pages are built from: a column, the words, the pictures and the buttons.
 *
 * Every size comes from FirstRunLayout through dpToPx, and every colour from the theme. The
 * views decide nothing: a page is laid out in the order its parts are given.
 */

/** The text appearance of a heading, the platform's large one. */
private val APPEARANCE_LARGE: Int = android.R.style.TextAppearance_DeviceDefault_Large

/** The text appearance of the body words, the platform's medium one. */
private val APPEARANCE_MEDIUM: Int = android.R.style.TextAppearance_DeviceDefault_Medium

/** Builds the pieces of a first-run page in [theme]. */
internal class PageBuilder(val context: Context, val theme: Theme) {
    private val density: Float = context.resources.displayMetrics.density
    private val side: Int = FirstRunLayout.dpToPx(FirstRunLayout.PAGE_PADDING_DP, density)
    private val above: Int = FirstRunLayout.dpToPx(FirstRunLayout.GAP_DP, density)

    /** A full-width column with the page padding on every side. */
    fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(side, side, side, side)
        layoutParams = rowParams(false)
    }

    /** Full width, as tall as its content, with the gap above it when [spaced] is true. */
    fun rowParams(spaced: Boolean): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (spaced) topMargin = above
        }

    /** A heading, in the display face and the text colour. */
    fun headingText(value: String): TextView =
        styled(value, APPEARANCE_LARGE, theme.type.displayFamily, theme.color(PaletteSlot.TEXT).argb)

    /** A body line, in the body face and the text colour. */
    fun bodyText(value: String): TextView =
        styled(value, APPEARANCE_MEDIUM, theme.type.bodyFamily, theme.color(PaletteSlot.TEXT).argb)

    /** A line of muted text, in the body face. */
    fun mutedText(value: String): TextView =
        styled(value, APPEARANCE_MEDIUM, theme.type.bodyFamily, theme.color(PaletteSlot.TEXT_MUTED).argb)

    /** A notice, in the body face and the danger colour. */
    fun dangerText(value: String): TextView =
        styled(value, APPEARANCE_MEDIUM, theme.type.bodyFamily, theme.color(PaletteSlot.DANGER).argb)

    /** A line of figures, in the mono face and the text colour. */
    fun monoText(value: String): TextView =
        styled(value, APPEARANCE_MEDIUM, theme.type.monoFamily, theme.color(PaletteSlot.TEXT).argb)

    private fun styled(value: String, appearance: Int, family: String, argb: Int): TextView =
        TextView(context).apply {
            setTextAppearance(appearance)
            typeface = Typeface.create(family, Typeface.NORMAL)
            setTextColor(argb)
            text = value
            layoutParams = rowParams(true)
        }

    /** The picture named [name], as wide as the page and as tall as its own shape. Draws nothing when no picture has the name. */
    fun picture(name: String): ImageView {
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return ImageView(context).apply {
            adjustViewBounds = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (id != 0) setImageResource(id)
            layoutParams = rowParams(true)
        }
    }

    /** The one full-width button, with its words and what a tap does. */
    fun fullButton(label: String, onTap: () -> Unit): FullWidthButtonView =
        FullWidthButtonView(context, label, theme).apply {
            setOnClickListener { onTap() }
            layoutParams = rowParams(true)
        }

    /** The Next button at the foot of a page: green once its step is granted, grey otherwise. */
    fun nextButton(look: KitLook, onTap: () -> Unit): View {
        when (look) {
            KitLook.RAISED -> return FullWidthButtonView(context, FirstRunTexts.NEXT, theme).apply { setOnClickListener { onTap() }; layoutParams = rowParams(true) }
            else -> return NeutralButtonView(context, FirstRunTexts.NEXT, theme).apply { setOnClickListener { onTap() }; layoutParams = rowParams(true) }
        }
    }

    /**
     * One permission page, top to bottom: the step marks, the notice, the picture, the heading,
     * the reason, the extra lines, the status word, the one grant button and Next. The last
     * permission page also lists what is still missing.
     */
    fun permissionPage(shown: FirstRunShown, dark: Boolean, onTap: (OpenAction) -> Unit, onNext: () -> Unit): LinearLayout {
        val page = column()
        page.addView(
            StepMarksView(context, FirstRunPages.STEP_COUNT, shown.step, FirstRunLayout.MARK_W_DP, FirstRunLayout.MARK_H_DP, theme),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        shown.notice?.let { page.addView(dangerText(it)) }
        FirstRunPictures.nameFor(shown.page, dark)?.let { page.addView(picture(it)) }
        FirstRunWords.heading(shown.page)?.let { page.addView(headingText(it)) }
        FirstRunWords.why(shown.page)?.let { page.addView(bodyText(it)) }
        for (extra in FirstRunWords.extras(shown.page, shown.state)) page.addView(mutedText(extra))
        val granted = FirstRunNext.ready(shown)
        page.addView(bodyText(FirstRunWords.status(granted)))
        if (!FirstRunPages.isLastPermission(shown.page)) shown.missing.firstOrNull { it.page == shown.page }?.let { item ->
            page.addView(fullButton(FirstRunWords.buttonFor(item.action)) { onTap(item.action) })
        }
        page.addView(nextButton(FirstRunNext.look(shown), onNext))
        if (FirstRunPages.isLastPermission(shown.page)) addMissing(page, shown, onTap)
        return page
    }

    /** The list under the last permission page: its heading, and each missing item with its own button. */
    private fun addMissing(page: LinearLayout, shown: FirstRunShown, onTap: (OpenAction) -> Unit) {
        page.addView(headingText(FirstRunTexts.MISSING_HEADING))
        if (shown.missing.isEmpty()) page.addView(bodyText(FirstRunTexts.MISSING_NONE))
        for (item in shown.missing) {
            FirstRunWords.heading(item.page)?.let { page.addView(bodyText(it)) }
            page.addView(fullButton(FirstRunWords.buttonFor(item.action)) { onTap(item.action) })
        }
    }
}
