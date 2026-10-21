package dev.breaker.shared.api.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

private const val RB = """{"v":1,"text":"hi","source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}"""

private const val CREATED_MS = 1792584000000L

class SyncBodyEncodeTest {

    private fun body(
        text: String = "hi",
        model: String = "m",
        durationMs: Long = 1L,
        createdAtMs: Long = CREATED_MS,
    ): SyncBody = SyncBody(text, SyncSource.LOCAL, model, durationMs, createdAtMs)

    private fun encoded(b: SyncBody): String = String(SyncBodyCodec.encodeBody(b), Charsets.UTF_8)

    private fun assertRefused(name: String, family: String, block: () -> Unit) {
        try {
            block()
            fail("no refusal of " + name)
        } catch (e: SyncBodyException) {
            assertEquals("vector " + name, "sync body refused: " + family, e.message)
        }
    }

    @Test
    fun `encodes a quote as backslash quote`() {
        val expected = """{"v":1,"text":"say \"hi\"","source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}"""
        assertEquals("quote", expected, encoded(body(text = "say \"hi\"")))
    }

    @Test
    fun `encodes a backslash as two backslashes`() {
        val expected = """{"v":1,"text":"a\\b","source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}"""
        assertEquals("backslash", expected, encoded(body(text = "a\\b")))
    }

    @Test
    fun `encodes backspace and form feed with short escapes`() {
        val expected = """{"v":1,"text":"\b\f","source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}"""
        assertEquals("backspace and form feed", expected, encoded(body(text = "\u0008\u000C")))
    }

    @Test
    fun `encodes line feed carriage return and tab with short escapes`() {
        val expected = """{"v":1,"text":"\n\r\t","source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}"""
        assertEquals("line feed, carriage return, tab", expected, encoded(body(text = "\n\r\t")))
    }

    @Test
    fun `encodes other control characters as lowercase unicode escapes`() {
        val expected = """{"v":1,"text":"\u0000\u001f","source":"local","model":"m","duration_ms":1,"created_at":"2026-10-21T12:00:00.000Z"}"""
        assertEquals("other controls", expected, encoded(body(text = "\u0000\u001F")))
    }

    @Test
    fun `writes the body fields in the fixed key order`() {
        assertEquals("fixed key order", RB, encoded(body()))
    }

    @Test
    fun `writes DEL and the line separator and four byte characters without escapes`() {
        val expected = "{\"v\":1,\"text\":\"\u007F\u2028\uD83D\uDE00\",\"source\":\"local\",\"model\":\"m\"," +
            "\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}"
        assertEquals("raw DEL, line separator, emoji", expected, encoded(body(text = "\u007F\u2028\uD83D\uDE00")))
    }

    @Test
    fun `refuses a lone high surrogate in text on encode`() {
        assertRefused("lone high surrogate in text", "surrogate") {
            SyncBodyCodec.encodeBody(body(text = "\uD83D"))
        }
    }

    @Test
    fun `refuses a lone low surrogate in model on encode`() {
        assertRefused("lone low surrogate in model", "surrogate") {
            SyncBodyCodec.encodeBody(body(model = "\uDC00"))
        }
    }

    @Test
    fun `refuses a negative duration on encode`() {
        assertRefused("duration -1", "duration") {
            SyncBodyCodec.encodeBody(body(durationMs = -1L))
        }
    }

    @Test
    fun `refuses a duration above the largest safe integer on encode`() {
        assertRefused("duration 9007199254740992", "duration") {
            SyncBodyCodec.encodeBody(body(durationMs = 9007199254740992L))
        }
    }

    @Test
    fun `refuses a created at before the epoch on encode`() {
        assertRefused("created_at -1", "created_at") {
            SyncBodyCodec.encodeBody(body(createdAtMs = -1L))
        }
    }
}
