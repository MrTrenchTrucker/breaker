package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryArchiveNamesTest {

    @Test
    fun `every registry entry carries a non-blank archive name`() {
        assertFalse("the registry must list entries to check", ModelRegistry.ALL.isEmpty())
        val blank = ModelRegistry.ALL.filter { it.archiveName.isBlank() }.map { it.id }
        assertTrue("registry entries with a blank archiveName: $blank", blank.isEmpty())
    }
}
