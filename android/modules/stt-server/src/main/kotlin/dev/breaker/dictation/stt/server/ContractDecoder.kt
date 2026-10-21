package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.model.SttSegment
import dev.breaker.shared.api.Job
import dev.breaker.shared.api.JobAccepted
import dev.breaker.shared.api.JobStatus
import dev.breaker.shared.api.Segment
import dev.breaker.shared.api.TranscriptionResult

/**
 * Raised when a reply does not match the job contract. The message is one of a few
 * fixed sentences and never carries any of the reply text.
 */
internal class ContractViolation(message: String) : Exception(message)

/**
 * Turns the JSON text of the server's job replies into the api-contracts types, and
 * turns a finished result into an [SttResult.Success].
 *
 * Every problem with a reply ends as [ContractViolation]. The api-contracts types are
 * built directly, so their own invariants apply too, and their refusals are mapped to
 * the same violation. Status text goes through the contract type's own
 * [JobStatus.fromString], so it is matched the same way the contract type matches it.
 */
internal object ContractDecoder {
    /** Largest segment time accepted, in seconds: one non-leap year. */
    private const val MAX_SEGMENT_SECONDS: Double = 31_536_000.0

    private const val MSG_NOT_JSON = "reply is not valid JSON"
    private const val MSG_NOT_OBJECT = "reply is not a JSON object"
    private const val MSG_FIELD = "reply field is missing or has the wrong type"
    private const val MSG_STATUS = "reply status is not a known job status"
    private const val MSG_CONTRACT = "reply breaks the job contract"
    private const val MSG_SEGMENT = "segment times are not usable"

    /** Decodes the answer to an enqueue call: a job id with status queued. */
    fun jobAccepted(body: String): JobAccepted = guard {
        val root = objectOf(JsonReader.parse(body))
        val jobId = stringField(root, "job_id")
        JobAccepted(jobId, statusField(root))
    }

    /** Decodes a poll answer: a status, plus a result when done and an error when failed. */
    fun job(body: String): Job = guard {
        val root = objectOf(JsonReader.parse(body))
        val status = statusField(root)
        Job(status, optionalResult(root.fields["result"]), optionalString(root.fields["error"]))
    }

    /** Turns a done result into a success. Refuses any segment time that cannot be used. */
    fun toSuccess(result: TranscriptionResult): SttResult.Success {
        val segments = result.segments.map { toSttSegment(it) }
        return SttResult.Success(result.text, segments, result.language)
    }

    private fun toSttSegment(segment: Segment): SttSegment {
        val start = segment.start
        val end = segment.end
        val usable = start.isFinite() && end.isFinite() &&
            start >= 0.0 && end >= start &&
            start <= MAX_SEGMENT_SECONDS && end <= MAX_SEGMENT_SECONDS
        if (!usable) throw ContractViolation(MSG_SEGMENT)
        return SttSegment(toMillis(start), toMillis(end), segment.text)
    }

    /** Seconds to whole milliseconds, rounded half up. Inputs are never negative here, so truncation is floor. */
    private fun toMillis(seconds: Double): Long = (seconds * 1000.0 + 0.5).toLong()

    /** Runs a decode and maps the reader's and the contract types' refusals to [ContractViolation]. */
    private inline fun <T> guard(decode: () -> T): T =
        try {
            decode()
        } catch (e: JsonSyntaxException) {
            throw ContractViolation(MSG_NOT_JSON)
        } catch (e: IllegalArgumentException) {
            throw ContractViolation(MSG_CONTRACT)
        }

    private fun objectOf(value: JsonValue): JsonValue.Obj =
        value as? JsonValue.Obj ?: throw ContractViolation(MSG_NOT_OBJECT)

    private fun stringField(obj: JsonValue.Obj, name: String): String =
        (obj.fields[name] as? JsonValue.Str)?.value ?: throw ContractViolation(MSG_FIELD)

    private fun numberField(obj: JsonValue.Obj, name: String): Double {
        val number = obj.fields[name] as? JsonValue.Num ?: throw ContractViolation(MSG_FIELD)
        return number.text.toDouble()
    }

    private fun statusField(obj: JsonValue.Obj): JobStatus {
        val text = stringField(obj, "status")
        return JobStatus.fromString(text) ?: throw ContractViolation(MSG_STATUS)
    }

    private fun optionalString(value: JsonValue?): String? = when (value) {
        null, JsonValue.Null -> null
        is JsonValue.Str -> value.value
        else -> throw ContractViolation(MSG_FIELD)
    }

    private fun optionalResult(value: JsonValue?): TranscriptionResult? = when (value) {
        null, JsonValue.Null -> null
        is JsonValue.Obj -> transcriptionResult(value)
        else -> throw ContractViolation(MSG_FIELD)
    }

    private fun transcriptionResult(obj: JsonValue.Obj): TranscriptionResult {
        val items = (obj.fields["segments"] as? JsonValue.Arr)?.items
            ?: throw ContractViolation(MSG_FIELD)
        return TranscriptionResult(
            text = stringField(obj, "text"),
            segments = items.map { segmentOf(it) },
            language = stringField(obj, "language"),
        )
    }

    private fun segmentOf(value: JsonValue): Segment {
        val obj = objectOf(value)
        return Segment(numberField(obj, "start"), numberField(obj, "end"), stringField(obj, "text"))
    }
}
