package dev.breaker.server.syncapi.transcriptions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// FeedCursor.parse accepts exactly today's two-part incremental cursor and the new three-part
// listing cursor, and refuses every other shape. Each refusal has an accept control in the same
// test. Test names: plain English in backticks, none of . ; [ ] / < > : \ ,, no underscore, no
// parentheses, no test prefix.
internal class FeedCursorTest {

    @Test
    fun `an incremental cursor parses and renders back`() {
        val cursor = FeedCursor.parse("7-42")!!
        assertTrue("incremental accepted", cursor is FeedCursor.Incremental)
        assertEquals("incremental account", 7L, (cursor as FeedCursor.Incremental).accountId)
        assertEquals("incremental change", 42L, (cursor as FeedCursor.Incremental).change)
        assertEquals("incremental render", "7-42", cursor.render())
    }

    @Test
    fun `a listing cursor parses and renders back`() {
        val cursor = FeedCursor.parse("1-100-103")!!
        assertTrue("listing accepted", cursor is FeedCursor.Listing)
        val listing = cursor as FeedCursor.Listing
        assertEquals("listing account", 1L, listing.accountId)
        assertEquals("listing position", 100L, listing.position)
        assertEquals("listing head", 103L, listing.head)
        assertEquals("listing render", "1-100-103", cursor.render())
    }

    @Test
    fun `position zero is refused`() {
        assertNull("position zero refused", FeedCursor.parse("1-0-103"))
    }

    @Test
    fun `position equal to head is refused`() {
        assertNull("position equal head refused", FeedCursor.parse("1-103-103"))
    }

    @Test
    fun `position above head is refused`() {
        assertNull("position above head refused", FeedCursor.parse("1-104-103"))
        // Accept control: a valid LIST cursor with position below head still parses.
        assertNotNull("position below head accepted", FeedCursor.parse("1-102-103"))
    }

    @Test
    fun `four parts are refused`() {
        assertNull("four parts refused", FeedCursor.parse("1-100-103-7"))
    }

    @Test
    fun `an empty part is refused`() {
        assertNull("empty part refused", FeedCursor.parse("7--300"))
    }

    @Test
    fun `a trailing dash is refused`() {
        assertNull("trailing dash refused", FeedCursor.parse("1-100-"))
    }

    @Test
    fun `19 nines above Long is refused`() {
        val aboveLong = "1-" + "9".repeat(19)   // passes the regex but does not fit a Long
        assertNull("19 nines refused", FeedCursor.parse(aboveLong))
    }

    @Test
    fun `a leading zero in the account part is refused`() {
        assertNull("account leading zero refused", FeedCursor.parse("01-100"))
        assertNull("account leading zero listing refused", FeedCursor.parse("01-100-103"))
    }

    @Test
    fun `a part above Long is refused`() {
        val aboveLong = "1-" + "9".repeat(20)   // 20 nines does not fit a Long
        assertNull("part above Long refused", FeedCursor.parse(aboveLong))
    }

    @Test
    fun `letters are refused`() {
        assertNull("letters refused", FeedCursor.parse("abc-42"))
        assertNull("letters listing refused", FeedCursor.parse("1-xx-103"))
    }

    @Test
    fun `a listing position of one is accepted`() {
        val cursor = FeedCursor.parse("1-1-103")!!
        assertTrue("position one accepted", cursor is FeedCursor.Listing)
    }

    @Test
    fun `zero account is accepted`() {
        val cursor = FeedCursor.parse("0-5")!!
        assertTrue("zero account accepted", cursor is FeedCursor.Incremental)
    }

    @Test
    fun `the other parts may have leading zeros`() {
        val cursor = FeedCursor.parse("1-007-103")!!
        assertTrue("leading zero position accepted", cursor is FeedCursor.Listing)
        assertEquals("leading zero position value", 7L, (cursor as FeedCursor.Listing).position)
    }
}
