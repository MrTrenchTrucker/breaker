package dev.breaker.dictation.sync

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for PullRequest: the internal description of a pull request. */
class PullRequestTest {

    /** A failure here means the first request carries a since it must not have. */
    @Test
    fun `first has no since and an empty query`() {
        val request = PullRequest.first()
        assertNull("android_sync: first request must have no since", request.since)
        assertEquals("android_sync: first request must have an empty query", "", request.query())
    }

    /** A failure here means a later request drops its since. */
    @Test
    fun `after keeps the cursor text exactly for a cursor shaped text`() {
        val request = PullRequest.after("12-345")
        assertEquals("android_sync: after must keep the cursor text exactly", "12-345", request.since)
        assertEquals(
            "android_sync: query must be since plus the cursor unchanged",
            "since=12-345",
            request.query(),
        )
    }

    /** A failure here means a later request changes the case of the cursor text. */
    @Test
    fun `after keeps upper and lower case letters exactly`() {
        val request = PullRequest.after("AbC-Z9")
        assertEquals("android_sync: after must not change the case of the cursor", "AbC-Z9", request.since)
        assertEquals(
            "android_sync: query must keep upper and lower case unchanged",
            "since=AbC-Z9",
            request.query(),
        )
    }

    /** A failure here means a later request trims the cursor text. */
    @Test
    fun `after keeps leading and trailing spaces exactly`() {
        val request = PullRequest.after(" 12-345 ")
        assertEquals("android_sync: after must not trim leading space", " 12-345 ", request.since)
        val query = request.query()
        assertEquals("android_sync: query keeps leading and trailing spaces", "since=%2012-345%20", query)
    }

    /** A failure here means a later request changes a non ASCII character in the cursor. */
    @Test
    fun `after keeps a non ASCII letter exactly`() {
        val request = PullRequest.after("\u00E9")
        assertEquals("android_sync: after must not change a non ASCII letter", "\u00E9", request.since)
        assertEquals(
            "android_sync: query must percent encode the non ASCII letter",
            "since=%C3%A9",
            request.query(),
        )
    }

    /** A failure here means a cursor that needs no encoding is encoded anyway. */
    @Test
    fun `query for 12-345 is exactly since=12-345`() {
        assertEquals(
            "android_sync: an unreserved cursor must not be encoded",
            "since=12-345",
            PullRequest.after("12-345").query(),
        )
    }

    /** A failure here means an unreserved character is wrongly encoded. */
    @Test
    fun `every unreserved character is left alone`() {
        val text = "A-z-0-9-.-_-~"
        assertEquals(
            "android_sync: unreserved characters must pass through unchanged",
            "since=A-z-0-9-.-_-~",
            PullRequest.after(text).query(),
        )
    }

    /** A failure here means the space encoding is wrong. */
    @Test
    fun `a space encodes as percent 20 not plus`() {
        assertEquals(
            "android_sync: a space must be %20 never +",
            "since=a%20b",
            PullRequest.after("a b").query(),
        )
    }

    /** A failure here means an ampersand is wrongly encoded. */
    @Test
    fun `an ampersand encodes as percent 26`() {
        assertEquals("android_sync: & must be %26", "since=a%26b", PullRequest.after("a&b").query())
    }

    /** A failure here means an equals sign is wrongly encoded. */
    @Test
    fun `an equals sign encodes as percent 3D`() {
        assertEquals("android_sync: = must be %3D", "since=a%3Db", PullRequest.after("a=b").query())
    }

    /** A failure here means a plus is wrongly encoded. */
    @Test
    fun `a plus encodes as percent 2B`() {
        assertEquals("android_sync: + must be %2B", "since=a%2Bb", PullRequest.after("a+b").query())
    }

    /** A failure here means a hash is wrongly encoded. */
    @Test
    fun `a hash encodes as percent 23`() {
        assertEquals("android_sync: # must be %23", "since=a%23b", PullRequest.after("a#b").query())
    }

    /** A failure here means a percent is wrongly encoded. */
    @Test
    fun `a percent encodes as percent 25`() {
        assertEquals("android_sync: % must be %25", "since=a%25b", PullRequest.after("a%b").query())
    }

    /** A failure here means a slash is wrongly encoded. */
    @Test
    fun `a slash encodes as percent 2F`() {
        assertEquals("android_sync: / must be %2F", "since=a%2Fb", PullRequest.after("a/b").query())
    }

    /** A failure here means an emoji is not encoded as its four UTF-8 bytes. */
    @Test
    fun `an emoji encodes as four UTF-8 percent bytes`() {
        val query = PullRequest.after("\uD83D\uDE00").query()
        assertEquals("android_sync: an emoji must be its four UTF-8 bytes", "since=%F0%9F%98%80", query)
    }

    /** A failure here means decoding query() with URLDecoder gives the original text. */
    @Test
    fun `decoding the query with URLDecoder gives the original text`() {
        val texts = listOf(
            "12-345",
            "a b",
            "a&b",
            "a=b",
            "a+b",
            "a#b",
            "a%b",
            "a/b",
            "\u00E9",
            "\uD83D\uDE00",
            " 12-345 ",
        )
        for (text in texts) {
            val encoded = PullRequest.after(text).query()
            val withoutPrefix = encoded.removePrefix("since=")
            val decoded = URLDecoder.decode(withoutPrefix, "UTF-8")
            assertEquals("android_sync: URLDecoder must return the original cursor text", text, decoded)
        }
    }

    /** A failure here means an empty cursor is accepted when it must be refused. */
    @Test
    fun `after with an empty cursor throws`() {
        val failure = thrownBy { PullRequest.after("") }
        assertNotNull("android_sync: an empty cursor must throw", failure)
        assertTrue(
            "android_sync: the refusal must be an IllegalArgumentException",
            failure is IllegalArgumentException,
        )
        if (failure is IllegalArgumentException) {
            assertEquals(
                "android_sync: the refusal message must name the rule",
                "after: an empty cursor is not a cursor",
                failure.message,
            )
        }
    }

    /** A failure here means a one character cursor is rejected when it must be accepted. */
    @Test
    fun `a one character cursor works`() {
        val request = PullRequest.after("x")
        assertEquals("android_sync: a one character cursor must be accepted", "x", request.since)
        assertEquals("android_sync: query for a one character cursor", "since=x", request.query())
    }

    /** A failure here means the path is wrong. */
    @Test
    fun `path is the fixed pull path`() {
        assertEquals(
            "android_sync: path must be the fixed pull path",
            "/v1/transcriptions",
            PullRequest.first().path(),
        )
        assertEquals(
            "android_sync: path is the same for first and after",
            "/v1/transcriptions",
            PullRequest.after("abc").path(),
        )
    }

    /** A failure here means two requests from the same text differ. */
    @Test
    fun `two requests from the same text have the same since and query`() {
        val first = PullRequest.after("hello world")
        val second = PullRequest.after("hello world")
        assertEquals(
            "android_sync: two requests from the same text have the same since",
            first.since,
            second.since,
        )
        assertEquals(
            "android_sync: two requests from the same text have the same query",
            first.query(),
            second.query(),
        )
    }
}
