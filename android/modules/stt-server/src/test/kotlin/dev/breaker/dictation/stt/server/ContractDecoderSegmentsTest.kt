package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttResult
import dev.breaker.shared.api.Segment
import dev.breaker.shared.api.TranscriptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Segment times and segment text on the way from a done reply to an [SttResult.Success]. */
class ContractDecoderSegmentsTest {

    @Test
    fun `keeps every segment the server sent, in order`() {
        val success = successOf(
            """{"start":0.0,"end":1.0,"text":"one"},{"start":1.0,"end":2.0,"text":"two"}""",
        )
        assertEquals("segment count", 2, success.segments.size)
        assertEquals("first text", "one", success.segments[0].text)
        assertEquals("second text", "two", success.segments[1].text)
    }

    @Test
    fun `turns seconds into milliseconds and keeps start and end apart`() {
        val segment = successOf("""{"start":1.5,"end":3.0,"text":"x"}""").segments.single()
        assertEquals("start in ms", 1500L, segment.startMs)
        assertEquals("end in ms", 3000L, segment.endMs)
    }

    @Test
    fun `keeps the text of a segment exactly`() {
        val segment = successOf("""{"start":0.0,"end":1.0,"text":"Hello, world!"}""").segments.single()
        assertEquals("segment text", "Hello, world!", segment.text)
    }

    @Test
    fun `zero seconds become zero milliseconds`() {
        val segment = successOf("""{"start":0.0,"end":0.0,"text":"x"}""").segments.single()
        assertEquals("start", 0L, segment.startMs)
        assertEquals("end", 0L, segment.endMs)
    }

    @Test
    fun `rounds four ten-thousandths of a second down to zero milliseconds`() {
        val segment = successOf("""{"start":0.0004,"end":0.0004,"text":"x"}""").segments.single()
        assertEquals("start", 0L, segment.startMs)
    }

    @Test
    fun `rounds five ten-thousandths of a second up to one millisecond`() {
        val segment = successOf("""{"start":0.0005,"end":0.0005,"text":"x"}""").segments.single()
        assertEquals("start", 1L, segment.startMs)
    }

    @Test
    fun `accepts a segment whose start and end are equal`() {
        val segment = successOf("""{"start":1.0,"end":1.0,"text":"x"}""").segments.single()
        assertEquals("start", 1000L, segment.startMs)
        assertEquals("end", 1000L, segment.endMs)
    }

    @Test
    fun `accepts the largest allowed time of 31536000 seconds`() {
        val segment = successOf("""{"start":0.0,"end":31536000.0,"text":"x"}""").segments.single()
        assertEquals("end in ms", 31_536_000_000L, segment.endMs)
    }

    @Test
    fun `refuses a time just over the largest allowed`() {
        refused("end 31536000.5", """{"start":0.0,"end":31536000.5,"text":"x"}""")
    }

    @Test
    fun `refuses a time too large to be a number of seconds`() {
        refused("end 1e300", """{"start":0.0,"end":1e300,"text":"x"}""")
    }

    @Test
    fun `refuses an infinite time read from a number too large for a double`() {
        refused("end 1e400", """{"start":0.0,"end":1e400,"text":"x"}""")
    }

    @Test
    fun `refuses a negative start`() {
        refused("start -0.5", """{"start":-0.5,"end":1.0,"text":"x"}""")
    }

    @Test
    fun `refuses an end before the start`() {
        refused("end before start", """{"start":2.0,"end":1.0,"text":"x"}""")
    }

    @Test
    fun `refuses a time that is not a number when built directly`() {
        refusedDirect(Segment(Double.NaN, 1.0, "x"))
        refusedDirect(Segment(0.0, Double.POSITIVE_INFINITY, "x"))
    }

    /** Decodes a done reply with the given segment objects and converts it. */
    private fun successOf(segments: String): SttResult.Success {
        val body = """{"status":"done","result":{"text":"t","segments":[$segments],"language":"en"}}"""
        val job = ContractDecoder.job(body)
        return ContractDecoder.toSuccess(checkNotNull(job.result) { "done job must carry a result" })
    }

    private fun refused(label: String, segment: String) {
        try {
            successOf(segment)
        } catch (e: ContractViolation) {
            return
        }
        fail("accepted but must be refused: $label")
    }

    private fun refusedDirect(segment: Segment) {
        val result = TranscriptionResult("t", listOf(segment), "en")
        try {
            ContractDecoder.toSuccess(result)
        } catch (e: ContractViolation) {
            assertTrue("refusal carries no reply text", e.message.orEmpty().isNotEmpty())
            return
        }
        fail("accepted but must be refused: $segment")
    }
}
