package dev.breaker.dictation.ui.screen.about

import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot

/**
 * The About screen, described as nodes and nothing else.
 *
 * The screen shows exactly two lines: what Breaker does and the license.
 * No version line unless one already exists on main.
 */
internal class AboutScreen {

    /** Builds the tree: the title and the two ruled lines. */
    fun render(): Screen = Screen(
        id = AboutNodeIds.SCREEN,
        title = AboutTexts.TITLE,
        nodes = buildList {
            add(Box(AboutNodeIds.BACKGROUND, PaletteSlot.BACKGROUND))
            add(Label(AboutNodeIds.TITLE, AboutTexts.TITLE, TypeRole.DISPLAY, PaletteSlot.TEXT))
            add(Label(AboutNodeIds.LINE_1, AboutTexts.LINE_1, TypeRole.BODY, PaletteSlot.TEXT))
            add(Label(AboutNodeIds.LINE_2, AboutTexts.LINE_2, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
        },
    )
}
