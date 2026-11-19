package dev.breaker.dictation.ui.screen.about

/** The ids of the About screen's nodes. */
internal object AboutNodeIds {
    const val SCREEN = "about"
    const val BACKGROUND = "about.background"
    const val TITLE = "about.title"
    const val LINE_1 = "about.line1"
    const val LINE_2 = "about.line2"
    const val NOTICES = "about.notices"
    const val CHECK = "about.check"
    const val INSTALL = "about.install"
    const val STATUS = "about.status"

    /** The id of the notice at [index]: the first keeps NOTICES, each later one adds its index after a dot. */
    fun notice(index: Int): String = if (index == 0) NOTICES else "$NOTICES.$index"
}
