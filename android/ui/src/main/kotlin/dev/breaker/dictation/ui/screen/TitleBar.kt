package dev.breaker.dictation.ui.screen

import dev.breaker.dictation.ui.controls.ControlTexts
import dev.breaker.dictation.ui.theme.PaletteSlot

/*
 * The title bar of a screen that has a back control: a back arrow, the title in italic
 * capitals, and an accent rule under the bar. The caller puts these nodes first in the
 * screen, so the bar sits above every block.
 */

/**
 * The title bar for the screen whose ids start with [idPrefix], titled [title].
 *
 * The bar is a horizontal row: the back arrow at its left, then the title. It is followed by
 * a full-width accent rule, which the host draws through its padding.
 */
internal fun titleBar(idPrefix: String, title: String): List<Node> = listOf(
    Box(
        id = "$idPrefix.titlebar",
        background = PaletteSlot.BACKGROUND,
        horizontal = true,
        children = listOf(
            BackArrow("$idPrefix.back", ScreenIntent.Back, description = ControlTexts.BACK_ARROW),
            Label("$idPrefix.title", title, TypeRole.DISPLAY, PaletteSlot.TEXT, caps = true, italic = true),
        ),
    ),
    Rule("$idPrefix.rule", PaletteSlot.ACCENT),
)
