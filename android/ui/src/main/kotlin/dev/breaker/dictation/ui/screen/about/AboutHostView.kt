package dev.breaker.dictation.ui.screen.about

import android.content.Context
import android.view.ViewGroup
import android.widget.ScrollView
import dev.breaker.dictation.ui.render.ScreenRenderer
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/**
 * The view the app puts on the display for the About screen.
 *
 * A simple scroll view: the screen is static, with no actions, so the view
 * renders it once and holds it. The screen's words come from [AboutScreen],
 * which shows the two ruled lines.
 */
internal class AboutHostView(
    context: Context,
    private val screen: AboutScreen,
    private val theme: Theme,
    private val renderer: ScreenRenderer,
) : ScrollView(context) {

    init {
        isFillViewport = true
        val rendered = renderer.render(screen.render(), theme, {})
        addView(rendered, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setBackgroundColor(theme.color(PaletteSlot.BACKGROUND).argb)
    }
}
