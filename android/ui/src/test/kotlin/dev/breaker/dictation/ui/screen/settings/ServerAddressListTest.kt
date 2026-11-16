package dev.breaker.dictation.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stored list of server addresses, checked with literal texts only.
 *
 * [ServerAddressEntry.acceptedList] is the list form of [ServerAddressEntry.accepted]:
 * the order is probe priority, so entry 1 is tried first. Every refused list is
 * paired with the control that shows the rule it leans on — a single address the
 * rule accepts — because a list function that refused everything would satisfy
 * the refusal cases on their own.
 */
class ServerAddressListTest {

    private fun kept(vararg entries: String?) = ServerAddressEntry.acceptedList(entries.toList())

    @Test
    fun `a valid list is kept in order`() {
        assertEquals(
            listOf("https://a.example", "http://b.example:8080", "https://c.example/"),
            kept("https://a.example", "http://b.example:8080", "https://c.example/"),
        )
    }

    @Test
    fun `trailing blank entries are dropped, not stored as entries`() {
        assertEquals(listOf("https://a.example"), kept("https://a.example", "", ""))
        assertEquals(listOf("https://a.example"), kept("https://a.example", "  ", null))
    }

    @Test
    fun `a trailing refused entry refuses the whole list`() {
        assertEquals(emptyList<String>(), kept("https://a.example", "ftp://b.example"))
        assertEquals(
            "control: a trailing BLANK is dropped, a trailing refusal is not",
            listOf("https://a.example"),
            kept("https://a.example", ""),
        )
    }

    @Test
    fun `a blank and a refused trailing entry together refuse the whole list`() {
        assertEquals(emptyList<String>(), kept("https://a.example", "", "ftp://b.example"))
        assertEquals(
            "control: the trailing blank alone is dropped",
            listOf("https://a.example"),
            kept("https://a.example", ""),
        )
    }

    @Test
    fun `more than three entries refuses the whole list`() {
        assertEquals(
            "four valid addresses must be refused whole, not truncated to three",
            emptyList<String>(),
            kept(
                "https://a.example",
                "https://b.example",
                "https://c.example",
                "https://d.example",
            ),
        )
        assertEquals(
            "control: three of the same addresses are kept",
            listOf("https://a.example", "https://b.example", "https://c.example"),
            kept("https://a.example", "https://b.example", "https://c.example"),
        )
    }

    @Test
    fun `a duplicate refuses the whole list`() {
        assertEquals(
            emptyList<String>(),
            kept("https://a.example", "https://a.example"),
        )
        assertEquals(
            "control: the same host on a different port is a different address",
            listOf("https://a.example", "https://a.example:8443"),
            kept("https://a.example", "https://a.example:8443"),
        )
    }

    @Test
    fun `a blank first entry with a later entry refuses the whole list`() {
        assertEquals(emptyList<String>(), kept("", "https://b.example"))
        assertEquals(emptyList<String>(), kept(null, "https://b.example"))
        assertEquals(
            "control: entry 1 present, the list is kept",
            listOf("https://a.example", "https://b.example"),
            kept("https://a.example", "https://b.example"),
        )
    }

    @Test
    fun `a refused entry that is not trailing refuses the whole list`() {
        assertEquals(emptyList<String>(), kept("https://a.example", "ftp://b.example", "https://c.example"))
        assertEquals(
            "control: the same blank text at the end is dropped instead",
            listOf("https://a.example", "https://c.example"),
            kept("https://a.example", "https://c.example", ""),
        )
        assertEquals(
            "the refused text at the end refuses the whole list, exactly like a refused one in the middle",
            emptyList<String>(),
            kept("https://a.example", "https://c.example", "ftp://b.example"),
        )
    }

    @Test
    fun `a blank entry that is not trailing refuses the whole list`() {
        assertEquals(emptyList<String>(), kept("https://a.example", "", "https://c.example"))
        assertEquals(
            "control: the same blank at the end is dropped instead",
            listOf("https://a.example", "https://c.example"),
            kept("https://a.example", "https://c.example", ""),
        )
    }

    @Test
    fun `an empty list is the blank install and stays empty`() {
        assertEquals(emptyList<String>(), kept())
        assertEquals(emptyList<String>(), kept("", null))
    }

    @Test
    fun `each entry is trimmed the way the single-address rule trims it`() {
        assertEquals(
            listOf("https://a.example", "https://b.example"),
            kept("  https://a.example  ", "\thttps://b.example\n"),
        )
    }

    @Test
    fun `the list leans on the same rule as a single entry`() {
        // A text the single rule refuses is refused here too, and a text it
        // accepts is accepted: the two must not drift apart.
        assertEquals(
            "the single rule accepts this",
            "https://a.example",
            ServerAddressEntry.accepted("https://a.example"),
        )
        assertEquals(listOf("https://a.example"), kept("https://a.example"))
        org.junit.Assert.assertNull(
            "the single rule refuses this",
            ServerAddressEntry.accepted("https://a.example/?q=1"),
        )
        assertEquals(emptyList<String>(), kept("https://a.example", "https://a.example/?q=1"))
    }
}
