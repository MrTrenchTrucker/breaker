package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMappingExtraTest {

    @Test
    fun `tampered with the model deleted says it was deleted`() {
        for (result in listOf(ErrorMapping.tampered("tiny"), ErrorMapping.tampered("tiny", false))) {
            assertEquals("tampered must be a LOCAL_MODEL_MISSING failure", SttError.LOCAL_MODEL_MISSING, result.error)
            val detail = result.detail!!
            assertTrue("detail must say the model was deleted: $detail", detail.contains("was deleted"))
            assertTrue("detail must not say the delete failed: $detail", !detail.contains("could not be deleted"))
        }
    }

    @Test
    fun `tampered with the delete failed says it could not be deleted`() {
        val result = ErrorMapping.tampered("tiny", true)
        assertEquals("tampered must be a LOCAL_MODEL_MISSING failure", SttError.LOCAL_MODEL_MISSING, result.error)
        val detail = result.detail!!
        assertTrue("detail must say the delete failed: $detail", detail.contains("could not be deleted"))
        assertTrue("detail must not say the model was deleted: $detail", !detail.contains("was deleted"))
    }

    @Test
    fun `tampered names the model in both forms`() {
        assertTrue("deleted form must name the model", ErrorMapping.tampered("tiny", false).detail!!.contains("tiny"))
        assertTrue("delete-failed form must name the model", ErrorMapping.tampered("tiny", true).detail!!.contains("tiny"))
    }

    @Test
    fun `modelUnreadable is an OTHER failure with a fixed sentence`() {
        val result = ErrorMapping.modelUnreadable()
        assertEquals("modelUnreadable must be an OTHER failure", SttError.OTHER, result.error)
        assertEquals("modelUnreadable sentence", "The on-device model could not be read.", result.detail)
    }
}
