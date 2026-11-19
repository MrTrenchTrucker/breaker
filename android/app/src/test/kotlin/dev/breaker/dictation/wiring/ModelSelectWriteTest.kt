package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the writes of the model in use: an installed id that the registry names is saved, and nothing else is. */
internal class ModelSelectWriteTest {

    private class RecordingSettings : SettingsStore {
        var stored: AppSettings = AppSettings()
        val saves: MutableList<String> = ArrayList()

        override fun load(): AppSettings = stored

        override fun save(settings: AppSettings) {
            saves.add(settings.modelSize)
            stored = settings
        }
    }

    @Test
    fun `use of an installed id saves that id as the model size`() {
        val settings = RecordingSettings()
        assertTrue(ModelSelectWriter(settings) { true }.select("parakeet"))
        assertEquals(listOf("parakeet"), settings.saves)
        assertEquals("parakeet", settings.load().modelSize)
    }

    @Test
    fun `use of a model that is not installed is refused and nothing is saved`() {
        val settings = RecordingSettings()
        assertFalse(ModelSelectWriter(settings) { false }.select("parakeet"))
        assertTrue("app: a refused use saves nothing", settings.saves.isEmpty())
        assertEquals("app: the model size is unchanged", "small", settings.load().modelSize)
    }

    @Test
    fun `use of an id the registry does not name is refused even when the store holds it`() {
        val settings = RecordingSettings()
        assertFalse(ModelSelectWriter(settings) { true }.select("not-a-registry-id"))
        assertTrue("app: an unknown id saves nothing", settings.saves.isEmpty())
    }

    @Test
    fun `select after a finished install saves the downloaded id`() {
        val settings = RecordingSettings()
        selectOnDone(ModelSelectWriter(settings) { true })(DownloadOutcome.Done("parakeet"))
        assertEquals(listOf("parakeet"), settings.saves)
    }

    @Test
    fun `select after a failed install or a refused start saves nothing`() {
        val settings = RecordingSettings()
        val onDone = selectOnDone(ModelSelectWriter(settings) { true })
        onDone(DownloadOutcome.Failed("failed"))
        onDone(DownloadOutcome.AlreadyRunning)
        assertTrue("app: no outcome but Done changes the model in use", settings.saves.isEmpty())
    }
}
