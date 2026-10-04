package dev.breaker.dictation.ui.screen.settings

/*
 * The messages a settings change can leave behind.
 *
 * A notice says only what happened: never what the reader attempted, and never
 * the stored value. A failed write leaves the stored settings exactly as it
 * found them, so a value in the message would describe something that is not
 * in force, and a value the user typed belongs to them rather than to the
 * screen. The texts are fixed here so that no message is assembled at the point
 * of failure, where the text of a caught exception could leak into it.
 */

/** Why the last settings action did not take effect. */
internal enum class Notice(val text: String) {
    /** The stored settings could not be read, so nothing has been written. */
    COULD_NOT_READ("Your settings could not be read. Nothing was changed."),

    /** The settings were read but the write did not reach storage. */
    COULD_NOT_SAVE("Your settings could not be saved. Nothing was changed."),

    /** The request names something this screen does not offer, or a value it will not store. */
    NOT_ACCEPTED("That change was not accepted. Nothing was changed."),
}
