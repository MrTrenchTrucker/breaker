package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.LocalModelStore
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pins the delete refusals: the model in use and the model downloading are never removed. */
internal class ModelDeleteRefusalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun storeWith(vararg ids: String): LocalModelStore {
        val root: File = folder.newFolder("models")
        for (id in ids) File(root, id).mkdirs()
        return LocalModelStore(root)
    }

    private fun folderOf(id: String): File = File(folder.root, "models/$id")

    @Test
    fun `delete refuses the model in use and keeps its folder`() {
        val port = StoreModelDelete(storeWith("small"), { "small" }, { null })
        assertFalse("app: the model in use must not be deleted", port.delete("small"))
        assertTrue("app: the folder of the model in use stays", folderOf("small").isDirectory)
    }

    @Test
    fun `delete refuses the model downloading now and keeps its folder`() {
        val port = StoreModelDelete(storeWith("parakeet"), { "small" }, { "parakeet" })
        assertFalse("app: a downloading model must not be deleted", port.delete("parakeet"))
        assertTrue("app: the folder of a downloading model stays", folderOf("parakeet").isDirectory)
    }

    @Test
    fun `delete removes an installed model that is neither in use nor downloading`() {
        val port = StoreModelDelete(storeWith("small", "parakeet"), { "small" }, { null })
        assertTrue("app: a model that is not in use is deleted", port.delete("parakeet"))
        assertFalse("app: the deleted folder is gone", folderOf("parakeet").exists())
        assertTrue("app: the model in use is untouched", folderOf("small").isDirectory)
    }

    @Test
    fun `delete answers false for an id that has no folder`() {
        val port = StoreModelDelete(storeWith(), { "small" }, { null })
        assertFalse("app: a missing model is not deleted", port.delete("tiny"))
    }
}
