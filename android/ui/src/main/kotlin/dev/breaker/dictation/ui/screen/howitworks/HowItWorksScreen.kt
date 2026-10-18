package dev.breaker.dictation.ui.screen.howitworks

import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.firstrun.FirstRunPages
import dev.breaker.dictation.ui.screen.firstrun.FirstRunWords
import dev.breaker.dictation.ui.theme.PaletteSlot

/**
 * The How it works screen, described as nodes and nothing else.
 *
 * The screen reuses the welcome headings and whys from [FirstRunWords]: each
 * permission page's heading and the short reason under it. The words are the
 * same ones the first-run pages show, so the wording stays in one place.
 */
internal class HowItWorksScreen {

    /** Builds the tree: the title, then each permission page's heading and why. */
    fun render(): Screen = Screen(
        id = HowItWorksNodeIds.SCREEN,
        title = HowItWorksTexts.TITLE,
        nodes = buildList {
            add(Box(HowItWorksNodeIds.BACKGROUND, PaletteSlot.BACKGROUND))
            add(Label(HowItWorksNodeIds.TITLE, HowItWorksTexts.TITLE, TypeRole.DISPLAY, PaletteSlot.TEXT))
            FirstRunPages.ORDER.forEach { page ->
                val heading = FirstRunWords.heading(page)
                val why = FirstRunWords.why(page)
                if (heading != null) {
                    add(Label("${HowItWorksNodeIds.PREFIX}.${page.name}.heading", heading, TypeRole.BODY, PaletteSlot.TEXT))
                }
                if (why != null) {
                    add(Label("${HowItWorksNodeIds.PREFIX}.${page.name}.why", why, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
                }
            }
        },
    )
}
