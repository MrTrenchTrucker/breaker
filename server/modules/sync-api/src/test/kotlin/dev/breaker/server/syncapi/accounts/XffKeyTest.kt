package dev.breaker.server.syncapi.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

// The pure key rule of the rate limits (ADR-041): the socket peer, or the client a listed proxy forwarded for.
class XffKeyTest {

    private val listed = parseTrustedProxies("10.0.0.0/8, 172.16.0.0/12 , 192.0.2.100, ::1")

    private fun key(peer: String, vararg header: String): String = xffKey(peer, header.toList(), listed)

    private fun keyWith(list: String, peer: String, vararg header: String): String =
        xffKey(peer, header.toList(), parseTrustedProxies(list))

    @Test
    fun `with an empty list a spoofed X-Forwarded-For does not change the key`() {
        assertEquals("1.2.3.4", keyWith("", "1.2.3.4", "5.6.7.8"))
        assertEquals("1.2.3.4", xffKey("1.2.3.4", listOf("5.6.7.8"), TrustedProxies.empty))
        assertEquals("1.2.3.4", xffKey("1.2.3.4", listOf("5.6.7.8"), parseTrustedProxies(null)))
        assertEquals("1.2.3.4", keyWith(" , ,", "1.2.3.4", "5.6.7.8"))
    }

    @Test
    fun `a peer that is not listed ignores a header that names a listed address`() {
        assertEquals("203.0.113.9", key("203.0.113.9", "10.0.0.1, 1.2.3.4"))
        assertEquals("203.0.113.9", key("203.0.113.9", "1.2.3.4"))
    }

    @Test
    fun `a listed peer uses the right-most entry that is not listed`() {
        assertEquals("1.2.3.4", key("10.0.0.1", "1.2.3.4, 10.0.0.1"))
        assertEquals("1.2.3.4", key("10.0.0.1", "9.9.9.9, 1.2.3.4, 10.0.0.2"))
        assertEquals("1.2.3.4", key("10.0.0.1", "1.2.3.4"))
    }

    @Test
    fun `several header lines are joined in order before the scan`() {
        assertEquals("8.8.8.8", key("10.0.0.1", "1.2.3.4", "8.8.8.8"))
        assertEquals("8.8.8.8", key("10.0.0.1", "8.8.8.8, 10.0.0.9", "10.0.0.8"))
    }

    @Test
    fun `every entry listed or no header at all gives the peer`() {
        assertEquals("10.0.0.5", key("10.0.0.5", "10.0.0.1, 172.16.0.1"))
        assertEquals("10.0.0.5", key("10.0.0.5"))
        assertEquals("10.0.0.5", key("10.0.0.5", ""))
        assertEquals("10.0.0.5", key("10.0.0.5", " , "))
    }

    @Test
    fun `an entry that is not an IP literal gives the peer`() {
        assertEquals("10.0.0.5", key("10.0.0.5", "not-an-ip"))
        assertEquals("10.0.0.5", key("10.0.0.5", "10.0.0.1, garbage"))
        assertEquals("10.0.0.5", key("10.0.0.5", "1.2.3.4:8080"))
        assertEquals("10.0.0.5", key("10.0.0.5", "[2001:db8::1]"))
        assertEquals("10.0.0.5", key("10.0.0.5", "localhost"))
        assertEquals("10.0.0.5", key("10.0.0.5", "01.2.3.4"))
        assertEquals("10.0.0.5", key("10.0.0.5", "unknown"))
    }

    @Test
    fun `an entry left of the chosen one is never read`() {
        assertEquals("8.8.8.8", key("10.0.0.1", "garbage, 8.8.8.8"))
    }

    @Test
    fun `more than 20 entries give the peer and exactly 20 are read`() {
        val twenty = (1..20).joinToString(", ") { "1.1.1.$it" }
        val twentyOne = (1..21).joinToString(", ") { "1.1.1.$it" }
        assertEquals("1.1.1.20", key("10.0.0.5", twenty))
        assertEquals("10.0.0.5", key("10.0.0.5", twentyOne))
        assertEquals("10.0.0.5", key("10.0.0.5", twenty, "1.1.1.21"))
    }

    @Test
    fun `an entry over 45 characters gives the peer even when the last entry is good`() {
        assertEquals("10.0.0.5", key("10.0.0.5", "a".repeat(46) + ", 8.8.8.8"))
    }

    @Test
    fun `an IPv6 key is written in lower case with the longest zero run compressed`() {
        assertEquals("2001:db8::1", key("::1", "2001:DB8::1"))
        assertEquals("2001:db8::1", key("::1", "2001:0db8:0000:0000:0000:0000:0000:0001"))
        assertEquals("fe80:0:0:1::5", key("::1", "fe80:0:0:1:0:0:0:5"))
        assertEquals("1::2:0:0:3:4", key("::1", "1:0:0:2:0:0:3:4"))
        assertEquals("1:2:3:4:5:6:7:8", key("::1", "1:2:3:4:5:6:7:8"))
        assertEquals("1:0:2:3:4:5:6:7", key("::1", "1:0:2:3:4:5:6:7"))
        assertEquals("::2", key("::1", "::2"))
    }

    @Test
    fun `an IPv6 key compresses a trailing, a leading and an all zero run but not a single zero group`() {
        assertEquals("1:2:3:4:5:6::", key("::1", "1:2:3:4:5:6:0:0"))
        assertEquals("::1:2:3:4:5:6", key("::1", "0:0:1:2:3:4:5:6"))
        assertEquals("::", key("::1", "0:0:0:0:0:0:0:0"))
        assertEquals("1:2:3:4:5:6:7:0", key("::1", "1:2:3:4:5:6:7:0"))
        assertEquals("0:1:2:3:4:5:6:7", key("::1", "0:1:2:3:4:5:6:7"))
    }

    @Test
    fun `an entry of exactly 45 characters is read`() {
        val entry = "ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255"
        assertEquals(45, entry.length)
        assertEquals("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", key("10.0.0.5", entry))
    }

    @Test
    fun `an IPv4 mapped address is its IPv4 address as peer, as entry and as list item`() {
        assertEquals("1.2.3.4", key("::ffff:10.0.0.1", "1.2.3.4"))
        assertEquals("1.2.3.4", key("10.0.0.1", "::ffff:1.2.3.4"))
        assertEquals("1.2.3.4", key("10.0.0.1", "::ffff:102:304"))
        assertEquals("10.0.0.1", keyWith("::ffff:1.2.3.4", "1.2.3.4", "10.0.0.1"))
        assertEquals("5.6.7.8", keyWith("::ffff:1.2.3.4", "1.2.3.4", "5.6.7.8, ::ffff:1.2.3.4"))
    }

    @Test
    fun `an IPv4 block is matched at its first and last address and not outside`() {
        val list = "10.0.0.0/29"
        assertEquals("1.2.3.4", keyWith(list, "10.0.0.0", "1.2.3.4"))
        assertEquals("1.2.3.4", keyWith(list, "10.0.0.7", "1.2.3.4"))
        assertEquals("10.0.0.8", keyWith(list, "10.0.0.8", "1.2.3.4"))
        assertEquals("9.255.255.255", keyWith(list, "9.255.255.255", "1.2.3.4"))
    }

    @Test
    fun `a block of 8 bits and a block of 32 bits are matched exactly`() {
        assertEquals("1.2.3.4", keyWith("10.0.0.0/8", "10.255.255.255", "1.2.3.4"))
        assertEquals("11.0.0.0", keyWith("10.0.0.0/8", "11.0.0.0", "1.2.3.4"))
        assertEquals("1.2.3.4", keyWith("10.0.0.5/32", "10.0.0.5", "1.2.3.4"))
        assertEquals("10.0.0.4", keyWith("10.0.0.5/32", "10.0.0.4", "1.2.3.4"))
        assertEquals("10.0.0.6", keyWith("10.0.0.5/32", "10.0.0.6", "1.2.3.4"))
    }

    @Test
    fun `an IPv6 block is matched inside and not outside`() {
        assertEquals("1.2.3.4", keyWith("2001:db8::/32", "2001:db8:ffff::1", "1.2.3.4"))
        assertEquals("2001:db9::1", keyWith("2001:db8::/32", "2001:db9::1", "1.2.3.4"))
        assertEquals("1.2.3.4", keyWith("2001:db8::5/128", "2001:db8::5", "1.2.3.4"))
        assertEquals("2001:db8::6", keyWith("2001:db8::5/128", "2001:db8::6", "1.2.3.4"))
    }

    @Test
    fun `an IPv4 peer and an IPv6 block never match`() {
        assertEquals("10.0.0.1", keyWith("2001:db8::/32", "10.0.0.1", "1.2.3.4"))
        assertEquals("2001:db8::1", keyWith("10.0.0.0/8", "2001:db8::1", "1.2.3.4"))
    }

    @Test
    fun `a peer that is not an IP literal is the key`() {
        assertEquals("localhost", key("localhost", "1.2.3.4"))
        assertEquals("", key("", "1.2.3.4"))
    }

    // The prefix mask of a block whose length is not a multiple of 8: /7 takes the whole next byte pair, so 10.0.0.0/7 is
    // 10.x and 11.x. A mask that compared 8 bits would drop 11.x out of the block.
    @Test
    fun `a block of 7 bits takes both of its bytes and nothing outside`() {
        assertEquals("1.2.3.4", keyWith("10.0.0.0/7", "10.255.255.255", "1.2.3.4"))
        assertEquals("1.2.3.4", keyWith("10.0.0.0/7", "11.0.0.1", "1.2.3.4"))
        assertEquals("12.0.0.1", keyWith("10.0.0.0/7", "12.0.0.1", "1.2.3.4"))
        assertEquals("9.255.255.255", keyWith("10.0.0.0/7", "9.255.255.255", "1.2.3.4"))
    }

    @Test
    fun `a block of 7 bits on the first IPv6 byte takes both bytes and nothing outside`() {
        assertEquals("1.2.3.4", keyWith("200::/7", "200::1", "1.2.3.4"))
        assertEquals("1.2.3.4", keyWith("200::/7", "300::1", "1.2.3.4"))
        assertEquals("400::1", keyWith("200::/7", "400::1", "1.2.3.4"))
        assertEquals("100::1", keyWith("200::/7", "100::1", "1.2.3.4"))
    }

    @Test
    fun `a block of 9 bits ends on the first bit of the second byte`() {
        assertEquals("1.2.3.4", keyWith("10.0.0.0/9", "10.127.255.255", "1.2.3.4"))
        assertEquals("10.128.0.0", keyWith("10.0.0.0/9", "10.128.0.0", "1.2.3.4"))
    }

    // The scan reads from the right: the right-most entry that is not an IP literal ends it with the peer, so a
    // spoofed good address to its left is never used. An unreadable entry to the LEFT of a good one is never reached.
    @Test
    fun `an unreadable right-most entry stops the walk and gives the peer`() {
        assertEquals("10.0.0.1", key("10.0.0.1", "8.8.8.8, garbage"))
        assertEquals("10.0.0.1", key("10.0.0.1", "9.9.9.9, 8.8.8.8, garbage"))
        assertEquals("8.8.8.8", key("10.0.0.1", "garbage, 8.8.8.8"))
    }

    // Empty pieces of the header (a trailing comma, doubled commas) are dropped before the scan; kept, an empty piece
    // is not an IP literal and would hand the key to the peer.
    @Test
    fun `empty pieces of the header are dropped before the scan`() {
        assertEquals("8.8.8.8", key("10.0.0.1", "8.8.8.8,"))
        assertEquals("8.8.8.8", key("10.0.0.1", "8.8.8.8,,,,"))
        assertEquals("8.8.8.8", key("10.0.0.1", ",8.8.8.8"))
        assertEquals("8.8.8.8", key("10.0.0.1", "8.8.8.8", ""))
        assertEquals("10.0.0.1", key("10.0.0.1", "a,,b"))
    }

    // The key text of an IPv6 client that ends in a zero group goes through the zero-run search. A loop bound that
    // never reaches the end of the eight groups does not finish, so each of these has a time limit: a hang fails.
    @Test(timeout = 20_000)
    fun `an IPv6 client that ends in zero groups is keyed in compressed form`() {
        assertEquals("2001:db8::", keyWith("10.0.0.0/8", "10.0.0.1", "2001:db8::"))
        assertEquals("::", keyWith("10.0.0.0/8", "10.0.0.1", "::"))
        assertEquals("1::", keyWith("10.0.0.0/8", "10.0.0.1", "1:0:0:0:0:0:0:0"))
    }

    @Test(timeout = 20_000)
    fun `an IPv6 client with a zero run in the middle or at the start is keyed in compressed form`() {
        assertEquals("1::8", keyWith("10.0.0.0/8", "10.0.0.1", "1:0:0:0:0:0:0:8"))
        assertEquals("::8", keyWith("10.0.0.0/8", "10.0.0.1", "0:0:0:0:0:0:0:8"))
        assertEquals("1:0:3:4:5:6:7:8", keyWith("10.0.0.0/8", "10.0.0.1", "1:0:3:4:5:6:7:8"))
        assertEquals("1:2:3:4:5:6:7:8", keyWith("10.0.0.0/8", "10.0.0.1", "1:2:3:4:5:6:7:8"))
    }
}
