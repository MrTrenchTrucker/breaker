package dev.breaker.dictation.stt.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's own answer on a table of addresses written out. The table was checked against the http
 * module's address rule by hand when it was written; this test never calls the rule, because the two
 * are separate copies on purpose until stt-server moves onto the http module, and nothing here calls
 * across modules.
 *
 * The cases are the ones where an address is checked against itself, because the engine's configured
 * address is the one address it was given and there is no second value to compare it with. The table
 * has three groups:
 * - the addresses on which the engine and the rule gave the same answer when checked by hand
 *   (accepted or refused);
 * - the addresses the engine accepts and the rule refuses, on purpose (see the comment on that group);
 * - the addresses the engine refuses and the rule accepts.
 *
 * A refused address gets one fixed sentence, and a blank one gets the no-address sentence.
 */
class EngineAddressTableTest {

    private class Row(val address: String, val problem: String?, val why: String)

    private val refused: String = FailureSentences.ADDRESS_NOT_HTTPS
    private val missing: String = FailureSentences.NO_ADDRESS

    /** The engine's answer on each row. By hand, the rule gives the same answer. A null problem means the address is accepted. */
    private val bothAgree: List<Row> = listOf(
        Row("http://example.test", null, "a plain host name"),
        Row("HTTP://EXAMPLE.TEST", null, "scheme and host in capitals"),
        Row("hTtP://Example.Test:8080", null, "mixed letter case with a port"),
        Row("http://example.test:80", null, "the plain port written out"),
        Row("http://example.test:8080", null, "an explicit port"),
        Row("http://example.test/", null, "a trailing slash"),
        Row("http://example.test/base/path", null, "a path"),
        Row("http://example.test/a@b", null, "an at sign after the first slash is not user information"),
        Row("http://example.test.", null, "a trailing dot is kept as written and is not a problem by itself"),
        Row("http://my_host.example.test", null, "an underscore in the host"),
        Row("http://192.0.2.10:8080", null, "a dotted address with a port"),
        Row("http://[::1]", null, "a bracketed IPv6 literal with no port"),
        Row("http://[2001:0db8::1]:8080", null, "a bracketed IPv6 literal with a port"),
        Row("http://", refused, "no host at all"),
        Row("http:///path", refused, "an empty host before a path"),
        Row("http://:8080", refused, "a port with no host"),
        Row("http://someone@example.test", refused, "user information"),
        Row("http://someone:@example.test", refused, "user information with an empty password"),
        Row("http://example.test@other.test", refused, "user information that looks like a host"),
        Row(" http://example.test", refused, "a leading space"),
        Row("http://example.test /x", refused, "a space inside the address"),
        Row("http://example.test\n", refused, "a line break"),
        Row("http://exa\tmple.test", refused, "a tab inside the host"),
        Row("http:/example.test", refused, "only one slash after the scheme"),
        Row("http:example.test", refused, "no slashes after the scheme"),
        Row("example.test", refused, "no scheme at all"),
        Row("ftp://example.test", refused, "another scheme"),
        Row("ws://example.test", refused, "another scheme that is not http"),
        Row("file://example.test", refused, "a scheme that is not a network scheme"),
        Row("htp://example.test", refused, "a mistyped scheme"),
        Row("httpss://example.test", refused, "a scheme with a letter too many"),
        Row("http\u017F://example.test", refused, "a long s in place of the last letter of the https scheme"),
        Row("\uFF48ttp://example.test", refused, "a full-width first letter in the scheme"),
        Row("", missing, "an empty address"),
        Row("   ", missing, "an address of spaces only"),
    )

    /**
     * The engine accepts and the rule refuses, on purpose. The engine only reads the start of the
     * address and the text up to the first slash. It sends to the one address it was given, so there is
     * no other host to protect; a port or a host spelling that cannot be called fails in the transport
     * as an ordinary failure. These rows are pinned so that a change of this looseness is a decision
     * and not an accident.
     */
    private val looserOnPurpose: List<Row> = listOf(
        Row("http://example.test:0", null, "the engine does not read the port, so port zero passes"),
        Row("http://example.test:65536", null, "the engine does not read the port, so a port above the last passes"),
        Row("http://example.test:99999", null, "the engine does not read the port, so a five digit port passes"),
        Row("http://example.test:", null, "the engine does not read the port, so an empty port passes"),
        Row("http://example.test:+80", null, "the engine does not read the port, so a signed port passes"),
        Row("http://example.test:80x", null, "the engine does not read the port, so a port with a letter passes"),
        Row("http://example.test:8080:9", null, "the engine does not read the port, so a second port passes"),
        Row("http://\u00FC.example/", null, "the engine does not read host characters, so a non-ASCII host passes"),
        Row("http://example.test\u00A0", null, "the engine refuses only the ordinary space and control characters"),
        Row("http://exa!mple.test", null, "the engine does not read host characters, so a mark in the host passes"),
        Row("http://exa\\mple.test", null, "the engine does not read host characters, so a backslash passes"),
        Row("http://[abc]", null, "the engine does not read brackets, so a bracket with no colon passes"),
        Row("http://[1.2.3.4]", null, "the engine does not read brackets, so a dotted address in brackets passes"),
        Row("http://[]", null, "the engine does not read brackets, so an empty bracket passes"),
        Row("http://[::1", null, "the engine does not read brackets, so an unclosed bracket passes"),
        Row("http://[::1]x", null, "the engine does not read brackets, so text after the bracket passes"),
        Row("http://[::1]:", null, "the engine does not read brackets, so an empty port after them passes"),
        Row("http://[::1%eth0]:8080", null, "the engine does not read brackets, so a zone id passes"),
    )

    /**
     * The engine refuses and the rule accepts: the engine ends the authority at the first slash only,
     * so an at sign after a question mark or a hash still counts as user information to the engine.
     */
    private val stricterThanTheRule: List<Row> = listOf(
        Row("http://example.test?a@b", refused, "an at sign after a question mark"),
        Row("http://example.test#a@b", refused, "an at sign after a hash"),
    )

    @Test
    fun `the engine answers as written on these http addresses, which were checked against the rule by hand`() {
        assertEquals(missing, ServerEndpoint.addressProblem(null))
        assertRows(bothAgree)
        assertTrue("the group has accepted rows", bothAgree.any { it.problem == null })
        assertTrue("the group has refused rows", bothAgree.any { it.problem == refused })
        assertTrue("the group has no-address rows", bothAgree.any { it.problem == missing })
    }

    @Test
    fun `the engine's check is looser than the rule on purpose`() {
        assertRows(looserOnPurpose)
        assertTrue("every row of this group is accepted by the engine", looserOnPurpose.all { it.problem == null })
    }

    @Test
    fun `the engine's check is stricter than the rule where an at sign follows a question mark or a hash`() {
        assertRows(stricterThanTheRule)
        assertTrue("every row of this group is refused by the engine", stricterThanTheRule.all { it.problem == refused })
    }

    @Test
    fun `the refusal sentence names both schemes`() {
        assertEquals(
            "The server address must be an http or https address with no spaces or user name.",
            FailureSentences.ADDRESS_NOT_HTTPS,
        )
    }

    private fun assertRows(rows: List<Row>) {
        assertTrue("the table is empty", rows.isNotEmpty())
        for (row in rows) {
            assertEquals("${row.why}: ${row.address}", row.problem, ServerEndpoint.addressProblem(row.address))
        }
    }
}
