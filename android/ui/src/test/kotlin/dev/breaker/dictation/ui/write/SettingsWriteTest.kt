package dev.breaker.dictation.ui.write

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one write path: load, edit, compare, save, and what each failure is called. */
class SettingsWriteTest {
    private val store = FakeSettingsStore(NONDEFAULT)

    /** A store that throws the given throwable from `load`, `save` or both and counts the calls. */
    private class ThrowingStore(
        private val loadThrows: Throwable?,
        private val saveThrows: Throwable?,
    ) : SettingsStore {
        var loadAttempts = 0
        var saveAttempts = 0

        override fun load(): AppSettings {
            loadAttempts++
            loadThrows?.let { throw it }
            return NONDEFAULT
        }

        override fun save(settings: AppSettings) {
            saveAttempts++
            saveThrows?.let { throw it }
        }
    }

    /** An error that is not an exception, as an out-of-memory condition is not. */
    private class FatalProbe : Error("fatal probe")

    @Test
    fun `a load that throws is FailedToLoad, the edit never runs and nothing is saved`() {
        store.failLoad = true
        var editRan = false
        val result = writeThrough(store) { editRan = true; it.copy(language = "fr") }
        assertEquals(WriteResult.FailedToLoad, result)
        assertTrue("the load was never reached", store.loadAttempts >= 1)
        assertEquals(0, store.saveAttempts)
        assertEquals(false, editRan)
        assertEquals(NONDEFAULT, store.current)
    }

    @Test
    fun `a save that throws is FailedToSave and the stored settings are unchanged`() {
        store.failSave = true
        val result = writeThrough(store) { it.copy(language = "fr") }
        assertEquals(WriteResult.FailedToSave, result)
        assertTrue("the save was never reached", store.saveAttempts >= 1)
        assertEquals(emptyList<AppSettings>(), store.saves)
        assertEquals(NONDEFAULT, store.current)
    }

    @Test
    fun `an edit that refuses with IllegalArgumentException is Refused and nothing is saved`() {
        val result = writeThrough(store) { throw IllegalArgumentException("ZZ-attempt-1") }
        assertEquals(WriteResult.Refused, result)
        assertTrue("the load was never reached", store.loadAttempts >= 1)
        assertEquals(0, store.saveAttempts)
        assertEquals(NONDEFAULT, store.current)
    }

    @Test
    fun `an edit that returns the same settings is Unchanged and save is not called`() {
        val same = writeThrough(store) { it }
        assertEquals(WriteResult.Unchanged(NONDEFAULT), same)
        val equalCopy = writeThrough(store) { it.copy(language = "de") }
        assertEquals(WriteResult.Unchanged(NONDEFAULT), equalCopy)
        assertEquals(2, store.loadAttempts)
        assertEquals(0, store.saveAttempts)
    }

    @Test
    fun `a changed result is saved once with only the edited field changed`() {
        val result = writeThrough(store) { it.copy(language = "fr") }
        val expected = NONDEFAULT.copy(language = "fr")
        assertEquals(WriteResult.Saved(expected), result)
        assertEquals(listOf(expected), store.saves)
        assertEquals(expected, store.current)
        assertEquals(1, store.saveAttempts)
        assertEquals(listOf("save"), store.events)
    }

    @Test
    fun `the edit is given the freshly loaded settings every time`() {
        writeThrough(store) { it.copy(language = "fr") }
        store.replaceStored(store.current.copy(modelSize = "large"))
        var seen: AppSettings? = null
        writeThrough(store) { seen = it; it.copy(preloadModel = true) }
        assertEquals(NONDEFAULT.copy(language = "fr", modelSize = "large"), seen)
        assertEquals(NONDEFAULT.copy(language = "fr", modelSize = "large", preloadModel = true), store.saves.last())
    }

    @Test
    fun `the saved value is the one the edit returned`() {
        val edited = NONDEFAULT.copy(formattingEnabled = true)
        val result = writeThrough(store) { edited }
        assertSame(edited, (result as WriteResult.Saved).settings)
    }

    @Test
    fun `a store that throws IllegalArgumentException is a store failure and not a refused value`() {
        val loadThrows = ThrowingStore(IllegalArgumentException("ZZ-load"), null)
        assertEquals(WriteResult.FailedToLoad, writeThrough(loadThrows) { it.copy(language = "fr") })
        assertTrue(loadThrows.loadAttempts >= 1)
        assertEquals(0, loadThrows.saveAttempts)
        val saveThrows = ThrowingStore(null, IllegalArgumentException("ZZ-save"))
        assertEquals(WriteResult.FailedToSave, writeThrough(saveThrows) { it.copy(language = "fr") })
        assertTrue(saveThrows.saveAttempts >= 1)
    }

    @Test
    fun `an Error from save passes through`() {
        val saveErrors = ThrowingStore(null, FatalProbe())
        assertThrows(FatalProbe::class.java) { writeThrough(saveErrors) { it.copy(language = "fr") } }
        assertTrue("the save was never reached", saveErrors.saveAttempts >= 1)
    }

    @Test
    fun `an Error from load passes through and nothing is saved`() {
        val loadErrors = ThrowingStore(FatalProbe(), null)
        assertThrows(FatalProbe::class.java) { writeThrough(loadErrors) { it.copy(language = "fr") } }
        assertTrue("the load was never reached", loadErrors.loadAttempts >= 1)
        assertEquals(0, loadErrors.saveAttempts)
    }

    @Test
    fun `an edit that throws something other than IllegalArgumentException passes through`() {
        assertThrows(IllegalStateException::class.java) {
            writeThrough(store) { throw IllegalStateException("ZZ-state") }
        }
        assertTrue("the load was never reached", store.loadAttempts >= 1)
        assertEquals(0, store.saveAttempts)
    }
}
