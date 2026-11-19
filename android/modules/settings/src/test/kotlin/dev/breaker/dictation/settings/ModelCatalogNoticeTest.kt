package dev.breaker.dictation.settings

import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * The notice a model carries in the registry reaches the settings projection unchanged, in all(), offered() and
 * byId(); a model with no notice carries null. Hand-traced only: nothing here has been compiled or run.
 * On the old code ModelInfo has no notice field, so every test here is red for a reason of compilation.
 */
class ModelCatalogNoticeTest {

    @Test
    fun `all carries the registry notice of the parakeet model unchanged`() {
        val parakeet = ModelCatalog.all().single { it.id == "parakeet" }
        assertNotNull("the parakeet model must carry a notice", parakeet.notice)
        assertEquals(ModelRegistry.PARAKEET.notice, parakeet.notice)
    }

    @Test
    fun `all carries no notice for the small model`() {
        assertNull("the small model has no licence notice", ModelCatalog.all().single { it.id == "small" }.notice)
    }

    @Test
    fun `offered carries the parakeet notice too`() {
        assertEquals(ModelRegistry.PARAKEET.notice, ModelCatalog.offered().single { it.id == "parakeet" }.notice)
    }

    @Test
    fun `byId carries the notice of the model it names`() {
        assertEquals(ModelRegistry.PARAKEET.notice, ModelCatalog.byId("parakeet")?.notice)
    }
}
