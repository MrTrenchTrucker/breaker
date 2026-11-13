package dev.breaker.dictation.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The preload_model key follows the saved value in both directions. The shared fixture saves it as false (the
 * non-default), so a writer that always wrote false passed every other test; this class also saves true, which is
 * the default, and reads it back from the file text and from a new store.
 */
class SettingsPreloadModelTest : SettingsFileStoreTestBase() {

    @Test
    fun `a saved true preload model is written as true and read back as true`() {
        val file = newFile()
        store(file).save(validSettings().copy(preloadModel = true))
        assertEquals("the key in the file", "true", propertyOf(file, KEY_PRELOAD_MODEL))
        assertEquals("the value a new store reads", true, store(file).load().preloadModel)
    }

    @Test
    fun `a saved false preload model is written as false and read back as false`() {
        val file = newFile()
        store(file).save(validSettings().copy(preloadModel = false))
        assertEquals("the key in the file", "false", propertyOf(file, KEY_PRELOAD_MODEL))
        assertEquals("the value a new store reads", false, store(file).load().preloadModel)
    }
}
