package dev.breaker.dictation.stt.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixed sentences of [FailureSentences] and the cleaning of server failure text in
 * [FailureSentences.jobFailed]. The cleaning runs in the order the spec gives: controls to one
 * space, the key removed, token-shaped runs redacted, trimmed, then cut to 160 units.
 * Tests that involve the key assert with fixed messages only.
 */
class FailureSentencesTest {

    private val allSentences: List<String> = listOf(
        FailureSentences.NO_ADDRESS,
        FailureSentences.ADDRESS_NOT_HTTPS,
        FailureSentences.NO_KEY,
        FailureSentences.KEY_NOT_USABLE,
        FailureSentences.REQUEST_NOT_USABLE,
        FailureSentences.KEY_REFUSED,
        FailureSentences.TOO_LARGE,
        FailureSentences.NO_ENDPOINT,
        FailureSentences.NOT_TAKING_WORK,
        FailureSentences.UNEXPECTED_STATUS,
        FailureSentences.JOB_GONE,
        FailureSentences.BAD_ANSWER,
        FailureSentences.UNREACHABLE,
        FailureSentences.TLS_FAILED,
        FailureSentences.CONNECTION_LOST,
        FailureSentences.NO_ANSWER_IN_TIME,
        FailureSentences.BUDGET_USED,
        FailureSentences.INTERRUPTED,
        FailureSentences.UNEXPECTED,
        FailureSentences.JOB_FAILED_PREFIX,
    )

    private val prefix = FailureSentences.JOB_FAILED_PREFIX

    /** A made-up key with no meaning outside this test. */
    private val sampleKey = "sample-key-7731"

    /** Surrogate halves of U+1F600, built from code units so the source stays ASCII. */
    private val emojiHigh = 0xD83D.toChar()
    private val emojiLow = 0xDE00.toChar()

    @Test
    fun `every sentence is plain ASCII`() {
        assertTrue("a sentence is not plain ASCII", allSentences.all { s -> s.all { it.code < 128 } })
    }

    @Test
    fun `every sentence is different from every other sentence`() {
        assertEquals(allSentences.size, allSentences.distinct().size)
    }

    @Test
    fun `no sentence contains a slash, so none can hold an address`() {
        assertTrue("a sentence contains a slash", allSentences.all { !it.contains('/') })
    }

    @Test
    fun `no sentence contains the word exception`() {
        assertTrue("a sentence contains the word exception", allSentences.all { !it.lowercase().contains("exception") })
    }

    @Test
    fun `no sentence has a token made only of digits`() {
        assertTrue(
            "a sentence has a token made only of digits",
            allSentences.all { s -> s.split(' ').none { t -> t.isNotEmpty() && t.all { c -> c.isDigit() } } },
        )
    }

    @Test
    fun `no sentence contains the sample key`() {
        assertTrue("a sentence contains the sample key", allSentences.all { !it.contains(sampleKey) })
    }

    @Test
    fun `a missing or blank server text gives the bare prefix`() {
        assertEquals(prefix, FailureSentences.jobFailed(null, sampleKey))
        assertEquals(prefix, FailureSentences.jobFailed("", sampleKey))
        assertEquals(prefix, FailureSentences.jobFailed("   ", sampleKey))
        assertEquals(prefix, FailureSentences.jobFailed("\n\t", sampleKey))
    }

    @Test
    fun `a blank key leaves the server text unchanged`() {
        assertEquals(prefix + "abc", FailureSentences.jobFailed("abc", ""))
        assertEquals(prefix + "abc", FailureSentences.jobFailed("abc", "  "))
    }

    @Test
    fun `control characters and line breaks each become one space`() {
        assertEquals(prefix + "a b c", FailureSentences.jobFailed("a\n\n\tb\u0001c", "x"))
    }

    @Test
    fun `the unicode line and paragraph separators become one space`() {
        val lineSeparator = 0x2028.toChar()
        val paragraphSeparator = 0x2029.toChar()
        val actual = FailureSentences.jobFailed("x" + lineSeparator + "y" + paragraphSeparator + "z", "k")
        assertTrue("the separators did not become one space each", actual == prefix + "x y z")
    }

    @Test
    fun `the key is removed from the server text`() {
        val actual = FailureSentences.jobFailed("token=$sampleKey;", sampleKey)
        assertTrue("the key was not removed from the server text", actual == prefix + "token=;")
    }

    @Test
    fun `the key is removed before the token rule so a long key leaves no redaction`() {
        val longKey = "abcdefghijklmnopqrstuvwxyz0123"
        val actual = FailureSentences.jobFailed("x $longKey y", longKey)
        assertTrue("the long key was redacted instead of removed", actual == prefix + "x  y")
    }

    @Test
    fun `a token run of at least 24 characters is replaced by the redacted marker`() {
        assertEquals(prefix + "run [redacted] end", FailureSentences.jobFailed("run ${"A".repeat(24)} end", sampleKey))
    }

    @Test
    fun `a token run of 23 characters is kept`() {
        assertEquals(prefix + "B".repeat(23), FailureSentences.jobFailed("B".repeat(23), sampleKey))
    }

    @Test
    fun `dot, tilde, plus, slash, equals and dash count as token characters`() {
        assertEquals(prefix + "[redacted]", FailureSentences.jobFailed("ab.~+/=-".repeat(3), sampleKey))
    }

    @Test
    fun `surrounding spaces are trimmed`() {
        assertEquals(prefix + "spaced", FailureSentences.jobFailed("  spaced  ", sampleKey))
    }

    @Test
    fun `the server text is cut at 160 units`() {
        assertEquals(prefix + "!".repeat(160), FailureSentences.jobFailed("!".repeat(200), sampleKey))
    }

    @Test
    fun `a text of exactly 160 units is not cut`() {
        assertEquals(prefix + "!".repeat(160), FailureSentences.jobFailed("!".repeat(160), sampleKey))
    }

    @Test
    fun `a surrogate pair that straddles the cut is dropped whole`() {
        val text = "!".repeat(159) + emojiHigh + emojiLow + "tail"
        assertEquals(prefix + "!".repeat(159), FailureSentences.jobFailed(text, sampleKey))
    }

    @Test
    fun `a surrogate pair inside the cut is kept`() {
        val text = "!".repeat(158) + emojiHigh + emojiLow + "tail"
        assertEquals(prefix + "!".repeat(158) + emojiHigh + emojiLow, FailureSentences.jobFailed(text, sampleKey))
    }

    @Test
    fun `text made only of controls or only of the key gives the bare prefix`() {
        assertEquals(prefix, FailureSentences.jobFailed("\u0001\n", sampleKey))
        val actual = FailureSentences.jobFailed(sampleKey, sampleKey)
        assertTrue("the key alone did not give the bare prefix", actual == prefix)
    }
}
