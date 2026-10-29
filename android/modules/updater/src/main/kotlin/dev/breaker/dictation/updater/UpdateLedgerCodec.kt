package dev.breaker.dictation.updater

/**
 * The values of the ledger file. A null time means the key holds no value. [record] is null when no install session
 * is recorded. [lastSeenCode] and [lastOutcome] are null when no value is stored.
 */
internal data class LedgerValues(
    val definitiveCheckMs: Long?,
    val attemptMs: Long?,
    val updateMs: Long?,
    val record: LedgerRecord?,
    val lastSeenCode: Int?,
    val lastOutcome: String?,
) {
    companion object {
        /** Every value unset: what a missing or a damaged file reads as. */
        val EMPTY: LedgerValues = LedgerValues(null, null, null, null, null, null)
    }
}

/** The install session that is in flight: its id, the time it was recorded, and the final status stored for it, if any. */
internal data class LedgerRecord(val sessionId: Int, val writtenAtMs: Long, val finalStatus: String?)

/** One optional field in an update: keep the stored value, put a new one, or clear it. */
internal sealed class FieldEdit<out T> {
    /** Leaves the stored value as it is. */
    internal object Keep : FieldEdit<Nothing>()

    /** Replaces the stored value with [value]. */
    internal class Put<T>(val value: T) : FieldEdit<T>()

    /** Removes the stored value. */
    internal object Clear : FieldEdit<Nothing>()
}

/** The value of this edit applied to the stored [current] value. */
internal fun <T> FieldEdit<T>.applyTo(current: T?): T? = when (this) {
    is FieldEdit.Keep -> current
    is FieldEdit.Put<T> -> value
    is FieldEdit.Clear -> null
}

/**
 * The text of the ledger file: one key=value line per field, ASCII, LF line ends, and a final LF. A text that breaks
 * one of these rules is damaged, and a damaged text reads as [LedgerValues.EMPTY] as a whole. An empty time is null;
 * an empty record key or code is damaged.
 */
internal object LedgerCodec {
    private const val DEFINITIVE_KEY = "definitive_check_ms"
    private const val ATTEMPT_KEY = "attempt_ms"
    private const val UPDATE_KEY = "update_ms"
    private const val SESSION_KEY = "record_session_id"
    private const val WRITTEN_AT_KEY = "record_written_at_ms"
    private const val FINAL_STATUS_KEY = "record_final_status"
    private const val LAST_SEEN_KEY = "last_seen_code"
    private const val LAST_OUTCOME_KEY = "last_outcome"
    private const val MAX_STATUS_LENGTH = 32
    private const val MAX_OUTCOME_LENGTH = 200
    private const val MAX_DECIMAL_DIGITS = 19
    private val INT_LIMIT: Long = Int.MAX_VALUE.toLong()
    private val KNOWN_KEYS: Set<String> = setOf(
        DEFINITIVE_KEY,
        ATTEMPT_KEY,
        UPDATE_KEY,
        SESSION_KEY,
        WRITTEN_AT_KEY,
        FINAL_STATUS_KEY,
        LAST_SEEN_KEY,
        LAST_OUTCOME_KEY,
    )

    /** The text of [values]. A value that the decoder would reject is refused before any text is made. */
    fun encode(values: LedgerValues): String = buildString {
        appendField(DEFINITIVE_KEY, timeText(values.definitiveCheckMs))
        appendField(ATTEMPT_KEY, timeText(values.attemptMs))
        appendField(UPDATE_KEY, timeText(values.updateMs))
        val record = values.record
        if (record != null) {
            appendField(SESSION_KEY, nonNegative(record.sessionId.toLong()).toString())
            appendField(WRITTEN_AT_KEY, nonNegative(record.writtenAtMs).toString())
            val status = record.finalStatus
            if (status != null) appendField(FINAL_STATUS_KEY, requireStatus(status))
        }
        val code = values.lastSeenCode
        if (code != null) appendField(LAST_SEEN_KEY, nonNegative(code.toLong()).toString())
        val outcome = values.lastOutcome
        if (outcome != null) appendField(LAST_OUTCOME_KEY, requireOutcome(outcome))
    }

    /** The values that [text] holds, or [LedgerValues.EMPTY] when the text is damaged. */
    fun decode(text: String): LedgerValues =
        try {
            parse(text)
        } catch (damage: DamagedLedgerText) {
            LedgerValues.EMPTY
        }

    private fun parse(text: String): LedgerValues {
        if (!text.endsWith('\n')) damaged()
        if (!text.all { it == '\n' || it in ' '..'~' }) damaged()
        val fields = mutableMapOf<String, String>()
        for (line in text.substring(0, text.length - 1).split('\n')) {
            val cut = line.indexOf('=')
            if (cut <= 0) damaged()
            val key = line.substring(0, cut)
            if (key !in KNOWN_KEYS || key in fields) damaged()
            fields[key] = line.substring(cut + 1)
        }
        return valuesOf(fields)
    }

    private fun valuesOf(fields: Map<String, String>): LedgerValues {
        val session = optionalDecimal(fields[SESSION_KEY], INT_LIMIT)
        val writtenAt = optionalDecimal(fields[WRITTEN_AT_KEY], Long.MAX_VALUE)
        val status = fields[FINAL_STATUS_KEY]
        if (status != null && !isStatusName(status)) damaged()
        val outcome = fields[LAST_OUTCOME_KEY]
        if (outcome != null && !isOutcomeText(outcome)) damaged()
        val record = when {
            session != null && writtenAt != null -> LedgerRecord(session.toInt(), writtenAt, status)
            session == null && writtenAt == null && status == null -> null
            else -> damaged()
        }
        return LedgerValues(
            timeValue(fields[DEFINITIVE_KEY]),
            timeValue(fields[ATTEMPT_KEY]),
            timeValue(fields[UPDATE_KEY]),
            record,
            optionalDecimal(fields[LAST_SEEN_KEY], INT_LIMIT)?.toInt(),
            outcome,
        )
    }

    /** A time: null when the key is absent or empty; damaged when it is not a canonical decimal. */
    private fun timeValue(raw: String?): Long? =
        if (raw == null || raw.isEmpty()) null else canonicalDecimal(raw, Long.MAX_VALUE) ?: damaged()

    /** A record key or a code: null when the key is absent; damaged when it is not a canonical decimal up to [max]. */
    private fun optionalDecimal(raw: String?, max: Long): Long? =
        if (raw == null) null else canonicalDecimal(raw, max) ?: damaged()

    /** The value of [text] when it is a canonical non-negative decimal no larger than [max]; otherwise null. */
    private fun canonicalDecimal(text: String, max: Long): Long? {
        if (text == "0") return 0L
        if (text.isEmpty() || text.length > MAX_DECIMAL_DIGITS || text[0] !in '1'..'9') return null
        var value = 0L
        for (ch in text) {
            if (ch !in '0'..'9') return null
            val digit = (ch - '0').toLong()
            if (value > (max - digit) / 10L) return null
            value = value * 10L + digit
        }
        return value
    }

    private fun isStatusName(text: String): Boolean =
        text.length in 1..MAX_STATUS_LENGTH && text.all { it in 'A'..'Z' || it in 'a'..'z' }

    private fun isOutcomeText(text: String): Boolean =
        text.length in 1..MAX_OUTCOME_LENGTH && text.all { it in ' '..'~' }

    private fun timeText(ms: Long?): String = if (ms == null) "" else nonNegative(ms).toString()

    private fun nonNegative(value: Long): Long {
        require(value >= 0L)
        return value
    }

    private fun requireStatus(text: String): String {
        require(isStatusName(text))
        return text
    }

    private fun requireOutcome(text: String): String {
        require(isOutcomeText(text))
        return text
    }

    private fun StringBuilder.appendField(key: String, value: String) {
        append(key).append('=').append(value).append('\n')
    }
}

/** Thrown inside the decoder when the text breaks a rule; [LedgerCodec.decode] turns it into the empty values. */
private class DamagedLedgerText : RuntimeException()

private fun damaged(): Nothing = throw DamagedLedgerText()
