package dev.breaker.dictation.wiring

import dev.breaker.dictation.host.redrawOnDelete
import dev.breaker.dictation.stt.ondevice.LocalModelStore
import dev.breaker.dictation.ui.DownloadControl
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The answer to a model delete as the settings view gets it: the real ModelDeletion and StoreModelDelete run on the
 * fake background and main queue, and the answer goes to the redrawOnDelete hook over a counting fake DownloadControl.
 * A finished delete, and a delete refused because the model is in use or its install runs, each draw the control
 * once. TileHost and the activity cannot run on a JVM; the listener's own wiring is pinned by
 * DeleteListenerLifecycleGateTest. Nothing here has been compiled or run: every expectation is a hand trace.
 */
internal class DeleteRedrawWiringTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val background = ModelQueueBackground()
    private val main = ModelQueueMain()

    private class CountingControl : DownloadControl {
        var refreshes: Int = 0

        override fun refresh() {
            refreshes += 1
        }

        override fun focusDownload(): Boolean = false
    }

    private fun store(vararg ids: String): LocalModelStore {
        val root: File = folder.newFolder("models")
        for (id in ids) File(root, id).mkdirs()
        return LocalModelStore(root)
    }

    private fun folderOf(id: String): File = File(folder.root, "models/$id")

    private fun deletion(store: LocalModelStore, held: String, downloading: String? = null): ModelDeletion =
        ModelDeletion(StoreModelDelete(store, { held }, { downloading }), background, main)

    private fun drain() {
        background.runAll()
        main.runAll()
    }

    @Test
    fun `a finished delete draws the settings control once and removes the files`() {
        val control = CountingControl()
        deletion(store("parakeet"), held = "small").request("parakeet", redrawOnDelete { control })
        assertEquals("app: nothing is drawn before the delete runs", 0, control.refreshes)
        drain()
        assertFalse("app: the finished delete removed the model", folderOf("parakeet").exists())
        assertEquals("app: a finished delete draws the settings control once", 1, control.refreshes)
    }

    @Test
    fun `a delete refused because the model is in use draws the settings control once and keeps the files`() {
        val control = CountingControl()
        deletion(store("parakeet"), held = "parakeet").request("parakeet", redrawOnDelete { control })
        drain()
        assertTrue("app: the model in use is kept", folderOf("parakeet").isDirectory)
        assertEquals("app: a refused delete draws the settings control once, with no change", 1, control.refreshes)
    }

    @Test
    fun `a delete refused because the install is running draws the settings control once and keeps the files`() {
        val control = CountingControl()
        deletion(store("parakeet"), held = "small", downloading = "parakeet").request("parakeet", redrawOnDelete { control })
        drain()
        assertTrue("app: a model being installed is kept", folderOf("parakeet").isDirectory)
        assertEquals("app: a refused delete during an install draws the settings control once", 1, control.refreshes)
    }
}
