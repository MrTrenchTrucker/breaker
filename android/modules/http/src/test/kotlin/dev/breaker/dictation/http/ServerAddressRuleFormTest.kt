package dev.breaker.dictation.http

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule, cases about the shape of the address and of the configured address: ports that are not usable, user
 * information, the scheme, a configured address that is unusable, and characters that are not allowed.
 * Each test holds one group of cases written out as rows; no case reads a network.
 */
class ServerAddressRuleFormTest {

    /** A bad port on the address, on the configured address, or on both permits nothing. */
    @Test
    fun `ports outside 1 to 65535 or not plain digits are not permitted on either side`() {
        val rows = listOf(
            RuleRow("http://server.test:0/x", "http://server.test", false),
            RuleRow("http://server.test:0/x", "http://server.test:0", false),
            RuleRow("http://server.test/x", "http://server.test:0", false),
            RuleRow("http://server.test:65536/x", "http://server.test", false),
            RuleRow("http://server.test:65536/x", "http://server.test:65536", false),
            RuleRow("http://server.test/x", "http://server.test:65536", false),
            RuleRow("http://server.test:99999/x", "http://server.test", false),
            RuleRow("http://server.test:99999/x", "http://server.test:99999", false),
            RuleRow("http://server.test/x", "http://server.test:99999", false),
            RuleRow("http://server.test:100000/x", "http://server.test", false),
            RuleRow("http://server.test:100000/x", "http://server.test:100000", false),
            RuleRow("http://server.test/x", "http://server.test:100000", false),
            RuleRow("http://server.test:/x", "http://server.test", false),
            RuleRow("http://server.test:/x", "http://server.test:", false),
            RuleRow("http://server.test/x", "http://server.test:", false),
            RuleRow("http://server.test:+80/x", "http://server.test", false),
            RuleRow("http://server.test:+80/x", "http://server.test:+80", false),
            RuleRow("http://server.test/x", "http://server.test:+80", false),
            RuleRow("http://server.test:-1/x", "http://server.test", false),
            RuleRow("http://server.test:-1/x", "http://server.test:-1", false),
            RuleRow("http://server.test/x", "http://server.test:-1", false),
            RuleRow("http://server.test:8a/x", "http://server.test", false),
            RuleRow("http://server.test:8a/x", "http://server.test:8a", false),
            RuleRow("http://server.test/x", "http://server.test:8a", false),
            RuleRow("http://server.test:80:90/x", "http://server.test", false),
            RuleRow("http://server.test:80:90/x", "http://server.test:80:90", false),
            RuleRow("http://server.test/x", "http://server.test:80:90", false),
            RuleRow("http://server.test:0x50/x", "http://server.test", false),
            RuleRow("http://server.test:0x50/x", "http://server.test:0x50", false),
            RuleRow("http://server.test/x", "http://server.test:0x50", false),
            RuleRow("http://server.test:\u0668\u0660/x", "http://server.test", false),
            RuleRow("http://server.test:\u0668\u0660/x", "http://server.test:\u0668\u0660", false),
            RuleRow("http://server.test/x", "http://server.test:\u0668\u0660", false),
            RuleRow("http://server.test:000080/x", "http://server.test", false),
            RuleRow("http://server.test:000080/x", "http://server.test:000080", false),
            RuleRow("http://server.test/x", "http://server.test:000080", false),
            RuleRow("http://server.test:" + "0".repeat(18) + "80" + "/x", "http://server.test", false),
            RuleRow("http://server.test:" + "0".repeat(18) + "80" + "/x", "http://server.test:" + "0".repeat(18) + "80" + "", false),
            RuleRow("http://server.test/x", "http://server.test:" + "0".repeat(18) + "80" + "", false),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for badports: $misses", misses.isEmpty())
    }

    /** An at sign in the authority of either address refuses plain http; one in the path or the query does not. */
    @Test
    fun `user information is not permitted and an at sign after the authority is ignored`() {
        val rows = listOf(
            RuleRow("http://user@server.test/x", "http://server.test", false),
            RuleRow("http://user:word@server.test/x", "http://server.test", false),
            RuleRow("http://@server.test/x", "http://server.test", false),
            RuleRow("http://server.test@other.test/x", "http://server.test", false),
            RuleRow("http://other.test@server.test/x", "http://server.test", false),
            RuleRow("http://server.test/p@x", "http://server.test", true),
            RuleRow("http://server.test/p?q=a@b", "http://server.test", true),
            RuleRow("http://server.test#a@b", "http://server.test", true),
            RuleRow("http://server.test/x", "http://user@server.test", false),
            RuleRow("http://user@server.test/x", "http://user@server.test", false),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for userinfo: $misses", misses.isEmpty())
    }

    /** A user who configured https is not moved down to plain http; the scheme must be exactly http. */
    @Test
    fun `a configured https address never permits plain http and look alike schemes are not permitted`() {
        val rows = listOf(
            RuleRow("http://server.test/x", "https://server.test", false),
            RuleRow("http://server.test:443/x", "https://server.test", false),
            RuleRow("https://server.test/x", "https://server.test", true),
            RuleRow("http\u017f://server.test/x", "http://server.test", false),
            RuleRow("htt\u0440://server.test/x", "http://server.test", false),
            RuleRow("ftp://server.test/x", "http://server.test", false),
            RuleRow("ws://server.test/x", "http://server.test", false),
            RuleRow("htp://server.test/x", "http://server.test", false),
            RuleRow("http:/server.test/x", "http://server.test", false),
            RuleRow("http:server.test", "http://server.test", false),
            RuleRow("//server.test/x", "http://server.test", false),
            RuleRow("server.test/x", "http://server.test", false),
            RuleRow("://server.test", "http://server.test", false),
            RuleRow("", "http://server.test", false),
            RuleRow("http://server.test/x", "ftp://server.test", false),
            RuleRow("http://server.test/x", "server.test", false),
            RuleRow("http://server.test/x", "//server.test", false),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for scheme: $misses", misses.isEmpty())
    }

    /** With no usable configured server there is no plain http address that may be called. */
    @Test
    fun `an empty blank or unusable configured address permits no plain http but https stays permitted`() {
        val rows = listOf(
            RuleRow("http://server.test/x", "", false),
            RuleRow("https://server.test/x", "", true),
            RuleRow("http://server.test/x", "   ", false),
            RuleRow("https://server.test/x", "   ", true),
            RuleRow("http://server.test/x", "server.test", false),
            RuleRow("https://server.test/x", "server.test", true),
            RuleRow("http://server.test/x", "ftp://server.test", false),
            RuleRow("https://server.test/x", "ftp://server.test", true),
            RuleRow("http://server.test/x", "http://", false),
            RuleRow("https://server.test/x", "http://", true),
            RuleRow("http://server.test/x", "http:///x", false),
            RuleRow("https://server.test/x", "http:///x", true),
            RuleRow("http://server.test/x", "http://:80", false),
            RuleRow("https://server.test/x", "http://:80", true),
            RuleRow("http://server.test/x", "http://server.test:0", false),
            RuleRow("https://server.test/x", "http://server.test:0", true),
            RuleRow("http://server.test/x", "http://user@server.test", false),
            RuleRow("https://server.test/x", "http://user@server.test", true),
            RuleRow("http://server.test/x", "http://server .test", false),
            RuleRow("https://server.test/x", "http://server .test", true),
            RuleRow("http://server.test/x", "http://[::1", false),
            RuleRow("https://server.test/x", "http://[::1", true),
            RuleRow("http://server.test/x", "not an address", false),
            RuleRow("https://server.test/x", "not an address", true),
            RuleRow("http://server.test/x", "http://s\u00e4rver.test", false),
            RuleRow("https://server.test/x", "http://s\u00e4rver.test", true),
            RuleRow("http://server.test/x", "https://server.test", false),
            RuleRow("https://server.test/x", "https://server.test", true),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for noserver: $misses", misses.isEmpty())
    }

    /** The host alphabet is ASCII letters, digits, hyphen, dot and underscore. */
    @Test
    fun `whitespace control characters and characters outside the host alphabet are not permitted`() {
        val rows = listOf(
            RuleRow("http://server.test/a b", "http://server.test", false),
            RuleRow("http://server.test/a\u0009b", "http://server.test", false),
            RuleRow("http://server.test/a\u000ab", "http://server.test", false),
            RuleRow("http://server.test/a\u000db", "http://server.test", false),
            RuleRow("http://server.test/a\u0007b", "http://server.test", false),
            RuleRow("http://server.test/a\u007fb", "http://server.test", false),
            RuleRow(" http://server.test/x", "http://server.test", false),
            RuleRow("http://s\u00e4rver.test/x", "http://s\u00e4rver.test", false),
            RuleRow("http://s\u0131te.test/x", "http://s\u0131te.test", false),
            RuleRow("http://server.test\\x/", "http://server.test", false),
            RuleRow("http://server%2etest/x", "http://server%2etest", false),
            RuleRow("http://ser*ver.test/x", "http://ser*ver.test", false),
            RuleRow("http:///x", "http://server.test", false),
            RuleRow("http://:80/x", "http://server.test", false),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for characters: $misses", misses.isEmpty())
    }

    /** The path, the query and the fragment are not compared, so a non-ASCII character there is accepted and left to the platform. */
    @Test
    fun `a non ASCII character is refused in the scheme and the host and left alone in the path and the query`() {
        val rows = listOf(
            RuleRow("http://server.test/\u00e9", "http://server.test", true),
            RuleRow("http://server.test/x?q=\u00e9", "http://server.test", true),
            RuleRow("http://server.test/x#\u00e9", "http://server.test", true),
            RuleRow("http://server.test/x", "http://server.test/\u00e9", true),
            RuleRow("https://other.test/\u00e9", "http://server.test", true),
            RuleRow("http://ser\u00e9ver.test/x", "http://ser\u00e9ver.test", false),
            RuleRow("http://ser\u00e9ver.test/x", "http://server.test", false),
            RuleRow("http://server.test/x", "http://ser\u00e9ver.test", false),
            RuleRow("http://server.test\u00e9/x", "http://server.test\u00e9", false),
            RuleRow("https://ex\u00e9mple.test/x", "http://server.test", false),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for nonascii: $misses", misses.isEmpty())
    }
}
