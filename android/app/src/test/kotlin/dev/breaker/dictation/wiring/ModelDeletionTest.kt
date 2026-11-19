package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.stt.ondevice.LocalModelStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the app port of the delete and the use, beyond what the screen sends: the model in use, a model whose
 * install runs, and a model that is not installed. A delete runs on the background and is answered on the main
 * queue; a use of a model that is not installed changes nothing. The background and the main queue are run by hand.
 */
internal class ModelDeletionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val background = ModelQueueBackground()
    private val main = ModelQueueMain()
    private val answers: MutableList<Boolean> = ArrayList()

    private fun store(vararg ids: String): LocalModelStore {
        val root: File = folder.newFolder("models")
        for (id in ids) File(root, id).mkdirs()
        return LocalModelStore(root)
    }

    private fun folderOf(id: String): File = File(folder.root, "models/$id")

    private fun deletion(store: LocalModelStore, held: String = "small", downloading: String? = null): ModelDeletion =
        ModelDeletion(StoreModelDelete(store, { held }, { downloading }), background, main)

    private fun drain() {
        background.runAll()
        main.runAll()
    }

    @Test
    fun `a delete of the model in use is refused even when the screen sent it`() {
        deletion(store("small"), held = "small").request("small") { answers.add(it) }
        drain()
        assertTrue("app: the model in use is kept", folderOf("small").isDirectory)
        assertEquals("app: the refusal is answered as false", listOf(false), answers)
    }

    @Test
    fun `a delete of a model whose install is running is refused`() {
        deletion(store("parakeet"), held = "small", downloading = "parakeet").request("parakeet") { answers.add(it) }
        drain()
        assertTrue("app: a model being installed is kept", folderOf("parakeet").isDirectory)
        assertEquals("app: the refusal is answered as false", listOf(false), answers)
    }

    @Test
    fun `a delete of a model that is not installed is a quiet no-op`() {
        deletion(store("small")).request("tiny") { answers.add(it) }
        drain()
        assertEquals("app: nothing to remove is answered as false, with no crash", listOf(false), answers)
        assertTrue("app: the model in use is untouched", folderOf("small").isDirectory)
    }

    @Test
    fun `the delete does not run on the caller until the background drains`() {
        deletion(store("parakeet")).request("parakeet") { answers.add(it) }
        assertTrue("app: nothing is removed before the background runs", folderOf("parakeet").isDirectory)
        assertTrue("app: nothing is answered before the background runs", answers.isEmpty())
        background.runAll()
        assertFalse("app: the folder is gone once the background has run", folderOf("parakeet").exists())
        assertTrue("app: the answer waits for the main queue", answers.isEmpty())
        main.runAll()
        assertEquals("app: the answer comes back on the main queue", listOf(true), answers)
    }

    @Test
    fun `a use of a model that is not installed is refused and the model size is unchanged`() {
        val settings = FakeSettings()
        val accepted = ModelSelectWriter(settings) { false }.select("parakeet")
        assertFalse("app: a use of a model that is not installed is refused", accepted)
        assertEquals("app: the model size is unchanged", "small", settings.load().modelSize)
        assertTrue("app: nothing was saved", settings.saves.isEmpty())
    }

    private class FakeSettings : SettingsStore {
        var stored: AppSettings = AppSettings()
        val saves: MutableList<String> = ArrayList()

        override fun load(): AppSettings = stored

        override fun save(settings: AppSettings) {
            saves.add(settings.modelSize)
            stored = settings
        }
    }
}
