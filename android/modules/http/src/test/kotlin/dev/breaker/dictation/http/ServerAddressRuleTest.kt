package dev.breaker.dictation.http

import org.junit.Assert.assertTrue
import org.junit.Test

/** One case of the rule: the address asked for, the configured server address, and the answer the rule must give. */
internal class RuleRow(val address: String, val configured: String, val expected: Boolean)

/** The rows for which [ServerAddressRule.permits] gives another answer than expected, written out for a failure message. */
internal fun ruleMisses(rows: List<RuleRow>): List<String> =
    rows.filter { ServerAddressRule.permits(it.address, it.configured) != it.expected }
        .map { "address '${it.address}' with configured '${it.configured}' expected ${it.expected}" }

/**
 * The rule that says which address the http module may call: https for any host, plain http only for the
 * configured server. Each test holds one group of cases written out as rows; no case reads a network.
 * The other cases of the rule are in ServerAddressRuleFormTest and ServerAddressRuleIpv6Test.
 */
class ServerAddressRuleTest {

    /** The https branch is unchanged: any host, and the same refusals as before. */
    @Test
    fun `https is permitted for any host whatever the configured server is`() {
        val rows = listOf(
            RuleRow("https://other.test/x", "http://server.test", true),
            RuleRow("https://other.test", "", true),
            RuleRow("https://other.test:8443/x", "http://server.test", true),
            RuleRow("HTTPS://Other.Test/x", "http://server.test", true),
            RuleRow("https://[::1]/x", "http://server.test", true),
            RuleRow("https://other.test", "https://server.test", true),
            RuleRow("https://other.test:99999/x", "http://server.test", true),
            RuleRow("https://user@other.test/x", "http://server.test", false),
            RuleRow("https:///x", "http://server.test", false),
            RuleRow("https://:443/x", "http://server.test", false),
            RuleRow("https://ex\u00e4mple.test/x", "http://server.test", false),
            RuleRow("https://other.test/a b", "http://server.test", false),
            RuleRow("https://other.test/a\u0009b", "http://server.test", false),
            RuleRow("https://other.test/a\u000ab", "http://server.test", false),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for https: $misses", misses.isEmpty())
    }

    /** Same host and same effective port; what follows the authority is not compared. */
    @Test
    fun `the configured server itself is permitted with any path query or fragment`() {
        val rows = listOf(
            RuleRow("http://server.test/x", "http://server.test", true),
            RuleRow("http://server.test", "http://server.test", true),
            RuleRow("http://server.test/a/b?q=1#f", "http://server.test", true),
            RuleRow("http://server.test?q", "http://server.test", true),
            RuleRow("http://server.test#f", "http://server.test", true),
            RuleRow("http://server.test/x", "http://server.test/base", true),
            RuleRow("http://server.test/x", "http://server.test/", true),
            RuleRow("http://server.test/x", "http://server.test?q", true),
            RuleRow("http://server.test:8080/x", "http://server.test:8080/api", true),
            RuleRow("http://my-server_two.test/x", "http://my-server_two.test", true),
            RuleRow("http://192.0.2.1/x", "http://192.0.2.1", true),
            RuleRow("http://192.0.2.1:8080/x", "http://192.0.2.1:8080", true),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for server: $misses", misses.isEmpty())
    }

    /** A sub domain, a longer name, a shorter name and another address are other hosts. */
    @Test
    fun `a plain http host other than the configured server is not permitted`() {
        val rows = listOf(
            RuleRow("http://other.test/x", "http://server.test", false),
            RuleRow("http://sub.server.test/x", "http://server.test", false),
            RuleRow("http://server.test.other.test/x", "http://server.test", false),
            RuleRow("http://xserver.test/x", "http://server.test", false),
            RuleRow("http://server/x", "http://server.test", false),
            RuleRow("http://other.test/x", "", false),
            RuleRow("http://192.0.2.2/x", "http://192.0.2.1", false),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for otherhost: $misses", misses.isEmpty())
    }

    /** A missing port equals 80, 443 is another server, and leading zeros do not change the number. */
    @Test
    fun `the effective port is compared as a number and a missing port means 80`() {
        val rows = listOf(
            RuleRow("http://server.test:80/x", "http://server.test", true),
            RuleRow("http://server.test/x", "http://server.test:80", true),
            RuleRow("http://server.test:8080/x", "http://server.test", false),
            RuleRow("http://server.test:443/x", "http://server.test", false),
            RuleRow("http://server.test/x", "http://server.test:8080", false),
            RuleRow("http://server.test:8081/x", "http://server.test:8080", false),
            RuleRow("http://server.test:0080/x", "http://server.test", true),
            RuleRow("http://server.test:00080/x", "http://server.test", true),
            RuleRow("http://server.test:65535/x", "http://server.test:65535", true),
            RuleRow("http://server.test:1/x", "http://server.test:1", true),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for ports: $misses", misses.isEmpty())
    }

    /** Only ASCII letter case is ignored. */
    @Test
    fun `the scheme and the host are compared without regard to ASCII letter case`() {
        val rows = listOf(
            RuleRow("HTTP://SERVER.TEST/x", "http://server.test", true),
            RuleRow("Http://Server.Test:80/x", "http://server.test", true),
            RuleRow("http://server.test/x", "HTTP://SERVER.TEST", true),
            RuleRow("http://SERVER.test/x", "http://server.TEST", true),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for case: $misses", misses.isEmpty())
    }

    /** The host is compared as written: a name with a trailing dot differs from the same name without it. */
    @Test
    fun `a trailing dot is not stripped from the host`() {
        val rows = listOf(
            RuleRow("http://server.test./x", "http://server.test", false),
            RuleRow("http://server.test/x", "http://server.test.", false),
            RuleRow("http://server.test./x", "http://server.test.", true),
        )
        val misses = ruleMisses(rows)
        assertTrue("http: ServerAddressRule gave a wrong answer for dot: $misses", misses.isEmpty())
    }
}
