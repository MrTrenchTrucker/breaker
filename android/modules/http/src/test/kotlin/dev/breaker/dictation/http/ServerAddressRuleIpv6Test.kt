package dev.breaker.dictation.http

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule, cases about bracketed IPv6 literals. A literal is compared as written after ASCII lower-casing, with
 * no canonical form, and it must hold at least two colons and only hex digits, colons and dots.
 * Each test holds one group of cases written out as rows; no case reads a network.
 */
class ServerAddressRuleIpv6Test {

    /** The same text with another letter case is the same server; another spelling of the same address is not. */
    @Test
    fun `a bracketed IPv6 literal is compared as written after ASCII lower-casing`() {
        val rows = listOf(
            RuleRow("http://[::1]/x", "http://[::1]", true),
            RuleRow("http://[::1]:80/x", "http://[::1]", true),
            RuleRow("http://[::1]/x", "http://[::1]:80", true),
            RuleRow("http://[::1]:8080/x", "http://[::1]", false),
            RuleRow("http://[::1]:8080/x", "http://[::1]:8080", true),
            RuleRow("http://[::A]/x", "http://[::a]", true),
            RuleRow("http://[::1]/x", "http://[0:0:0:0:0:0:0:1]", false),
            RuleRow("http://[0:0:0:0:0:0:0:1]/x", "http://[::1]", false),
            RuleRow("http://[0:0:0:0:0:0:0:1]/x", "http://[0:0:0:0:0:0:0:1]", true),
            RuleRow("http://[2001:abc::1]/x", "http://[2001:abc::1]", true),
            RuleRow("http://[::ffff:192.0.2.1]/x", "http://[::ffff:192.0.2.1]", true),
            RuleRow("http://[::]/x", "http://[::]", true),
            RuleRow("http://[::1]/x", "http://server.test", false),
            RuleRow("http://server.test/x", "http://[::1]", false),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for ipv6: $misses", misses.isEmpty())
    }

    /** Empty brackets, one colon, no colon, a zone, an unclosed bracket and text after the bracket are refused. */
    @Test
    fun `bracket text that is not one IPv6 literal of at least two colons is not permitted`() {
        val rows = listOf(
            RuleRow("http://[]/x", "http://[]", false),
            RuleRow("http://[]/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[]", false),
            RuleRow("http://[abc]/x", "http://[abc]", false),
            RuleRow("http://[abc]/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[abc]", false),
            RuleRow("http://[1.2.3.4]/x", "http://[1.2.3.4]", false),
            RuleRow("http://[1.2.3.4]/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[1.2.3.4]", false),
            RuleRow("http://[:]/x", "http://[:]", false),
            RuleRow("http://[:]/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[:]", false),
            RuleRow("http://[::1/x", "http://[::1", false),
            RuleRow("http://[::1/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[::1", false),
            RuleRow("http://[::1]x/x", "http://[::1]x", false),
            RuleRow("http://[::1]x/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[::1]x", false),
            RuleRow("http://[::g]/x", "http://[::g]", false),
            RuleRow("http://[::g]/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[::g]", false),
            RuleRow("http://[abc::1%25eth]/x", "http://[abc::1%25eth]", false),
            RuleRow("http://[abc::1%25eth]/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[abc::1%25eth]", false),
            RuleRow("http://[::1]:/x", "http://[::1]:", false),
            RuleRow("http://[::1]:/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[::1]:", false),
            RuleRow("http://[::1]:0/x", "http://[::1]:0", false),
            RuleRow("http://[::1]:0/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[::1]:0", false),
            RuleRow("http://[::1]:65536/x", "http://[::1]:65536", false),
            RuleRow("http://[::1]:65536/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[::1]:65536", false),
            RuleRow("http://[::1]]/x", "http://[::1]]", false),
            RuleRow("http://[::1]]/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[::1]]", false),
            RuleRow("http://[[::1]]/x", "http://[[::1]]", false),
            RuleRow("http://[[::1]]/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[[::1]]", false),
            RuleRow("http://[ ::1]/x", "http://[ ::1]", false),
            RuleRow("http://[ ::1]/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://[ ::1]", false),
            RuleRow("http://::1/x", "http://::1", false),
            RuleRow("http://::1/x", "http://[::1]", false),
            RuleRow("http://[::1]/x", "http://::1", false),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for badipv6: $misses", misses.isEmpty())
    }
}
