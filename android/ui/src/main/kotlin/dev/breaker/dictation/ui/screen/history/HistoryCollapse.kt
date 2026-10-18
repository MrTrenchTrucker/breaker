package dev.breaker.dictation.ui.screen.history

/*
 * The one decision about how many lines a long history text shows.
 *
 * A text is counted in the lines it draws at the width it is given. Up to the cap it
 * shows whole and offers no middle button. Past the cap it shows the cap while it is
 * closed and offers Show all, and shows every line while it is open and offers Hide.
 * The view asks this function once per measure and applies the answer.
 */

/** The most lines a closed history text shows. The one cap for this rule. */
internal const val COLLAPSE_LINES = 6

/** Which middle button a row offers: none, Show all while the text is closed, or Hide while it is open. */
internal enum class Middle { NONE, SHOW_ALL, HIDE }

/** How many lines a text may show, where null means no limit, and which middle button goes with it. */
internal data class Collapse(val maxLines: Int?, val middle: Middle)

/**
 * The collapse decision for a text that draws [drawnLines] lines, closed or [opened].
 *
 * A text no longer than the cap has no limit and no button, open or closed. A longer text
 * closed has the cap as its limit and Show all; a longer text open has no limit and Hide.
 */
internal fun collapse(drawnLines: Int, opened: Boolean): Collapse = when {
    drawnLines <= COLLAPSE_LINES -> Collapse(null, Middle.NONE)
    opened -> Collapse(null, Middle.HIDE)
    else -> Collapse(COLLAPSE_LINES, Middle.SHOW_ALL)
}
