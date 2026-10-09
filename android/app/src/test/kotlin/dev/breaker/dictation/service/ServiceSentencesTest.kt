package dev.breaker.dictation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact words of the three sentences the service controller answers with. They are shown to the
 * user as they are, so a change of wording must be a deliberate edit of this test.
 */
internal class ServiceSentencesTest {

    @Test
    fun `the missing microphone permission sentence has its agreed words`() {
        assertEquals(
            "app: the missing-permission sentence changed",
            "Breaker cannot listen because microphone access is off. Turn it on in Settings, Apps, Breaker, Permissions.",
            ServiceSentences.MIC_PERMISSION_MISSING,
        )
    }

    @Test
    fun `the arm refused sentence has its agreed words`() {
        assertEquals(
            "app: the arm-refused sentence changed",
            "Android did not let Breaker start listening. Open Breaker and try again.",
            ServiceSentences.ARM_REFUSED,
        )
    }

    @Test
    fun `the cold start refused sentence has its agreed words`() {
        assertEquals(
            "app: the cold-start-refused sentence changed",
            "Open Breaker once to switch dictation on.",
            ServiceSentences.COLD_START_REFUSED,
        )
    }

    private val avoided = Regex("""\b(?:accessibility|overlay|foreground|service|permission|debug)\w*|\b(?:api|sdk)s?\b""", RegexOption.IGNORE_CASE)
    private val avoidedButPermission = Regex("""\b(?:accessibility|overlay|foreground|service|debug)\w*|\b(?:api|sdk)s?\b""", RegexOption.IGNORE_CASE)

    private fun plain(name: String, sentence: String, words: Regex, longest: Int) {
        assertTrue("app: the $name sentence is blank", sentence.isNotBlank())
        assertTrue("app: the $name sentence is not ASCII", sentence.all { it.code in 32..126 })
        assertFalse("app: the $name sentence holds a word the notification texts avoid", words.containsMatchIn(sentence))
        assertTrue("app: the $name sentence is over $longest characters", sentence.length <= longest)
    }

    @Test
    fun `the arm refused sentence is short plain ASCII without the avoided words`() {
        plain("arm-refused", ServiceSentences.ARM_REFUSED, avoided, 80)
    }

    @Test
    fun `the missing microphone permission sentence is short plain ASCII and may name Permissions`() {
        plain("missing-permission", ServiceSentences.MIC_PERMISSION_MISSING, avoidedButPermission, 110)
    }
}
