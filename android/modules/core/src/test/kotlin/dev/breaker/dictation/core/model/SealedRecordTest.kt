package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The sealed record: a key version of at least 1 and a text that is not blank. */
class SealedRecordTest {

    private fun refusedMessage(what: String, block: () -> Unit): String {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return expected.message.orEmpty()
        }
        fail("$what was accepted but must be refused with IllegalArgumentException")
        return ""
    }

    @Test
    fun `key version zero is refused`() {
        val message = refusedMessage("key version 0") { SealedRecord(0, "frame") }
        assertEquals("A sealed record needs a key version of at least 1", message)
    }

    @Test
    fun `a negative key version is refused`() {
        val message = refusedMessage("key version -1") { SealedRecord(-1, "frame") }
        assertEquals("A sealed record needs a key version of at least 1", message)
    }

    @Test
    fun `a blank text is refused`() {
        val empty = refusedMessage("an empty text") { SealedRecord(1, "") }
        val spaces = refusedMessage("a spaces text") { SealedRecord(1, "   ") }
        assertEquals("A sealed record cannot have a blank text", empty)
        assertEquals("A sealed record cannot have a blank text", spaces)
    }

    @Test
    fun `a good record keeps its two fields`() {
        val record = SealedRecord(3, "AbCd1234")
        assertEquals(3, record.keyVersion)
        assertEquals("AbCd1234", record.encrypted)
        assertEquals(1, SealedRecord(1, "x").keyVersion)
    }

    @Test
    fun `the text shows the key version and the length and no part of the frame`() {
        val frame = "zq9Xmarker7Kp"
        val text = SealedRecord(2, frame).toString()
        assertEquals("SealedRecord(keyVersion=2, 13 chars)", text)
        assertFalse("the text shows the frame", text.contains(frame))
        assertFalse("the text shows a part of the frame", text.contains("marker"))
        assertFalse("the text shows an array or object identity", text.contains("@"))
    }

    @Test
    fun `a sealed record is not a data class`() {
        val names = SealedRecord::class.java.declaredMethods.map { it.name }
        val message = "SealedRecord gained generated copy or component functions"
        assertFalse(message, names.contains("copy"))
        assertTrue(message, names.none { it.startsWith("component") })
    }
}
