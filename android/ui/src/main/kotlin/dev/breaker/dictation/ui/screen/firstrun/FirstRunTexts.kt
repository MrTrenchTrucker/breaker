package dev.breaker.dictation.ui.screen.firstrun

/*
 * The words the first-run pages add of their own, in one place.
 *
 * The rest of the words on those pages come from the set-up texts. These strings are plain
 * ASCII and not blank. The capitals shown on screen are styling and are not part of them.
 * A placeholder in braces is replaced by a number, as plain decimal text.
 */

/** The words of the first-run pages that are not in the set-up texts. */
internal object FirstRunTexts {
    /** The button that moves on to the next page. */
    const val NEXT = "Next"

    /** The heading of the last permission page when something is still missing. */
    const val MISSING_HEADING = "Still missing"

    /** The line of the last permission page when nothing is missing. */
    const val MISSING_NONE = "Everything is set."

    /** The bar caption when the total is known; {done} and {total} are replaced by the numbers. */
    const val BAR_MB_OF = "{done} of {total} MB"

    /** The bar caption when the total is not known; {done} is replaced by the number. */
    const val BAR_MB_SO_FAR = "{done} MB so far"

    /** The bar percent caption; {pct} is replaced by the number. */
    const val BAR_PERCENT = "{pct}%"

    /** The content description of the welcome logo. */
    const val LOGO_DESCRIPTION = "Breaker"

    /**
     * The megabyte caption under the bar: the total form when the total is above zero, and the
     * so-far form when it is not. The two numbers are put in exactly as they are given.
     */
    fun mbLine(doneMb: Int, totalMb: Int): String =
        if (totalMb > 0) {
            BAR_MB_OF.replace("{done}", doneMb.toString()).replace("{total}", totalMb.toString())
        } else {
            BAR_MB_SO_FAR.replace("{done}", doneMb.toString())
        }

    /** The percent caption for [pct]. */
    fun percentLine(pct: Int): String = BAR_PERCENT.replace("{pct}", pct.toString())
}
