package dev.breaker.dictation.probe

/** The debug probe's stored choice, over one read and one write of a word. Pure: no Android type. */
class ProbeSettingStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    /** The stored choice; a missing or unknown word reads as OFF. */
    fun load(): KeyboardSignalSetting = keyboardSignalFromWord(read())

    /** Stores the name of [setting]. */
    fun save(setting: KeyboardSignalSetting) {
        write(setting.name)
    }
}
