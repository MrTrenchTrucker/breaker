package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.CollapsibleText
import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.theme.PaletteSlot

/** The shared transcript card: the collapsible text and the buttons that act on it. */
internal data class TranscriptCard(
    val text: CollapsibleText,
    val buttons: Box,
)

/**
 * Builds the shared transcript card for [prefix]: the collapsible text at
 * `$prefix.text` and the buttons box at `$prefix.buttons`.
 *
 * The toggle button is always present. The copy button is drawn only when [copyIntent] is
 * not null, and the delete button only when [deleteIntent] is not null. The toggle word
 * flips with [opened]. The buttons spread across the row: the first button sits at the
 * start and the delete button at the end, each at its natural width, and the toggle takes
 * no place while it is hidden.
 *
 * When [compact] is set the three buttons take the narrower compact width, so that three of
 * them fit one row of a narrow phone with the card's padding and gaps; the History entry rows
 * set it and the Home card keeps the default width.
 */
internal fun transcriptCard(
    prefix: String,
    text: String,
    blankText: String,
    opened: Boolean,
    copyIntent: ScreenIntent?,
    toggleIntent: ScreenIntent,
    deleteIntent: ScreenIntent? = null,
    compact: Boolean = false,
): TranscriptCard {
    val blank = text.isBlank()
    val textNode = CollapsibleText(
        id = "$prefix.text",
        text = if (blank) blankText else text,
        color = if (blank) PaletteSlot.TEXT_MUTED else PaletteSlot.TEXT,
        opened = opened,
    )
    val buttons = Box(
        id = "$prefix.buttons",
        background = PaletteSlot.SURFACE,
        horizontal = true,
        spread = true,
        children = buildList {
            if (copyIntent != null) {
                add(
                    Action(
                        id = "$prefix.copy",
                        text = HistoryTexts.BUTTON_COPY,
                        intent = copyIntent,
                        kit = KitLook.RAISED,
                        compact = compact,
                    ),
                )
            }
            add(
                Action(
                    id = "$prefix.toggle",
                    text = if (opened) HistoryTexts.BUTTON_HIDE else HistoryTexts.BUTTON_SHOW_ALL,
                    intent = toggleIntent,
                    kit = KitLook.OUTLINED,
                    compact = compact,
                ),
            )
            if (deleteIntent != null) {
                add(
                    Action(
                        id = "$prefix.delete",
                        text = HistoryTexts.BUTTON_DELETE,
                        emphasis = Emphasis.DESTRUCTIVE,
                        intent = deleteIntent,
                        kit = KitLook.DANGER,
                        compact = compact,
                    ),
                )
            }
        },
    )
    return TranscriptCard(textNode, buttons)
}

/**
 * The shared card as one box: [header] first, then the text, then the buttons. It is outlined
 * in the accent slot on a surface fill, padded and spaced from the tokens. [id] names the box.
 */
internal fun transcriptCardBox(id: String, header: Node, card: TranscriptCard): Box = Box(
    id = id,
    background = PaletteSlot.SURFACE,
    outline = PaletteSlot.ACCENT,
    pad = Space.L,
    gap = Space.S,
    children = listOf(header, card.text, card.buttons),
)
