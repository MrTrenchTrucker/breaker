package dev.breaker.dictation.probe

import org.junit.Assert.assertEquals
import org.junit.Test

internal class ProbeSettingStoreTest {

    /** A store over one in-memory map, under the one key the store writes. */
    private fun storeOver(storage: MutableMap<String, String>): ProbeSettingStore = ProbeSettingStore(
        read = { storage[KEY] },
        write = { word -> storage[KEY] = word },
    )

    @Test
    fun `a saved value is read back by a fresh store over the same storage`() {
        val storage: MutableMap<String, String> = HashMap()
        storeOver(storage).save(KeyboardSignalSetting.FIELD_FOCUS)
        assertEquals(
            "probe: a saved value must be read back by a fresh store",
            KeyboardSignalSetting.FIELD_FOCUS,
            storeOver(storage).load(),
        )
    }

    @Test
    fun `an empty storage loads off`() {
        assertEquals(
            "probe: an empty storage must load as OFF",
            KeyboardSignalSetting.OFF,
            storeOver(HashMap()).load(),
        )
    }

    @Test
    fun `a stored unknown word loads off`() {
        val storage: MutableMap<String, String> = HashMap()
        storage[KEY] = "KEYBOARD"
        assertEquals("probe: a stored unknown word must load as OFF", KeyboardSignalSetting.OFF, storeOver(storage).load())
    }

    private companion object {
        const val KEY: String = "signal"
    }
}
