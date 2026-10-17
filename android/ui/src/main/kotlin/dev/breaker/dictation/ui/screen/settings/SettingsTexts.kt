package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.screen.history.HistoryTexts

/*
 * Every word the settings screen shows, in one place.
 *
 * The words are stored in sentence case; the screen draws capitals where the
 * design asks for them. A word that is already shown on another screen is not
 * repeated here: the set-up screen's model heading is read from its own object.
 */
internal object SettingsTexts {
    /** The three appearance cells, one for each stored choice. */
    const val SEGMENT_SYSTEM = "System"
    const val SEGMENT_LIGHT = "Light"
    const val SEGMENT_DARK = "Dark"

    /** The appearance block and its line about the system choice. */
    const val APPEARANCE_HEADING = "Appearance"
    const val APPEARANCE_SYSTEM_HELP = "System matches your phone's light or dark setting."

    /** The routing block, its three cells and the line about automatic routing. */
    const val ROUTING_HEADING = "Where speech turns into text"
    const val ROUTING_AUTO = "Automatic"
    const val ROUTING_PHONE = HistoryTexts.TAG_LOCAL
    const val ROUTING_SERVER = HistoryTexts.TAG_SERVER
    const val ROUTING_AUTO_HELP = "Automatic uses the server when it answers, and the phone when it does not."

    /** The dictation block: its heading, and each switch with its label and its line. */
    const val DICTATION_HEADING = "Dictation"
    const val PRELOAD_LABEL = "Preload model"
    const val PRELOAD_HELP = "Keeps the model ready for a faster start."
    const val WAKE_LABEL = "Wake gesture"
    const val WAKE_HELP = "Shake the phone to bring the tile back."
    const val FORMATTING_LABEL = "Formatting"
    const val FORMATTING_HELP = "Spoken lists become lists. Filler words go."

    /** The model and server block: its heading and the rows that hold the values. */
    const val VALUES_HEADING = "Model and server"
    const val SERVER_ROW_LABEL = "Server address"
    const val API_KEY_LABEL = "API key"
    const val LANGUAGE_ENGLISH = "English"

    /** What a row says when it holds no value: no address, or a key that is not held. */
    const val NOT_SET_TEXT = "(not set)"
    const val HELD_TEXT = "set"
    const val NOT_HELD_TEXT = "not set"
}

/** The name shown for a language code: English for "en", and any other code as it is stored. */
internal fun languageName(code: String): String =
    if (code == "en") SettingsTexts.LANGUAGE_ENGLISH else code
