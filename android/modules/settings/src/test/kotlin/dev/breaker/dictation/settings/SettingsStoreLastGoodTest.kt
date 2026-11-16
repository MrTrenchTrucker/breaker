package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SettingsStore.recordServerLastGood] changes one field and nothing else.
 *
 * The probe thread writes the address that answered while the user may be saving their own
 * settings. The promise is that this write cannot disturb any other setting: the test saves a
 * settings object that is non-default on every key the file carries, records a last good
 * address, and compares the reloaded settings field by field against the saved one. Only
 * `serverLastGood` may differ - a write that carried a stale copy of the whole object, or that
 * wrote the address into the wrong field, shows up here.
 */
class SettingsStoreLastGoodTest : SettingsFileStoreTestBase() {

    @Test
    fun `recording the last good address changes only server_last_good`() {
        val file = newFile()
        val keystore = FakeKeystore().apply { setActiveRef("REFLASTGOOD") }
        val store = store(file, keystore)
        val saved = validSettings().copy(
            serverAddresses = listOf("https://box.local", "https://road.local"),
            serverLastGood = "",
            apiKeyRef = "REFLASTGOOD",
        )
        store.save(saved)

        store.recordServerLastGood("https://road.local")

        val reloaded = store.load()
        assertEquals("the last good address is the one recorded", "https://road.local", reloaded.serverLastGood)
        assertEquals("mode is unchanged", saved.mode, reloaded.mode)
        assertEquals("model size is unchanged", saved.modelSize, reloaded.modelSize)
        assertEquals("serverUrl is unchanged", saved.serverUrl, reloaded.serverUrl)
        assertEquals("the ordered address list is unchanged", saved.serverAddresses, reloaded.serverAddresses)
        assertEquals("wake gesture is unchanged", saved.wakeGestureMode, reloaded.wakeGestureMode)
        assertEquals("tile position is unchanged", saved.tilePosition, reloaded.tilePosition)
        assertEquals("language is unchanged", saved.language, reloaded.language)
        assertEquals("preload model is unchanged", saved.preloadModel, reloaded.preloadModel)
        assertEquals("formatting is unchanged", saved.formattingEnabled, reloaded.formattingEnabled)
        assertEquals("theme mode is unchanged", saved.themeMode, reloaded.themeMode)
        assertEquals("microphone asked is unchanged", saved.microphoneAsked, reloaded.microphoneAsked)
        assertEquals("the credential reference is unchanged", saved.apiKeyRef, reloaded.apiKeyRef)
        assertEquals(
            "the file itself carries the recorded address",
            "https://road.local",
            propertyOf(file, KEY_SERVER_LAST_GOOD),
        )
    }
}
