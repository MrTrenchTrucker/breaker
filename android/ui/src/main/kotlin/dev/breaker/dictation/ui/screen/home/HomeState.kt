package dev.breaker.dictation.ui.screen.home

/**
 * The home screen's plain state, no platform names.
 *
 * The view reads this and draws it; the state holds no colour, no geometry
 * and no word of its own, those live in HomeGeometry and HomeTexts.
 */
internal data class HomeState(
    val switchOn: Boolean = false,
    val modelReady: Boolean = false,
    val serverReachable: Boolean = false,
    val lastDictation: String? = null,
    val lastTranscript: String? = null,
    val transcriptTime: String? = null,
    val menuOpen: Boolean = false,
    val notice: String? = null,
    val line: String? = null,
    val recording: Boolean = false,
)
