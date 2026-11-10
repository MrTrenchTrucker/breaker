package dev.breaker.dictation.sync

import dev.breaker.shared.api.TranscriptionPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refusal tests: each bad body has an accept control beside it. */
class PullPageDecoderRefusalsTest {

    private fun clientId(n: Int): String =
        "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + n.toString().padStart(12, '0')

    private val c1 = clientId(1)
    private val c2 = clientId(2)

    private val okLive =
        """{"client_id":"$c1","change":1,"deleted":false,"key_version":0,"encrypted":"x","stored_at":"t"}"""

    private val okDelete =
        """{"client_id":"$c2","change":2,"deleted":true,"stored_at":"t"}"""

    private fun pageOf(vararg items: String): String =
        """{"items":[${items.joinToString(",")}],"next":"any-string","more":true}"""

    private fun checkRefuse(body: String, messageContains: String) {
        val thrown = org.junit.Assert.assertThrows(
            "body must refuse",
            PullPageException::class.java,
        ) { PullPageDecoder.decode(body) }
        assertTrue("message names the rule: $messageContains, got ${thrown.message}",
            thrown.message!!.contains(messageContains))
    }

    private fun checkOk(body: String) {
        PullPageDecoder.decode(body) // nothing thrown IS the control
    }

    @Test
    fun `empty body throws`() {
        checkRefuse("", "one JSON value")
        checkOk("""{"items":[],"next":"x","more":true}""")
    }

    @Test
    fun `truncated body throws`() {
        checkRefuse("""{"items":[""", "is not one JSON value")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `trailing text throws`() {
        checkRefuse("""{"items":[],"next":"x","more":true} junk""", "trailing text")
        checkOk("""{"items":[],"next":"x","more":true}""")
    }

    @Test
    fun `top value that is not an object throws`() {
        checkRefuse("""[1,2,3]""", "not an object")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `missing items throws`() {
        checkRefuse("""{"next":"x","more":true}""", "items is missing")
        checkOk("""{"items":[],"next":"x","more":true}""")
    }

    @Test
    fun `empty next throws`() {
        checkRefuse("""{"items":[],"next":"","more":true}""", "next is empty")
        checkOk("""{"items":[],"next":"x","more":true}""")
    }

    @Test
    fun `missing more throws`() {
        checkRefuse("""{"items":[],"next":"x"}""", "more is missing")
        checkOk("""{"items":[],"next":"x","more":true}""")
    }

    @Test
    fun `wrong type of more throws`() {
        checkRefuse("""{"items":[],"next":"x","more":"yes"}""", "more is not")
        checkOk("""{"items":[],"next":"x","more":true}""")
    }

    @Test
    fun `element that is not an object throws`() {
        checkRefuse("""{"items":["x"],"next":"x","more":true}""", "is not an object")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `client id upper case throws`() {
        val bad =
            """{"client_id":"${c1.uppercase()}", "change":1, "deleted":true, "stored_at":"t"}"""
        checkRefuse(pageOf(bad), "client_id")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `change zero throws`() {
        val bad =
            """{"client_id":"$c1","change":0,"deleted":true,"stored_at":"t"}"""
        checkRefuse(pageOf(bad), "change")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `change fractional throws`() {
        val bad =
            """{"client_id":"$c1","change":1.5,"deleted":true,"stored_at":"t"}"""
        checkRefuse(pageOf(bad), "change")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `change exponent throws`() {
        val bad =
            """{"client_id":"$c1","change":1e2,"deleted":true,"stored_at":"t"}"""
        checkRefuse(pageOf(bad), "change")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `change above Long throws`() {
        val huge = "9".repeat(20)
        val bad =
            """{"client_id":"$c1","change":$huge,"deleted":true,"stored_at":"t"}"""
        val thrown1 = org.junit.Assert.assertThrows(
            "huge change must refuse",
            PullPageException::class.java,
        ) { PullPageDecoder.decode(pageOf(bad)) }
        assertTrue("message names Long", thrown1.message!!.contains("Long"))
        val oneAbove = java.math.BigInteger.valueOf(Long.MAX_VALUE).add(java.math.BigInteger.ONE)
        val bad2 =
            """{"client_id":"$c1","change":$oneAbove,"deleted":true,"stored_at":"t"}"""
        val thrown2 = org.junit.Assert.assertThrows(
            "one above Long max must refuse",
            PullPageException::class.java,
        ) { PullPageDecoder.decode(pageOf(bad2)) }
        assertTrue("message names Long", thrown2.message!!.contains("Long"))
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `change as a string throws`() {
        val bad =
            """{"client_id":"$c1","change":"one","deleted":true,"stored_at":"t"}"""
        checkRefuse(pageOf(bad), "change")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `missing deleted throws`() {
        val bad =
            """{"client_id":"$c1","change":1,"stored_at":"t"}"""
        checkRefuse(pageOf(bad), "deleted")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `deleted as a string throws`() {
        val bad =
            """{"client_id":"$c1","change":1,"deleted":"yes","stored_at":"t"}"""
        checkRefuse(pageOf(bad), "deleted")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `live record without key version throws`() {
        val bad =
            """{"client_id":"$c1","change":1,"deleted":false,"encrypted":"x","stored_at":"t"}"""
        checkRefuse(pageOf(bad), "key_version")
        checkOk(pageOf(okLive))
    }

    @Test
    fun `live record without encrypted throws`() {
        val bad =
            """{"client_id":"$c1","change":1,"deleted":false,"key_version":0,"stored_at":"t"}"""
        checkRefuse(pageOf(bad), "encrypted")
        checkOk(pageOf(okLive))
    }

    @Test
    fun `key version negative throws`() {
        val bad =
            """{"client_id":"$c1","change":1,"deleted":false,"key_version":-1,
             |"encrypted":"x","stored_at":"t"}""".trimMargin()
        checkRefuse(pageOf(bad), "key_version")
        checkOk(pageOf(okLive))
    }

    @Test
    fun `delete record carrying key version throws`() {
        val bad =
            """{"client_id":"$c1","change":1,"deleted":true,"key_version":0,"stored_at":"t"}"""
        checkRefuse(pageOf(bad), "key_version")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `delete record carrying encrypted throws`() {
        val bad =
            """{"client_id":"$c1","change":1,"deleted":true,"encrypted":"x","stored_at":"t"}"""
        checkRefuse(pageOf(bad), "encrypted")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `missing stored at throws`() {
        val bad =
            """{"client_id":"$c1","change":1,"deleted":true}"""
        checkRefuse(pageOf(bad), "stored_at")
        checkOk(pageOf(okDelete))
    }

    @Test
    fun `nesting deeper than 8 throws`() {
        val deep = "[".repeat(9) + "]".repeat(9)
        checkRefuse(deep, "is not one JSON value")
        checkOk("""{"items":[],"next":"x","more":true}""")
    }

    @Test
    fun `privacy test never echoes body text in a message`() {
        val marker = "secret user text that must not leak"
        val bad =
            """{"items":[{"client_id":"$c1","change":1,"deleted":false,
             |"key_version":0,"encrypted":"$marker","stored_at":""}],
             |"next":"x","more":true}""".trimMargin()
        val thrown = org.junit.Assert.assertThrows(
            "bad stored_at must refuse",
            PullPageException::class.java,
        ) { PullPageDecoder.decode(bad) }
        assertFalse("message must not contain marker", thrown.message!!.contains(marker))
        assertFalse("message must not contain secret", thrown.message!!.contains("secret"))
        val ok =
            """{"items":[{"client_id":"$c1","change":1,"deleted":false,
             |"key_version":0,"encrypted":"$marker","stored_at":"t"}],
             |"next":"x","more":true}""".trimMargin()
        val page = PullPageDecoder.decode(ok)
        assertEquals("control decodes", 1, page.items.size)
        assertEquals("control encrypted", marker, page.items[0].encrypted)
    }

    /** A failure here means the fixture text on this side does not decode as the shared page: the
     * delete row at ...123Z and the live row at ...250Z both decode, and PullApplier applies the
     * delete with deletedAt == 1_700_000_000_123L. */
    @Test
    fun `decoding and applying PullPageFixture matches the shared vector`() {
        val page = PullPageDecoder.decode(PullPageFixture.PAGE)
        assertEquals("fixture page has two items", 2, page.items.size)
        assertEquals("fixture next", "1-3", page.next)
        val live = page.items.single { !it.deleted }
        assertEquals("fixture live row client id", "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f", live.clientId)
        assertEquals("fixture live row storedAt", "2023-11-14T22:13:20.250Z", live.storedAt)
        val del = page.items.single { it.deleted }
        assertEquals("fixture delete row storedAt", "2023-11-14T22:13:20.123Z", del.storedAt)
        val sink = RecordingPullSink()
        val report = PullApplier(sink, RecordingQueueProbe()).apply(
            PullRunResult(PullOutcome.Ok(listOf(del), page.next), false))
        assertEquals("applier counts one delete", 1, report.deleted)
        assertEquals("applier delete deletedAt is the fixture fraction",
            1_700_000_000_123L, sink.deletes.single().deletedAt)
    }
}
