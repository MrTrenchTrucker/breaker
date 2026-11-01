package dev.breaker.dictation.stt.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The address, key, field and job-id checks and the URL building in [ServerEndpoint].
 * A refused value returns one fixed sentence, and that sentence never echoes the value.
 * Key checks assert with fixed messages only, so a failing run cannot print a key.
 */
class ConfigChecksTest {

    @Test
    fun `a blank address is refused with the no-address sentence`() {
        assertEquals(FailureSentences.NO_ADDRESS, ServerEndpoint.addressProblem(null))
        assertEquals(FailureSentences.NO_ADDRESS, ServerEndpoint.addressProblem(""))
        assertEquals(FailureSentences.NO_ADDRESS, ServerEndpoint.addressProblem("   "))
    }

    // Changed because the rule changed: the engine now accepts a plain http address for the one address
    // it was given, so the two spellings that used to give the refusal sentence now give no problem.
    @Test
    fun `a plain http address is accepted whatever the case of its scheme`() {
        assertNull(ServerEndpoint.addressProblem("http://example.test"))
        assertNull(ServerEndpoint.addressProblem("HTTP://example.test"))
    }

    @Test
    fun `an https address is accepted whatever the case of its scheme`() {
        assertNull(ServerEndpoint.addressProblem("https://example.test"))
        assertNull(ServerEndpoint.addressProblem("HTTPS://example.test"))
        assertNull(ServerEndpoint.addressProblem("Https://example.test:8443"))
    }

    @Test
    fun `an address with no scheme or a cut short scheme is refused`() {
        assertEquals(FailureSentences.ADDRESS_NOT_HTTPS, ServerEndpoint.addressProblem("example.test"))
        assertEquals(FailureSentences.ADDRESS_NOT_HTTPS, ServerEndpoint.addressProblem("https:/example.test"))
    }

    @Test
    fun `an address with no host is refused`() {
        assertEquals(FailureSentences.ADDRESS_NOT_HTTPS, ServerEndpoint.addressProblem("https://"))
        assertEquals(FailureSentences.ADDRESS_NOT_HTTPS, ServerEndpoint.addressProblem("https:///path"))
        assertEquals(FailureSentences.ADDRESS_NOT_HTTPS, ServerEndpoint.addressProblem("https://:8443"))
    }

    @Test
    fun `an address with user information before the first slash is refused`() {
        assertEquals(FailureSentences.ADDRESS_NOT_HTTPS, ServerEndpoint.addressProblem("https://someone@example.test"))
        assertEquals(FailureSentences.ADDRESS_NOT_HTTPS, ServerEndpoint.addressProblem("https://someone:@example.test"))
    }

    @Test
    fun `an at sign after the first slash is not user information`() {
        assertNull(ServerEndpoint.addressProblem("https://example.test/a@b"))
    }

    @Test
    fun `an address with a space is refused whether the space is leading or inside`() {
        assertEquals(FailureSentences.ADDRESS_NOT_HTTPS, ServerEndpoint.addressProblem(" https://example.test"))
        assertEquals(FailureSentences.ADDRESS_NOT_HTTPS, ServerEndpoint.addressProblem("https://example.test /x"))
    }

    @Test
    fun `an address with a control character is refused`() {
        assertEquals(FailureSentences.ADDRESS_NOT_HTTPS, ServerEndpoint.addressProblem("https://example.test\n"))
        assertEquals(FailureSentences.ADDRESS_NOT_HTTPS, ServerEndpoint.addressProblem("https://exa\tmple.test"))
    }

    @Test
    fun `a trailing slash on the address is accepted`() {
        assertNull(ServerEndpoint.addressProblem("https://example.test/"))
    }

    @Test
    fun `a key that is null, empty or blank is refused with the no-key sentence`() {
        assertEquals(FailureSentences.NO_KEY, ServerEndpoint.keyProblem(null))
        assertEquals(FailureSentences.NO_KEY, ServerEndpoint.keyProblem(""))
        assertEquals(FailureSentences.NO_KEY, ServerEndpoint.keyProblem("   "))
    }

    @Test
    fun `a key with a carriage return, a line feed or a NUL is refused as not usable`() {
        assertEquals(FailureSentences.KEY_NOT_USABLE, ServerEndpoint.keyProblem("abc\rdef"))
        assertEquals(FailureSentences.KEY_NOT_USABLE, ServerEndpoint.keyProblem("abc\ndef"))
        assertEquals(FailureSentences.KEY_NOT_USABLE, ServerEndpoint.keyProblem("abc\u0000def"))
    }

    @Test
    fun `an ordinary key with spaces and symbols is accepted`() {
        assertNull(ServerEndpoint.keyProblem("sample key-value 123!"))
    }

    @Test
    fun `a model or language that is blank is refused with the request-not-usable sentence`() {
        assertEquals(FailureSentences.REQUEST_NOT_USABLE, ServerEndpoint.fieldProblem("", "en"))
        assertEquals(FailureSentences.REQUEST_NOT_USABLE, ServerEndpoint.fieldProblem("whisper-1", "  "))
    }

    @Test
    fun `a model or language with a double quote, a line break or a NUL is refused`() {
        assertEquals(FailureSentences.REQUEST_NOT_USABLE, ServerEndpoint.fieldProblem("a\"b", "en"))
        assertEquals(FailureSentences.REQUEST_NOT_USABLE, ServerEndpoint.fieldProblem("a\nb", "en"))
        assertEquals(FailureSentences.REQUEST_NOT_USABLE, ServerEndpoint.fieldProblem("a\rb", "en"))
        assertEquals(FailureSentences.REQUEST_NOT_USABLE, ServerEndpoint.fieldProblem("whisper-1", "e\u0000n"))
    }

    @Test
    fun `a field longer than 128 characters is refused and exactly 128 is accepted`() {
        assertEquals(FailureSentences.REQUEST_NOT_USABLE, ServerEndpoint.fieldProblem("x".repeat(129), "en"))
        assertNull(ServerEndpoint.fieldProblem("x".repeat(128), "en"))
    }

    @Test
    fun `ordinary model and language values are accepted`() {
        assertNull(ServerEndpoint.fieldProblem("whisper-1", "en"))
    }

    @Test
    fun `a job id of letters, digits, underscore and hyphen is accepted`() {
        assertTrue(ServerEndpoint.isJobIdUsable("abc_DEF-123"))
    }

    @Test
    fun `a job id of 128 characters is accepted and one of 129 is refused`() {
        assertTrue(ServerEndpoint.isJobIdUsable("a".repeat(128)))
        assertFalse(ServerEndpoint.isJobIdUsable("a".repeat(129)))
    }

    @Test
    fun `an empty job id is refused`() {
        assertFalse(ServerEndpoint.isJobIdUsable(""))
    }

    @Test
    fun `a job id with a slash, a dot or a space is refused`() {
        assertFalse(ServerEndpoint.isJobIdUsable("../x"))
        assertFalse(ServerEndpoint.isJobIdUsable("a/b"))
        assertFalse(ServerEndpoint.isJobIdUsable("a.b"))
        assertFalse(ServerEndpoint.isJobIdUsable("a b"))
    }

    @Test
    fun `the transcription URL is the base with the transcriptions path`() {
        assertEquals(
            "https://example.test/v1/audio/transcriptions",
            ServerEndpoint.transcriptionsUrl("https://example.test"),
        )
    }

    @Test
    fun `one trailing slash on the base is dropped before the path is added`() {
        assertEquals(
            "https://example.test/v1/audio/transcriptions",
            ServerEndpoint.transcriptionsUrl("https://example.test/"),
        )
        assertEquals("https://example.test/v1/jobs/job-1", ServerEndpoint.jobUrl("https://example.test/", "job-1"))
    }

    @Test
    fun `a path on the base stays in front of the transcriptions path`() {
        assertEquals(
            "https://example.test/base/v1/audio/transcriptions",
            ServerEndpoint.transcriptionsUrl("https://example.test/base"),
        )
    }

    @Test
    fun `the job URL is the base with the jobs path and the job id`() {
        assertEquals("https://example.test/v1/jobs/job-1", ServerEndpoint.jobUrl("https://example.test", "job-1"))
    }

    @Test
    fun `the job URL refuses a job id that is not usable`() {
        try {
            ServerEndpoint.jobUrl("https://example.test", "../x")
            fail("a job URL was built from a job id with a slash")
        } catch (e: IllegalArgumentException) {
            assertEquals("job id is not usable", e.message)
        }
    }
}
