package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A settings store that records every load and save and can be made to throw. */
private class RecordingStore : SettingsStore {
    var current: AppSettings = AppSettings()
    var loadCount = 0
    var saveCount = 0
    var saved: MutableList<AppSettings> = mutableListOf()
    var failLoad = false
    var failSave = false

    override fun load(): AppSettings {
        loadCount++
        if (failLoad) throw IllegalStateException("load failed")
        return current
    }

    override fun save(settings: AppSettings) {
        saveCount++
        if (failSave) throw IllegalStateException("save failed")
        current = settings
        saved.add(settings)
    }
}

class ProbeRefreshingSettingsStoreTest {

    @Test
    fun `changed address calls the callback once after save`() {
        val delegate = RecordingStore().apply {
            current = AppSettings(serverUrl = "https://old.test")
        }
        var callbackCount = 0
        val store = ProbeRefreshingSettingsStore(delegate) { callbackCount++ }

        store.save(AppSettings(serverUrl = "https://new.test"))

        assertEquals("save must be delegated", 1, delegate.saveCount)
        assertEquals("callback must fire once for a changed address", 1, callbackCount)
        assertEquals("new address must be stored", "https://new.test", delegate.current.serverUrl)
    }

    @Test
    fun `same address does not call the callback`() {
        val delegate = RecordingStore().apply {
            current = AppSettings(serverUrl = "https://same.test")
        }
        var callbackCount = 0
        val store = ProbeRefreshingSettingsStore(delegate) { callbackCount++ }

        store.save(AppSettings(serverUrl = "https://same.test"))

        assertEquals("save must be delegated", 1, delegate.saveCount)
        assertEquals("callback must not fire for an unchanged address", 0, callbackCount)
    }

    @Test
    fun `blank to blank does not call the callback`() {
        val delegate = RecordingStore().apply {
            current = AppSettings(serverUrl = "")
        }
        var callbackCount = 0
        val store = ProbeRefreshingSettingsStore(delegate) { callbackCount++ }

        store.save(AppSettings(serverUrl = ""))

        assertEquals("save must be delegated", 1, delegate.saveCount)
        assertEquals("callback must not fire for blank-to-blank", 0, callbackCount)
    }

    @Test
    fun `throwing save calls no callback and the exception passes on`() {
        val delegate = RecordingStore().apply {
            current = AppSettings(serverUrl = "https://old.test")
            failSave = true
        }
        var callbackCount = 0
        val store = ProbeRefreshingSettingsStore(delegate) { callbackCount++ }

        try {
            store.save(AppSettings(serverUrl = "https://new.test"))
            fail("expected the save failure to propagate")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals("save must have been attempted", 1, delegate.saveCount)
        assertEquals("callback must not fire when save throws", 0, callbackCount)
    }

    @Test
    fun `load passes through to the delegate`() {
        val delegate = RecordingStore().apply {
            current = AppSettings(serverUrl = "https://load.test")
        }
        val store = ProbeRefreshingSettingsStore(delegate) {}

        val loaded = store.load()

        assertEquals("load must return the delegate's settings", "https://load.test", loaded.serverUrl)
        assertEquals("load must be delegated", 1, delegate.loadCount)
    }

    @Test
    fun `callback runs after save completes`() {
        val delegate = RecordingStore().apply {
            current = AppSettings(serverUrl = "https://old.test")
        }
        var saveBeforeCallback = false
        val store = ProbeRefreshingSettingsStore(delegate) {
            saveBeforeCallback = delegate.saveCount == 1
        }

        store.save(AppSettings(serverUrl = "https://new.test"))

        assertTrue("callback must run after save has completed", saveBeforeCallback)
    }
}
