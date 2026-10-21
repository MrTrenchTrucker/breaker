package dev.breaker.shared.api.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncBodyVectorsTest {

    private fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun decodeDoc(doc: String): SyncBody = SyncBodyCodec.decodeBody(bytesOf(doc))

    private fun r1Of(body: SyncBody): String = String(SyncBodyCodec.encodeBody(body), Charsets.UTF_8)

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

    @Test
    fun `encodes every positive body vector to its expected R1 text and base64`() {
        for (v in SyncBodyVectorData.okBody) {
            val body = decodeDoc(v.input)
            assertEquals("R1 text of " + v.name, v.r1, r1Of(body))
            assertEquals("base64 of " + v.name, v.base64, SyncBodyCodec.encodePlain(body))
        }
        for (v in SyncBodyVectorData.okBodyBytes) {
            val body = SyncBodyCodec.decodeBody(hexBytes(v.hex))
            assertEquals("R1 text of " + v.name, v.r1, r1Of(body))
            assertEquals("base64 of " + v.name, v.base64, SyncBodyCodec.encodePlain(body))
        }
    }

    @Test
    fun `decodes every positive body vector and encodes it back to its expected R1 text`() {
        for (v in SyncBodyVectorData.okBody) {
            assertEquals("re-encoded R1 of " + v.name, v.r1, r1Of(decodeDoc(v.input)))
        }
    }

    @Test
    fun `decodes the browser style body with other key order whitespace and escapes`() {
        val browser = " {\r\n\"text\" : \"caf\u00e9 \\/ ok\",\t\"v\": 1, \"source\":\"server\", " +
            "\"model\":\"whisper-large-v3\", \"created_at\":\"2026-10-11T12:00:00.000Z\", " +
            "\"duration_ms\": 1500\r\n}\n"
        val expected = SyncBody(
            text = "caf\u00e9 / ok",
            source = SyncSource.SERVER,
            model = "whisper-large-v3",
            durationMs = 1500L,
            createdAtMs = 1791720000000L,
        )
        assertEquals("browser style body", expected, decodeDoc(browser))
    }

    @Test
    fun `decodes every positive plain vector to its expected body`() {
        for (v in SyncBodyVectorData.okPlain) {
            assertEquals("plain " + v.name, v.r1, r1Of(SyncBodyCodec.decodePlain(v.input)))
        }
    }

    @Test
    fun `has positive vectors with zero one and two equals signs`() {
        var zero = 0
        var one = 0
        var two = 0
        for (v in SyncBodyVectorData.okBody) {
            val pads = v.base64.length - v.base64.trimEnd('=').length
            if (pads == 0) {
                zero++
            } else if (pads == 1) {
                one++
            } else if (pads == 2) {
                two++
            }
        }
        assertTrue("no positive vector without padding", zero > 0)
        assertTrue("no positive vector with one equals sign", one > 0)
        assertTrue("no positive vector with two equals signs", two > 0)
    }

    @Test
    fun `decodes a surrogate pair escape to one four byte character`() {
        val doc = """{"v":1,"text":"\ud83d\ude00","source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}"""
        val text = decodeDoc(doc).text
        assertEquals("two UTF-16 units", 2, text.length)
        assertEquals("U+1F600", 0x1F600, text.codePointAt(0))
    }
}
