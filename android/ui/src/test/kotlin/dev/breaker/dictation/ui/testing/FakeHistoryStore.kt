package dev.breaker.dictation.ui.testing

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.HistoryStore

/** A text that only a failing [FakeHistoryStore] or [FakeClipboardSink] puts in its exception message. */
internal const val HISTORY_FAILURE_MARKER = "BOOM-HISTORY-4417"

/**
 * A [HistoryStore] held in memory. [rows] is what [list] returns, newest first, as the port
 * promises. [listCalls] holds the limit of every call to [list], in order, and [deleted] the
 * id of every call to [delete], failed or not. While [failList] or [failDelete] is set the
 * call throws with [HISTORY_FAILURE_MARKER] in its message; [refuseDelete] makes [delete]
 * return false and leave the rows as they were.
 */
internal class FakeHistoryStore(val rows: MutableList<Transcription> = mutableListOf()) : HistoryStore {
    val listCalls: MutableList<Int> = mutableListOf()
    val deleted: MutableList<String> = mutableListOf()
    var failList: Boolean = false
    var failDelete: Boolean = false
    var refuseDelete: Boolean = false

    override fun save(transcription: Transcription) {
        rows.add(0, transcription)
    }

    override fun list(limit: Int): List<Transcription> {
        listCalls.add(limit)
        if (failList) throw IllegalStateException("list failed: $HISTORY_FAILURE_MARKER")
        return rows.take(limit)
    }

    override fun delete(id: String): Boolean {
        deleted.add(id)
        if (failDelete) throw IllegalStateException("delete failed: $HISTORY_FAILURE_MARKER")
        if (refuseDelete) return false
        return rows.removeAll { it.id == id }
    }
}
