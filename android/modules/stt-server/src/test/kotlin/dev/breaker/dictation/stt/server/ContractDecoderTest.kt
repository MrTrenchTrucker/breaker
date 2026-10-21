package dev.breaker.dictation.stt.server

import dev.breaker.shared.api.JobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Job replies decoded into the api-contracts types, and every refusal. */
class ContractDecoderTest {

    private val doneResult =
        """{"text":"Hello","segments":[{"start":0.0,"end":1.0,"text":"Hello"}],"language":"en"}"""

    @Test
    fun `decodes a queued accepted reply into a queued job accepted`() {
        val accepted = ContractDecoder.jobAccepted("""{"job_id":"job-1","status":"queued"}""")
        assertEquals("job id", "job-1", accepted.jobId)
        assertEquals("status", JobStatus.QUEUED, accepted.status)
    }

    @Test
    fun `status text follows the contract type JobStatus fromString so upper case DONE decodes`() {
        val job = ContractDecoder.job("""{"status":"DONE","result":$doneResult}""")
        assertEquals("upper-case DONE", JobStatus.DONE, job.status)
        val accepted = ContractDecoder.jobAccepted("""{"job_id":"job-1","status":"Queued"}""")
        assertEquals("mixed-case Queued", JobStatus.QUEUED, accepted.status)
    }

    @Test
    fun `decodes a queued job with no result and no error`() {
        val job = ContractDecoder.job("""{"status":"queued"}""")
        assertEquals("status", JobStatus.QUEUED, job.status)
        assertNull("result", job.result)
        assertNull("error", job.error)
    }

    @Test
    fun `decodes a processing job with a null result`() {
        val job = ContractDecoder.job("""{"status":"processing","result":null}""")
        assertEquals("status", JobStatus.PROCESSING, job.status)
        assertNull("result", job.result)
    }

    @Test
    fun `decodes a done job with its text language and segments`() {
        val job = ContractDecoder.job("""{"status":"done","result":$doneResult}""")
        assertEquals("status", JobStatus.DONE, job.status)
        val result = checkNotNull(job.result) { "done job must carry a result" }
        assertEquals("text", "Hello", result.text)
        assertEquals("language", "en", result.language)
        assertEquals("segment count", 1, result.segments.size)
    }

    @Test
    fun `decodes a failed job carrying its error text`() {
        val job = ContractDecoder.job("""{"status":"failed","error":"whisper exploded"}""")
        assertEquals("status", JobStatus.FAILED, job.status)
        assertEquals("error", "whisper exploded", job.error)
    }

    @Test
    fun `accepts a null error on a done job and ignores extra unknown fields`() {
        val job = ContractDecoder.job(
            """{"status":"done","result":$doneResult,"error":null,"extra":{"x":[1,2]},"note":"hi"}""",
        )
        assertEquals("status", JobStatus.DONE, job.status)
        assertNull("error", job.error)
    }

    @Test
    fun `ignores extra unknown fields on the accepted reply`() {
        val accepted = ContractDecoder.jobAccepted("""{"job_id":"j","status":"queued","extra":1}""")
        assertEquals("job id", "j", accepted.jobId)
    }

    @Test
    fun `toSuccess keeps the text and the language of the result`() {
        val job = ContractDecoder.job("""{"status":"done","result":$doneResult}""")
        val success = ContractDecoder.toSuccess(checkNotNull(job.result) { "result" })
        assertEquals("text", "Hello", success.text)
        assertEquals("language", "en", success.language)
        assertEquals("segment text", "Hello", success.segments.single().text)
    }

    @Test
    fun `refuses an accepted reply whose status is not queued`() {
        refusal("processing accepted") { ContractDecoder.jobAccepted("""{"job_id":"j","status":"processing"}""") }
    }

    @Test
    fun `refuses an accepted reply with no job id`() {
        refusal("no job id") { ContractDecoder.jobAccepted("""{"status":"queued"}""") }
    }

    @Test
    fun `refuses an accepted reply whose job id is a number`() {
        refusal("numeric job id") { ContractDecoder.jobAccepted("""{"job_id":7,"status":"queued"}""") }
    }

    @Test
    fun `refuses a job reply with no status`() {
        refusal("no status") { ContractDecoder.job("""{"result":null}""") }
    }

    @Test
    fun `refuses a status word the contract does not know`() {
        refusal("unknown status") { ContractDecoder.job("""{"status":"bogus"}""") }
    }

    @Test
    fun `refuses a status that is not text`() {
        refusal("numeric status") { ContractDecoder.job("""{"status":1}""") }
    }

    @Test
    fun `refuses a done job with no result`() {
        refusal("done without result") { ContractDecoder.job("""{"status":"done"}""") }
    }

    @Test
    fun `refuses a done job whose result is null`() {
        refusal("done with null result") { ContractDecoder.job("""{"status":"done","result":null}""") }
    }

    @Test
    fun `refuses a failed job with no error text`() {
        refusal("failed without error") { ContractDecoder.job("""{"status":"failed"}""") }
    }

    @Test
    fun `refuses a failed job whose error is null`() {
        refusal("failed with null error") { ContractDecoder.job("""{"status":"failed","error":null}""") }
    }

    @Test
    fun `refuses a queued job that carries a result`() {
        refusal("queued with result") { ContractDecoder.job("""{"status":"queued","result":$doneResult}""") }
    }

    @Test
    fun `refuses an error that is a number`() {
        refusal("numeric error") { ContractDecoder.job("""{"status":"failed","error":5}""") }
    }

    @Test
    fun `refuses a result that is text`() {
        refusal("text result") { ContractDecoder.job("""{"status":"done","result":"x"}""") }
    }

    @Test
    fun `refuses a result with no text field`() {
        refusal("result without text") {
            ContractDecoder.job("""{"status":"done","result":{"segments":[],"language":"en"}}""")
        }
    }

    @Test
    fun `refuses a result with no language and a result with no segments`() {
        refusal("result without language") {
            ContractDecoder.job("""{"status":"done","result":{"text":"x","segments":[]}}""")
        }
        refusal("result without segments") {
            ContractDecoder.job("""{"status":"done","result":{"text":"x","language":"en"}}""")
        }
    }

    @Test
    fun `refuses a result whose segments are not an array`() {
        refusal("segments as text") {
            ContractDecoder.job("""{"status":"done","result":{"text":"x","segments":"x","language":"en"}}""")
        }
    }

    @Test
    fun `refuses a segment with no end time`() {
        refusal("segment without end") {
            ContractDecoder.job(
                """{"status":"done","result":{"text":"x","segments":[{"start":0.0,"text":"x"}],"language":"en"}}""",
            )
        }
    }

    @Test
    fun `refuses a segment whose text is a number and whose start is text`() {
        refusal("numeric segment text") {
            ContractDecoder.job(
                """{"status":"done","result":{"text":"x","segments":[{"start":0.0,"end":1.0,"text":5}],"language":"en"}}""",
            )
        }
        refusal("text segment start") {
            ContractDecoder.job(
                """{"status":"done","result":{"text":"x","segments":[{"start":"0","end":1.0,"text":"x"}],"language":"en"}}""",
            )
        }
    }

    @Test
    fun `refuses a reply that is an array or is not JSON`() {
        refusal("array reply") { ContractDecoder.job("[]") }
        refusal("unquoted keys") { ContractDecoder.job("{status: done}") }
    }

    @Test
    fun `violation messages never carry reply text`() {
        val messages = listOf(
            refusal("status text") { ContractDecoder.job("""{"status":"$CANARY"}""") },
            refusal("not JSON") { ContractDecoder.job(CANARY) },
            refusal("wrong type") { ContractDecoder.job("""{"status":"done","result":"$CANARY"}""") },
            refusal("accepted job id") {
                ContractDecoder.jobAccepted("""{"job_id":"$CANARY","status":"processing"}""")
            },
        )
        for (message in messages) {
            assertFalse("violation must not carry the reply text", message.contains(CANARY))
        }
    }

    /** Runs [decode], requires a [ContractViolation], and returns its message. */
    private fun refusal(label: String, decode: () -> Any): String {
        try {
            decode()
        } catch (e: ContractViolation) {
            return e.message.orEmpty()
        }
        fail("accepted but must be refused: $label")
        return ""
    }

    private companion object {
        const val CANARY = "CANARY-7f3a"
    }
}
