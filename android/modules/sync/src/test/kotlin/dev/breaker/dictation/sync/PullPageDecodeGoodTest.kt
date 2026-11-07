package dev.breaker.dictation.sync

import dev.breaker.shared.api.TranscriptionPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Accept-path tests: every field read back correctly, controls beside them. */
class PullPageDecodeGoodTest {

    private fun clientId(n: Int): String =
        "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + n.toString().padStart(12, '0')

    private val c1 = clientId(1)
    private val c2 = clientId(2)
    private val c3 = clientId(3)

    private val liveOne =
        """{"client_id":"$c1","change":1,"deleted":false,"key_version":0,"encrypted":"x","stored_at":"t"}"""

    private val deleteOne =
        """{"client_id":"$c2","change":2,"deleted":true,"stored_at":"t"}"""

    private fun pageOf(vararg items: String): String =
        """{"items":[${items.joinToString(",")}],"next":"any-string","more":true}"""

    @Test
    fun `a live record comes back with every field read back`() {
        val page = PullPageDecoder.decode(pageOf(liveOne))
        assertEquals("one record", 1, page.items.size)
        val record = page.items[0]
        assertEquals("client id", c1, record.clientId)
        assertEquals("change", 1L, record.change)
        assertFalse("not deleted", record.deleted)
        assertEquals("key version", 0, record.keyVersion)
        assertEquals("encrypted", "x", record.encrypted)
        assertEquals("stored at", "t", record.storedAt)
        assertTrue("more is true", page.more)
        assertEquals("next unchanged", "any-string", page.next)
    }

    @Test
    fun `a delete record comes back with null key version and null encrypted`() {
        val page = PullPageDecoder.decode(pageOf(deleteOne))
        assertEquals("one record", 1, page.items.size)
        val record = page.items[0]
        assertTrue("deleted", record.deleted)
        assertNull("no key version", record.keyVersion)
        assertNull("no encrypted", record.encrypted)
        assertEquals("change", 2L, record.change)
    }

    @Test
    fun `an empty page with more true is valid`() {
        val page = PullPageDecoder.decode("""{"items":[],"next":"any-string","more":true}""")
        assertEquals("no records", 0, page.items.size)
        assertTrue("more", page.more)
    }

    @Test
    fun `order is kept over three records`() {
        val r1 =
            """{"client_id":"$c1","change":1,"deleted":false,
             |"key_version":0,"encrypted":"x","stored_at":"t1"}""".trimMargin()
        val r2 = """{"client_id":"$c2","change":2,"deleted":true,"stored_at":"t2"}"""
        val r3 =
            """{"client_id":"$c3","change":3,"deleted":false,
             |"key_version":5,"encrypted":"y","stored_at":"t3"}""".trimMargin()
        val page = PullPageDecoder.decode(pageOf(r1, r2, r3))
        assertEquals("three records", 3, page.items.size)
        assertEquals("first client id", c1, page.items[0].clientId)
        assertEquals("second client id", c2, page.items[1].clientId)
        assertEquals("third client id", c3, page.items[2].clientId)
        assertEquals("change order", listOf(1L, 2L, 3L), page.items.map { it.change })
    }

    @Test
    fun `next is returned unchanged when it is not in cursor form`() {
        val page = PullPageDecoder.decode("""{"items":[],"next":"not-a-cursor","more":false}""")
        assertEquals("next passed through", "not-a-cursor", page.next)
        assertFalse("more is false", page.more)
    }

    @Test
    fun `live record with empty encrypted is valid`() {
        val live =
            """{"client_id":"$c1","change":1,"deleted":false,
             |"key_version":0,"encrypted":"","stored_at":"t"}""".trimMargin()
        val page = PullPageDecoder.decode(pageOf(live))
        assertEquals("control decodes", 1, page.items.size)
        assertEquals("control encrypted", "", page.items[0].encrypted)
    }
}
