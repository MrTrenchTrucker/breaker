package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The progress sentences and the megabyte helpers the download shows; MB means 1,000,000 bytes. */
internal class ModelSentencesProgressTest {

    private val forbidden = Regex("""\b(?:accessibility|overlay|foreground|service|permission|debug)\w*|\b(?:api|sdk)s?\b""", RegexOption.IGNORE_CASE)

    @Test
    fun `the progress sentences have their exact words`() {
        assertEquals(
            "app: the known-total progress sentence",
            "Downloading the speech model: 120 of 366 MB.",
            ModelSentences.progressFor(120, 366),
        )
        assertEquals(
            "app: the unknown-total progress sentence",
            "Downloading the speech model: 120 MB so far.",
            ModelSentences.progressUnknownFor(120),
        )
        assertEquals("app: the checking sentence", "Checking the speech model.", ModelSentences.CHECKING)
    }

    @Test
    fun `megabytes done are whole megabytes with the part dropped`() {
        assertEquals("app: 999,999 bytes is 0 MB", 0, ModelSentences.doneMb(999_999L))
        assertEquals("app: 1,000,000 bytes is 1 MB", 1, ModelSentences.doneMb(1_000_000L))
        assertEquals("app: 365,748,162 bytes is 365 MB", 365, ModelSentences.doneMb(365_748_162L))
    }

    @Test
    fun `megabytes total are rounded to the nearest megabyte`() {
        assertEquals("app: 499,999 bytes rounds to 0 MB", 0, ModelSentences.totalMb(499_999L))
        assertEquals("app: 500,000 bytes rounds to 1 MB", 1, ModelSentences.totalMb(500_000L))
        assertEquals("app: 365,748,162 bytes rounds to 366 MB", 366, ModelSentences.totalMb(365_748_162L))
    }

    @Test
    fun `each progress sentence is short plain ASCII, ends with a full stop and avoids the notification words`() {
        val sentences = listOf(
            ModelSentences.progressFor(120, 366),
            ModelSentences.progressUnknownFor(120),
            ModelSentences.CHECKING,
            ModelSentences.progressFor(1818, 1909),
        )
        for (sentence in sentences) {
            assertTrue("app: '$sentence' is over 63 characters", sentence.length <= 63)
            assertTrue("app: '$sentence' is not ASCII", sentence.all { it.code in 32..126 })
            assertTrue("app: '$sentence' must end like a sentence", sentence.endsWith("."))
            assertFalse("app: '$sentence' holds a word the notification texts avoid", forbidden.containsMatchIn(sentence))
        }
    }

    @Test
    fun `the progress sentences differ from each other and from the four model sentences`() {
        val all = listOf(
            ModelSentences.NO_MODEL,
            ModelSentences.downloadingFor(349),
            ModelSentences.DOWNLOAD_FAILED,
            ModelSentences.DOWNLOAD_NO_SPACE,
            ModelSentences.progressFor(120, 366),
            ModelSentences.progressUnknownFor(120),
            ModelSentences.CHECKING,
        )
        assertEquals("app: the seven sentences are different", 7, all.toSet().size)
    }
}
