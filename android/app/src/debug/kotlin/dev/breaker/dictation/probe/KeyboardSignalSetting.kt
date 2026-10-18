package dev.breaker.dictation.probe

/** The debug probe's signal choice. OFF runs nothing; the other two only turn the probe on. */
enum class KeyboardSignalSetting { OFF, FIELD_FOCUS, KEYBOARD_VISIBLE }

/** The setting a stored word names; a missing, empty or unknown word reads as OFF. */
fun keyboardSignalFromWord(word: String?): KeyboardSignalSetting {
    val match: KeyboardSignalSetting? = KeyboardSignalSetting.entries.firstOrNull { it.name == word }
    return match ?: KeyboardSignalSetting.OFF
}
