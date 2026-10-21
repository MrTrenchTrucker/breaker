package dev.breaker.shared.api.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val PADDED = "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0="

class SyncBodyPlainTest {

    private fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun assertRefused(name: String, family: String, block: () -> Unit) {
        try {
            block()
            fail("no refusal of " + name)
        } catch (e: SyncBodyException) {
            assertEquals("vector " + name, "sync body refused: " + family, e.message)
        }
    }

    private fun refusePlain(name: String, family: String, encrypted: String) {
        assertRefused(name, family) { SyncBodyCodec.decodePlain(encrypted) }
    }

    @Test
    fun `refuses every negative plain vector with the family its name gives`() {
        for (v in SyncBodyVectorData.refusePlain) {
            refusePlain(v.name, v.family, v.input)
        }
    }

    @Test
    fun `refuses an empty encrypted string`() {
        refusePlain("empty string", "base64", "")
    }

    @Test
    fun `refuses base64 without padding`() {
        refusePlain("padding removed", "base64", PADDED.trimEnd('='))
    }

    @Test
    fun `refuses base64 with a line break`() {
        refusePlain("line feed after four characters", "base64", PADDED.substring(0, 4) + "\n" + PADDED.substring(4))
    }

    @Test
    fun `refuses base64 with a URL safe character`() {
        refusePlain("hyphen for the first character", "base64", "-" + PADDED.substring(1))
    }

    @Test
    fun `refuses base64 whose unused trailing bits are not zero`() {
        refusePlain("last character changed in its unused bits", "base64", PADDED.dropLast(2) + "1=")
    }

    @Test
    fun `encodes plain bodies as padded base64 on one line`() {
        var longest = 0
        for (v in SyncBodyVectorData.okBody) {
            val encoded = SyncBodyCodec.encodePlain(SyncBodyCodec.decodeBody(bytesOf(v.input)))
            assertEquals("padded base64 of " + v.name, v.base64, encoded)
            assertFalse("no line break in " + v.name, encoded.contains('\n'))
            if (encoded.length > longest) {
                longest = encoded.length
            }
        }
        assertTrue("a body over 76 characters proves there is no wrap", longest > 76)
    }
}
