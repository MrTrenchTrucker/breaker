package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the per-session nonce: the adapter in wiring/UpdateInstallerAndroid.kt, its helper in
 * wiring/InstallNonce.kt, and the decision rule in wiring/InstallStatusRule.kt, which must keep its signature.
 * Each pin reads the real file and counts one point of the nonce path in its code, with comments and string
 * text blanked; a pin on a literal reads the text as written. Every pin is broken by at least two edited samples
 * and must stay quiet on harmless edits: a comment and renamed locals.
 */
internal class UpdateInstallerNonceGateTest {

    private val raw: String = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/UpdateInstallerAndroid.kt")

    /** The adapter text with each pair applied in order; a pair whose old text is missing fails by name. */
    private fun applied(edits: List<Pair<String, String>>): String {
        var text: String = raw
        for ((old, new) in edits) {
            check(text.contains(old)) { "app: a nonce sample lost its text \"$old\"" }
            text = text.replace(old, new)
        }
        return text
    }

    /** Counts the matches of [pattern] in the edited adapter, in its code (or its raw text when [literal]), and checks the pin. */
    private fun pin(
        label: String,
        pattern: String,
        times: Int,
        firing: List<List<Pair<String, String>>>,
        literal: Boolean = false,
    ) {
        val regex = Regex(pattern)
        fun hits(edits: List<Pair<String, String>>): Int {
            val text: String = applied(edits)
            return regex.findAll(if (literal) text else AppSourceFiles.strip(text).code).count()
        }
        assertEquals("app: $label does not hold on the real file", times, hits(emptyList()))
        assertTrue("app: $label has fewer than two firing samples", firing.size >= 2)
        for (edits in firing) {
            assertTrue("app: $label still holds after an edit that breaks it", hits(edits) != times)
        }
        for (edits in quiet) {
            assertEquals("app: $label fires on a harmless edit", times, hits(edits))
        }
    }

    private val quiet: List<List<Pair<String, String>>> = listOf(
        listOf(
            "private val ownSessions: MutableMap<Int, String> = ConcurrentHashMap()" to
                "private val ownSessions: MutableMap<Int, String> = ConcurrentHashMap() // keyed by session id",
            "val stored: String" to "val expected: String",
            "InstallNonce.matches(stored, received)" to "InstallNonce.matches(expected, received)",
            "val nonce: String = ownSessions[sessionId].orEmpty()" to "val token: String = ownSessions[sessionId].orEmpty()",
            ".putExtra(nonceExtra, nonce)" to ".putExtra(nonceExtra, token)",
            "private companion object {" to "// a note that names nonce and Log\n    private companion object {",
        ),
    )

    @Test
    fun `a nonce is generated once per opened session and stored under its session id`() {
        pin(
            "the nonce generation per session",
            """ownSessions\s*\[\s*sessionId\s*\]\s*=\s*InstallNonce\s*\.\s*generate\s*\(\s*\)""",
            1,
            listOf(
                listOf("ownSessions[sessionId] = InstallNonce.generate()" to "ownSessions.put(sessionId, InstallNonce.generate())"),
                listOf("ownSessions[sessionId] = InstallNonce.generate()" to "ownSessions[sessionId] = InstallNonce.reuse()"),
            ),
        )
    }

    @Test
    fun `the own sessions map holds the nonce of each session id`() {
        pin(
            "the own sessions map type",
            """private\s+val\s+ownSessions\s*:\s*MutableMap\s*<\s*Int\s*,\s*String\s*>\s*=\s*ConcurrentHashMap\s*\(\s*\)""",
            1,
            listOf(
                listOf("MutableMap<Int, String> = ConcurrentHashMap()" to "MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()"),
                listOf("MutableMap<Int, String> = ConcurrentHashMap()" to "MutableMap<Int, Int> = ConcurrentHashMap()"),
            ),
        )
    }

    @Test
    fun `the status intent extra is named after this package as the install nonce`() {
        pin(
            "the nonce extra name",
            """context\s*\.\s*packageName\s*\+\s*"\.extra\.INSTALL_NONCE"\s*""",
            1,
            listOf(
                listOf("context.packageName + \".extra.INSTALL_NONCE\"" to "context.packageName + \".extra.NONCE\""),
                listOf("context.packageName + \".extra.INSTALL_NONCE\"" to "\"com.other\" + \".extra.INSTALL_NONCE\""),
            ),
            literal = true,
        )
    }

    @Test
    fun `the status intent carries the stored nonce under the extra name`() {
        pin(
            "the nonce put into the status intent",
            """ownSessions\s*\[\s*sessionId\s*\]\s*\.orEmpty\s*\(\s*\)[\s\S]*?\.putExtra\s*\(\s*nonceExtra\s*,\s*\w+\s*\)""",
            1,
            listOf(
                listOf(".putExtra(nonceExtra, nonce)" to ".putExtra(PackageInstaller.EXTRA_SESSION_ID, nonce)"),
                listOf("ownSessions[sessionId].orEmpty()" to "ownSessions[sessionId] ?: \"\""),
            ),
        )
    }

    @Test
    fun `the own session check needs a known session id and an equal nonce`() {
        pin(
            "the own session check body",
            """private\s+fun\s+isOwnSession\s*\(\s*sessionId\s*:\s*Int\s*,\s*received\s*:\s*String\?\s*\)\s*:\s*Boolean\s*\{\s*val\s+(\w+)\s*:\s*String\s*=\s*ownSessions\s*\[\s*sessionId\s*\]\s*\?:\s*return\s+false\s*return\s+InstallNonce\s*\.\s*matches\s*\(\s*\1\s*,\s*received\s*\)\s*\}""",
            1,
            listOf(
                listOf("return InstallNonce.matches(stored, received)" to "return received != null"),
                listOf("ownSessions[sessionId] ?: return false" to "ownSessions[sessionId] ?: return true"),
            ),
        )
    }

    @Test
    fun `the nonce is compared only by the helper and never with a double equals`() {
        pin(
            "the helper compare call",
            """InstallNonce\s*\.\s*matches\s*\(\s*\w+\s*,\s*received\s*\)""",
            1,
            listOf(
                listOf("InstallNonce.matches(stored, received)" to "stored == received"),
                listOf("InstallNonce.matches(stored, received)" to "InstallNonce.matches(stored, received) && InstallNonce.matches(stored, received)"),
            ),
        )
        pin(
            "no double equals on a nonce name",
            """\b(stored|received|nonce|token|expected)\b\s*(==|!=)|(==|!=)\s*\b(stored|received|nonce|token|expected)\b""",
            0,
            listOf(
                listOf("InstallNonce.matches(stored, received)" to "stored == received"),
                listOf("InstallNonce.matches(stored, received)" to "received != stored"),
            ),
        )
    }

    @Test
    fun `the nonce never reaches a log or a message`() {
        pin(
            "the nonce in a log or a print call",
            """\b(Log|println|print|Toast)\b[^\n]*\b(nonce|stored|received|token|expected)\b""",
            0,
            listOf(
                listOf("return InstallNonce.matches(stored, received)" to "return InstallNonce.matches(stored, received).also { println(stored) }"),
                listOf("context.startActivity(confirm)" to "Toast.makeText(context, stored, 0)\n            context.startActivity(confirm)"),
            ),
        )
    }

    @Test
    fun `the status intent stays explicit and the pending intent stays mutable`() {
        pin(
            "the explicit package",
            """\.setPackage\s*\(\s*context\s*\.\s*packageName\s*\)""",
            1,
            listOf(
                listOf(".setPackage(context.packageName)" to ".setPackage(statusAction)"),
                listOf(".setPackage(context.packageName)" to ""),
            ),
        )
        pin(
            "the mutable flag",
            """\bFLAG_MUTABLE\b""",
            1,
            listOf(
                listOf("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE" to "PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"),
                listOf("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE" to "PendingIntent.FLAG_UPDATE_CURRENT"),
            ),
        )
    }

    @Test
    fun `forgetting a session removes its nonce with it`() {
        pin(
            "the removal of a session",
            """ownSessions\.remove\s*\(\s*sessionId\s*\)""",
            1,
            listOf(
                listOf("ownSessions.remove(sessionId)" to "ownSessions.get(sessionId)"),
                listOf("ownSessions.remove(sessionId)" to "ownSessions.remove(NO_SESSION_ID)"),
            ),
        )
    }

    @Test
    fun `the nonce helper compares with the message digest equality`() {
        val helper: String = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/InstallNonce.kt")
        val compare = "MessageDigest.isEqual("
        assertEquals("app: the helper compare does not hold on the real file", 1, occurrences(helper, compare))
        assertEquals("app: a plain equality replaces the digest compare", 0, occurrences(swapped(helper, compare, "java.util.Objects.equals("), compare))
        assertEquals("app: a second digest compare still holds", 2, occurrences(swapped(helper, compare, "MessageDigest.isEqual(\"\", \"\") || MessageDigest.isEqual("), compare))
        assertEquals("app: a comment changes the count", 1, occurrences(swapped(helper, "object InstallNonce {", "// a note\nobject InstallNonce {"), compare))
    }

    @Test
    fun `the status rule keeps its decide signature with the nonce folded into ours`() {
        val rule: String = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/InstallStatusRule.kt")
        val signature = "fun decide(status: Int, hasConfirmIntent: Boolean, sessionIsOurs: Boolean): StatusAction"
        assertEquals("app: the decide signature does not hold on the real file", 1, occurrences(rule, signature))
        assertEquals(0, occurrences(swapped(rule, signature, "fun decide(status: Int, hasConfirmIntent: Boolean, sessionIsOurs: Int): StatusAction"), signature))
        assertEquals(0, occurrences(swapped(rule, signature, "fun decide(status: Int, hasConfirmIntent: Boolean, sessionIsOurs: Boolean, extra: Int): StatusAction"), signature))
        assertEquals(1, occurrences(swapped(rule, "object InstallStatusRule {", "// a note\nobject InstallStatusRule {"), signature))
    }

    private fun occurrences(text: String, needle: String): Int = text.split(needle).size - 1

    private fun swapped(text: String, old: String, new: String): String {
        check(text.contains(old)) { "app: a nonce sample lost its text \"$old\"" }
        return text.replace(old, new)
    }
}
