package dev.breaker.dictation.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HttpRefusalTest {

    /**
     * The caller order the module asks for: the refusal handler comes first, and the
     * connection-problem handler second. Takes a Throwable so a wrong superclass fails
     * at run time by name, not at compile time.
     */
    private fun route(thrown: Throwable): String =
        try {
            throw thrown
        } catch (refusal: HttpRefusal) {
            "refusal"
        } catch (lost: IOException) {
            "lost connection"
        }

    /** The order the module warns against: the connection-problem handler first, which swallows a refusal. */
    private fun routeIoFirst(thrown: Throwable): String =
        try {
            throw thrown
        } catch (lost: IOException) {
            "lost connection"
        } catch (refusal: HttpRefusal) {
            "refusal"
        }

    @Test
    fun `a refusal is an IOException`() {
        val refusal: Any = HttpRefusal(HttpRefusalReason.NOT_HTTPS)
        assertTrue("a refusal is not an IOException", refusal is IOException)
    }

    @Test
    fun `a refusal has no cause and keeps its reason`() {
        val refusal = HttpRefusal(HttpRefusalReason.LENGTH_MISMATCH)
        assertNull("a refusal carries a cause", refusal.cause)
        assertEquals(HttpRefusalReason.LENGTH_MISMATCH, refusal.reason)
    }

    @Test
    fun `the enum holds the four refusal reasons in order`() {
        assertEquals(
            listOf("NOT_HTTPS", "BAD_HEADER", "REPLY_TOO_LARGE", "LENGTH_MISMATCH"),
            HttpRefusalReason.entries.map { it.name },
        )
    }

    @Test
    fun `each reason has its own fixed sentence and the sentence is the same on every refusal`() {
        val sentences = HttpRefusalReason.entries.map { HttpRefusal(it).message.orEmpty() }
        assertTrue("a sentence is empty", sentences.all { it.isNotEmpty() })
        assertEquals("two reasons share a sentence", sentences.size, sentences.toSet().size)
        assertEquals("a sentence changes between refusals", sentences, HttpRefusalReason.entries.map { HttpRefusal(it).message.orEmpty() })
        assertTrue("a sentence holds a digit", sentences.none { sentence -> sentence.any { it.isDigit() } })
    }

    @Test
    fun `catching HttpRefusal first routes a refusal to it and a plain IOException to the second handler`() {
        assertEquals("refusal", route(HttpRefusal(HttpRefusalReason.REPLY_TOO_LARGE)))
        assertEquals("lost connection", route(IOException("connection reset")))
        assertEquals(
            "an IOException handler placed first swallows a refusal, so the order matters",
            "lost connection",
            routeIoFirst(HttpRefusal(HttpRefusalReason.REPLY_TOO_LARGE)),
        )
    }
}
