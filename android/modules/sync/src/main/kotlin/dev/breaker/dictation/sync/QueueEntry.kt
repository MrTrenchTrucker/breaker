package dev.breaker.dictation.sync

/**
 * One queued row, as the database stored it.
 *
 * The fields are the stored values. [source] is the stored text: "local" or
 * "server" for a row this module wrote. It is not checked here. The queue checks
 * it before it builds a push, so a row with any other source is skipped and a
 * read never fails because of it.
 *
 * [seq] is the position the database gave the row when it was queued. A lower
 * [seq] was queued earlier, and the queue drains in that order. A [seq] is never
 * reused after its row is removed.
 *
 * [toString] prints the ids, source, model and timing and the length of the
 * text, never the text.
 */
internal class QueueEntry(
    val seq: Long,
    val clientId: String,
    val text: String,
    val source: String,
    val model: String,
    val durationMs: Long,
    val createdAtMs: Long,
) {

    override fun toString(): String =
        "QueueEntry(seq=$seq, clientId=$clientId, source=$source, model=$model, " +
            "durationMs=$durationMs, createdAtMs=$createdAtMs, ${text.length} chars)"
}
