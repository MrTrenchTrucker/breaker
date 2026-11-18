package dev.breaker.dictation.ui.screen.about

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot

/**
 * The About screen, described as nodes and nothing else.
 *
 * The screen shows exactly two lines: what Breaker does and the license.
 * No version line unless one already exists on main.
 *
 * With the default state the tree is exactly those two lines and nothing else, so the screen is
 * unchanged for a person who is not an admin. With an admin state the rows the decision allows and
 * the one status sentence it names are added after them; which rows and which sentence is
 * [decideAbout]'s answer, not this screen's.
 */
internal class AboutScreen {

    /** Builds the tree: the title, the two body lines, and for an admin the rows and the sentence. */
    fun render(state: AboutState = AboutState()): Screen {
        val decision = decideAbout(state)
        return Screen(
            id = AboutNodeIds.SCREEN,
            title = AboutTexts.TITLE,
            nodes = buildList {
                add(Box(AboutNodeIds.BACKGROUND, PaletteSlot.BACKGROUND))
                add(Label(AboutNodeIds.TITLE, AboutTexts.TITLE, TypeRole.DISPLAY, PaletteSlot.TEXT))
                add(Label(AboutNodeIds.LINE_1, AboutTexts.LINE_1, TypeRole.BODY, PaletteSlot.TEXT))
                add(Label(AboutNodeIds.LINE_2, AboutTexts.LINE_2, TypeRole.BODY, PaletteSlot.TEXT_MUTED))
                if (state.admin) {
                    if (AboutActions.CHECK in decision.rows) {
                        add(Action(AboutNodeIds.CHECK, AboutTexts.CHECK_LABEL, intent = ScreenIntent.About(AboutActions.CHECK)))
                    }
                    if (AboutActions.INSTALL in decision.rows) {
                        add(Action(AboutNodeIds.INSTALL, AboutTexts.INSTALL, intent = ScreenIntent.About(AboutActions.INSTALL)))
                    }
                    decision.sentence?.let { add(Label(AboutNodeIds.STATUS, it, TypeRole.BODY, PaletteSlot.TEXT_MUTED)) }
                }
            },
        )
    }
}
