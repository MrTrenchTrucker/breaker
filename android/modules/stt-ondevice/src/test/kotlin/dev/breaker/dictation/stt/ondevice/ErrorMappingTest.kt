package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMappingTest {

    @Test
    fun `noModelInstalled returns LOCAL_MODEL_MISSING with model id in detail`() {
        val result = ErrorMapping.noModelInstalled("tiny")
        assertEquals(SttError.LOCAL_MODEL_MISSING, result.error)
        assertNotNull(result.detail)
        assertTrue(result.detail!!.contains("tiny"))
    }

    @Test
    fun `unknownModel returns LOCAL_MODEL_MISSING with model id in detail`() {
        val result = ErrorMapping.unknownModel("unknown-model")
        assertEquals(SttError.LOCAL_MODEL_MISSING, result.error)
        assertNotNull(result.detail)
        assertTrue(result.detail!!.contains("unknown-model"))
    }

    @Test
    fun `tampered returns LOCAL_MODEL_MISSING with model id in detail`() {
        val result = ErrorMapping.tampered("tiny")
        assertEquals(SttError.LOCAL_MODEL_MISSING, result.error)
        assertNotNull(result.detail)
        assertTrue(result.detail!!.contains("tiny"))
    }

    @Test
    fun `wrongFamily returns LOCAL_MODEL_MISSING with model id and family in detail`() {
        val result = ErrorMapping.wrongFamily("base", "WHISPER")
        assertEquals(SttError.LOCAL_MODEL_MISSING, result.error)
        assertNotNull(result.detail)
        assertTrue(result.detail!!.contains("base"))
        assertTrue(result.detail!!.contains("WHISPER"))
    }

    @Test
    fun `checksumsUnreadable returns LOCAL_MODEL_MISSING with model id in detail`() {
        val result = ErrorMapping.checksumsUnreadable("tiny")
        assertEquals(SttError.LOCAL_MODEL_MISSING, result.error)
        assertNotNull(result.detail)
        assertTrue(result.detail!!.contains("tiny"))
    }

    @Test
    fun `audioWrongRate returns OTHER with rate in detail`() {
        val result = ErrorMapping.audioWrongRate(44100)
        assertEquals(SttError.OTHER, result.error)
        assertNotNull(result.detail)
        assertTrue(result.detail!!.contains("44100"))
    }

    @Test
    fun `decodeFailed returns OTHER with non-empty detail`() {
        val result = ErrorMapping.decodeFailed()
        assertEquals(SttError.OTHER, result.error)
        assertNotNull(result.detail)
        assertTrue(result.detail!!.isNotEmpty())
    }

    @Test
    fun `engineClosed returns OTHER with non-empty detail`() {
        val result = ErrorMapping.engineClosed()
        assertEquals(SttError.OTHER, result.error)
        assertNotNull(result.detail)
        assertTrue(result.detail!!.isNotEmpty())
    }

    @Test
    fun `reentrantDecode returns OTHER with non-empty detail`() {
        val result = ErrorMapping.reentrantDecode()
        assertEquals(SttError.OTHER, result.error)
        assertNotNull(result.detail)
        assertTrue(result.detail!!.isNotEmpty())
    }
}
