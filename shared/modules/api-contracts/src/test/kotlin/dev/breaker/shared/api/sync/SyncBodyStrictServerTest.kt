package dev.breaker.shared.api.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncBodyStrictServerTest {

    private fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit")
    }

    private fun hexBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((hexDigit(s[2 * i]) shl 4) or hexDigit(s[2 * i + 1])).toByte()
        }
        return out
    }

    private fun refusalOf(name: String, bytes: ByteArray): SyncBodyException {
        try {
            SyncBodyCodec.decodeStrictServerBody(bytes)
        } catch (e: SyncBodyException) {
            return e
        }
        fail("no refusal of " + name)
        throw IllegalStateException("unreachable")
    }

    private fun assertRefusedWith(name: String, family: String, bytes: ByteArray): SyncBodyException {
        val e = refusalOf(name, bytes)
        assertEquals("class of " + name, SyncBodyException::class.java, e.javaClass)
        assertEquals("vector " + name, "sync body refused: " + family, e.message)
        return e
    }

    private fun textVector(name: String): SealedBodyVectorData.RefuseText =
        SealedBodyVectorData.refuseTexts.first { it.name == name }

    @Test
    fun `decodes every ok vector to the fields in the data with source server`() {
        for (v in SealedBodyVectorData.okBodies) {
            val expected = SyncBody(v.text, SyncSource.SERVER, v.model, v.durationMs, v.createdAtMs)
            val actual = SyncBodyCodec.decodeStrictServerBody(bytesOf(v.input))
            assertEquals("ok vector " + v.name, expected, actual)
        }
    }

    @Test
    fun `refuses every refused vector with exactly its family`() {
        for (v in SealedBodyVectorData.refuseTexts) {
            assertRefusedWith(v.name, v.family, bytesOf(v.input))
        }
        for (v in SealedBodyVectorData.refuseBytes) {
            assertRefusedWith(v.name, v.family, hexBytes(v.hex))
        }
    }

    @Test
    fun `refusal messages carry no text of the body`() {
        for (v in SealedBodyVectorData.refuseTexts) {
            val message = assertRefusedWith(v.name, v.family, bytesOf(v.input)).message ?: ""
            if (v.input.length > 3 && v.input != v.family) {
                assertFalse("message of " + v.name + " holds the body", message.contains(v.input))
            }
        }
    }

    @Test
    fun `leaves decodeBody unchanged for source local and for the source first order`() {
        val local = SyncBodyCodec.decodeBody(bytesOf(textVector("refused-source-local").input))
        assertEquals("source local", SyncSource.LOCAL, local.source)
        val order = SyncBodyCodec.decodeBody(bytesOf(textVector("refused-order-source-first").input))
        assertEquals("source first", SyncSource.SERVER, order.source)
    }

    @Test
    fun `checks the key order before the source`() {
        val both = textVector("refused-order-source-first").input.replace("server", "local")
        assertTrue("test body has source local", both.contains("\"source\":\"local\""))
        assertRefusedWith("order and source both wrong", "key_order", bytesOf(both))
    }

    @Test
    fun `uses only refusal families that SyncBodyFamily defines`() {
        val known = setOf(
            SyncBodyFamily.BASE64,
            SyncBodyFamily.UTF8,
            SyncBodyFamily.BOM,
            SyncBodyFamily.NOT_OBJECT,
            SyncBodyFamily.VERSION,
            SyncBodyFamily.MISSING_KEY,
            SyncBodyFamily.DUPLICATE_KEY,
            SyncBodyFamily.UNKNOWN_KEY,
            SyncBodyFamily.TYPE,
            SyncBodyFamily.SOURCE,
            SyncBodyFamily.DURATION,
            SyncBodyFamily.CREATED_AT,
            SyncBodyFamily.SURROGATE,
            SyncBodyFamily.TRUNCATED,
            SyncBodyFamily.TRAILING,
            SyncBodyFamily.CLIENT_ID,
            SyncBodyFamily.SYNTAX,
            SyncBodyFamily.KEY_ORDER,
        )
        val used = SealedBodyVectorData.refuseTexts.map { it.family } +
            SealedBodyVectorData.refuseBytes.map { it.family }
        assertTrue("families not in SyncBodyFamily: " + (used.toSet() - known), known.containsAll(used))
    }
}
