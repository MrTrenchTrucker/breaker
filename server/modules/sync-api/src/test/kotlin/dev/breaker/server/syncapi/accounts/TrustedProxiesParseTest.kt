package dev.breaker.server.syncapi.accounts

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The text of BREAKER_TRUSTED_PROXIES: what it accepts and what stops the start.
class TrustedProxiesParseTest {

    private val prefix = "sync-api: BREAKER_TRUSTED_PROXIES"

    private fun refusal(raw: String): String? =
        try {
            parseTrustedProxies(raw)
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }

    private fun has(list: TrustedProxies, literal: String): Boolean = list.contains(InetAddress.getByName(literal))

    @Test
    fun `unset blank and separator only values trust nothing`() {
        assertEquals(0, parseTrustedProxies(null).entries.size)
        assertEquals(0, parseTrustedProxies("").entries.size)
        assertEquals(0, parseTrustedProxies("   ").entries.size)
        assertEquals(0, parseTrustedProxies(" , ,, ").entries.size)
    }

    @Test
    fun `entries are split on commas with spaces around them ignored`() {
        val list = parseTrustedProxies(" 10.0.0.0/8 ,192.0.2.1,  ::1  ")
        assertEquals(3, list.entries.size)
        assertTrue(has(list, "10.9.9.9"))
        assertTrue(has(list, "192.0.2.1"))
        assertTrue(has(list, "::1"))
        assertFalse(has(list, "192.0.2.2"))
        assertFalse(has(list, "11.0.0.1"))
    }

    @Test
    fun `exactly 20 entries are accepted`() {
        assertEquals(20, parseTrustedProxies((1..20).joinToString(",") { "10.0.0.$it" }).entries.size)
    }

    @Test
    fun `every bad value stops the start with a message that names the variable`() {
        val bad = listOf(
            "not-an-ip", "localhost", "example.com", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "1.2.3.4:80",
            "10.0.0.0/33", "10.0.0.0/-1", "10.0.0.0/", "10.0.0.0/8/8", "10.0.0.0/08x", "10.0.0.0/0", "0.0.0.0/0",
            "::/0", "::1/129", "::g", ":::", "1::2::3", ":1", "1:", "1:2:3:4:5:6:7:8:9", "1:2:3:4::5:6:7:8",
            "[::1]", "10.0.0.1 10.0.0.2", "::ffff:1.2.3.256", "::1.2.3",
        )
        for (value in bad) {
            val message = refusal(value)
            assertNotNull("sync-api: the value $value is refused", message)
            assertTrue("sync-api: the message for $value names the variable: $message", message!!.startsWith(prefix))
        }
    }

    @Test
    fun `21 entries and an entry over 45 characters stop the start`() {
        assertTrue(refusal((1..21).joinToString(",") { "10.0.0.$it" })!!.startsWith(prefix))
        assertTrue(refusal("a".repeat(46))!!.startsWith(prefix))
        assertTrue(refusal("10.0.0.1," + "1".repeat(46))!!.startsWith(prefix))
    }

    @Test
    fun `an entry of exactly 45 characters is accepted`() {
        val entry = "ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255"
        assertEquals(45, entry.length)
        val list = parseTrustedProxies(entry)
        assertEquals(1, list.entries.size)
        assertTrue(has(list, "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))
    }

    @Test
    fun `a group of five digits and a group list that is too short or too long are refused`() {
        for (value in listOf("12345::1", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8::", "::1:2:3:4:5:6:7:8")) {
            assertTrue("sync-api: $value is refused", refusal(value)!!.startsWith(prefix))
        }
    }

    @Test
    fun `a longest legal entry is accepted`() {
        val list = parseTrustedProxies("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/128")
        assertEquals(1, list.entries.size)
        assertTrue(has(list, "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))
    }

    @Test
    fun `a block with prefix 1 and a block with prefix 32 are legal and exact`() {
        val half = parseTrustedProxies("128.0.0.0/1")
        assertTrue(has(half, "200.1.1.1"))
        assertFalse(has(half, "100.1.1.1"))
        val single = parseTrustedProxies("1.2.3.4/32")
        assertTrue(has(single, "1.2.3.4"))
        assertFalse(has(single, "1.2.3.5"))
    }

    @Test
    fun `an IPv6 literal with a zero gap and the same address spelled in full are the same entry`() {
        val list = parseTrustedProxies("2001:db8::1")
        assertTrue(has(list, "2001:db8:0:0:0:0:0:1"))
        assertTrue(has(list, "2001:0DB8::0001"))
        assertFalse(has(list, "2001:db8::2"))
    }

    // The entry length cap is 45 characters, counted on the trimmed entry. The 48 character entry below is a valid
    // IPv4-mapped block, so only the length rule refuses it.
    @Test
    fun `an entry of 45 characters parses and a valid entry of 48 characters stops the start`() {
        val at45 = "0000:0000:0000:0000:0000:ffff:255.255.255.255"
        assertEquals(45, at45.length)
        assertTrue(has(parseTrustedProxies(at45), "255.255.255.255"))
        val at48 = "$at45/32"
        assertEquals(48, at48.length)
        assertTrue(refusal(at48)!!.startsWith(prefix))
        assertTrue(refusal("10.0.0.1," + at48)!!.startsWith(prefix))
    }

    // Three digits are allowed after the slash, four are not, even when the value would be a good prefix.
    @Test
    fun `a prefix length of three digits parses and one of four digits stops the start`() {
        assertTrue(has(parseTrustedProxies("10.0.0.0/008"), "10.9.9.9"))
        assertTrue(refusal("10.0.0.0/0001")!!.startsWith(prefix))
        assertTrue(refusal("10.0.0.0/0008")!!.startsWith(prefix))
        assertTrue(refusal("::1/0128")!!.startsWith(prefix))
    }

    // Seven explicit groups beside the :: is the most an IPv6 literal can hold; eight leave no room for it.
    @Test
    fun `an IPv6 literal with seven explicit groups beside the double colon parses`() {
        assertTrue(has(parseTrustedProxies("1:2:3:4:5:6::7"), "1:2:3:4:5:6:0:7"))
        assertTrue(has(parseTrustedProxies("1:2:3:4:5:6:7::"), "1:2:3:4:5:6:7:0"))
        assertTrue(has(parseTrustedProxies("::1:2:3:4:5:6:7"), "0:1:2:3:4:5:6:7"))
        assertTrue(refusal("1:2:3:4:5:6:7::8")!!.startsWith(prefix))
        assertTrue(refusal("1:2:3:4:5:6:7:8:9")!!.startsWith(prefix))
    }

    // Both cases of the hex letters A to F are digits.
    @Test
    fun `upper case and lower case hex letters both parse in an IPv6 literal`() {
        assertTrue(has(parseTrustedProxies("::F"), "::f"))
        assertTrue(has(parseTrustedProxies("::f"), "::f"))
        assertTrue(has(parseTrustedProxies("ABCD:EF01::"), "abcd:ef01::"))
        assertTrue(refusal("::G")!!.startsWith(prefix))
    }

    // The 8 group literal and the :: at either edge go through the group fill: a hang fails the test.
    @Test(timeout = 20_000)
    fun `literals with eight groups or the double colon at an edge parse within the time limit`() {
        assertEquals(1, parseTrustedProxies("1:2:3:4:5:6:7:8").entries.size)
        assertEquals(1, parseTrustedProxies("::1:2:3:4:5:6:7").entries.size)
        assertEquals(1, parseTrustedProxies("1:2:3:4:5:6:7::").entries.size)
        assertEquals(1, parseTrustedProxies("::").entries.size)
    }
}
