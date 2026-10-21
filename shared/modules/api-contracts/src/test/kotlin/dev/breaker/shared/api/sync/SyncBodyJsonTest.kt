package dev.breaker.shared.api.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

private const val RB = """{"v":1,"text":"hi","source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}"""

class SyncBodyJsonTest {

    private fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun decodeDoc(doc: String): SyncBody = SyncBodyCodec.decodeBody(bytesOf(doc))

    private fun expectedBody(): SyncBody = SyncBody("hi", SyncSource.LOCAL, "m", 1L, 1792584000000L)

    private fun assertRefused(name: String, family: String, block: () -> Unit) {
        try {
            block()
            fail("no refusal of " + name)
        } catch (e: SyncBodyException) {
            assertEquals("vector " + name, "sync body refused: " + family, e.message)
        }
    }

    @Test
    fun `accepts carriage return and tab between tokens`() {
        val doc = "{\r\n\t\"v\": 1,\r\n\t\"text\": \"hi\",\r\n\t\"source\": \"local\",\r\n" +
            "\t\"model\": \"m\",\r\n\t\"duration_ms\": 1,\r\n\t\"created_at\": \"2026-10-21T12:00:00.000Z\"\r\n}"
        assertEquals("CR LF and TAB between members", expectedBody(), decodeDoc(doc))
    }

    @Test
    fun `accepts an escaped slash and reads it as a plain slash`() {
        val doc = """{"v":1,"text":"a\/b","source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}"""
        assertEquals("escaped slash", "a/b", decodeDoc(doc).text)
    }

    @Test
    fun `refuses a raw control character inside a string`() {
        assertRefused("raw U+0001 in text", "syntax") {
            decodeDoc(RB.replace("\"hi\"", "\"h\u0001i\""))
        }
    }
}
