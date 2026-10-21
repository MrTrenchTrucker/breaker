package dev.breaker.shared.api.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

private const val RB = """{"v":1,"text":"hi","source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}"""

class SyncBodyRefusalTest {

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

    private fun assertRefused(name: String, family: String, block: () -> Unit) {
        try {
            block()
            fail("no refusal of " + name)
        } catch (e: SyncBodyException) {
            assertEquals("vector " + name, "sync body refused: " + family, e.message)
        }
    }

    private fun refuseDoc(name: String, family: String, doc: String) {
        assertRefused(name, family) { SyncBodyCodec.decodeBody(bytesOf(doc)) }
    }

    @Test
    fun `refuses every negative body vector with the family its name gives`() {
        for (v in SyncBodyVectorData.refuseBytes) {
            assertRefused(v.name, v.family) { SyncBodyCodec.decodeBody(hexBytes(v.hex)) }
        }
        for (v in SyncBodyVectorData.refuseText) {
            refuseDoc(v.name, v.family, v.input)
        }
    }

    @Test
    fun `refuses a duplicate key instead of keeping the last value`() {
        refuseDoc("two text members", "duplicate_key", RB.replace("\"source\"", "\"text\":\"hi\",\"source\""))
    }

    @Test
    fun `refuses an unknown key in a version one body`() {
        refuseDoc("extra member", "unknown_key", RB.replace("\"source\"", "\"extra\":1,\"source\""))
    }

    @Test
    fun `refuses a version that is the string one`() {
        refuseDoc("v as string", "version", RB.replace("\"v\":1", "\"v\":\"1\""))
    }

    @Test
    fun `refuses a version written as one point zero`() {
        refuseDoc("v as 1.0", "version", RB.replace("\"v\":1", "\"v\":1.0"))
    }

    @Test
    fun `refuses each of the six keys when it is missing`() {
        val pieces = listOf(
            "\"v\":1,",
            "\"text\":\"hi\",",
            "\"source\":\"local\",",
            "\"model\":\"m\",",
            "\"duration_ms\":1,",
            ",\"created_at\":\"2026-10-21T12:00:00.000Z\"",
        )
        for (piece in pieces) {
            refuseDoc("without " + piece, "missing_key", RB.replace(piece, ""))
        }
    }

    @Test
    fun `refuses a byte order mark before a valid body`() {
        val bom = hexBytes("efbbbf") + bytesOf(RB)
        assertRefused("byte order mark", "bom") { SyncBodyCodec.decodeBody(bom) }
    }

    @Test
    fun `refuses invalid UTF-8 bytes instead of replacing them`() {
        val raw = bytesOf(RB.replace("\"hi\"", "\"hXi\""))
        raw[raw.indexOf('X'.code.toByte())] = 0xFF.toByte()
        assertRefused("byte FF in a string", "utf8") { SyncBodyCodec.decodeBody(raw) }
    }

    @Test
    fun `refuses trailing bytes after the closing brace`() {
        refuseDoc("x after the brace", "trailing", RB + "x")
    }

    @Test
    fun `refuses a top level value that is not an object`() {
        for (doc in listOf("[1]", "\"x\"", "1", "null")) {
            refuseDoc("top level " + doc, "not_object", doc)
        }
    }

    @Test
    fun `refuses a duration of 9007199254740992`() {
        refuseDoc("first unsafe integer", "duration", RB.replace("\"duration_ms\":1", "\"duration_ms\":9007199254740992"))
    }

    @Test
    fun `refuses a negative duration on decode`() {
        refuseDoc("duration -1", "duration", RB.replace("\"duration_ms\":1", "\"duration_ms\":-1"))
    }

    @Test
    fun `refuses a duration with a leading zero`() {
        refuseDoc("duration 01", "duration", RB.replace("\"duration_ms\":1", "\"duration_ms\":01"))
    }

    @Test
    fun `refuses a source word that differs in case`() {
        refuseDoc("source Local", "source", RB.replace("\"source\":\"local\"", "\"source\":\"Local\""))
    }

    @Test
    fun `refuses a lone high surrogate escape in text`() {
        refuseDoc("high surrogate escape", "surrogate", RB.replace("\"hi\"", "\"\\ud800\""))
    }

    @Test
    fun `refuses a lone low surrogate escape in text`() {
        refuseDoc("low surrogate escape", "surrogate", RB.replace("\"hi\"", "\"\\udc00\""))
    }

    @Test
    fun `refuses created at without milliseconds in a body`() {
        refuseDoc("created_at without fraction", "created_at", RB.replace(".000Z", "Z"))
    }

    @Test
    fun `refuses a text value that is a number`() {
        refuseDoc("text 5", "type", RB.replace("\"text\":\"hi\"", "\"text\":5"))
    }

    @Test
    fun `refuses a duration value that is a string`() {
        refuseDoc("duration string", "type", RB.replace("\"duration_ms\":1", "\"duration_ms\":\"1\""))
    }

    @Test
    fun `refuses a body cut short before the closing brace`() {
        refuseDoc("no closing brace", "truncated", RB.dropLast(1))
    }

    @Test
    fun `refuses a comma before the closing brace as a syntax error`() {
        refuseDoc("trailing comma", "syntax", RB.dropLast(1) + ",}")
    }
}
