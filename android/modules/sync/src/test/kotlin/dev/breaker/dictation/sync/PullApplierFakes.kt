package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.PullSink
import kotlin.collections.ArrayList
import kotlin.collections.HashSet

/** A recording PullSink: every call remembered in order, answers set by the test. */
internal class RecordingPullSink : PullSink {

    data class DeleteCall(val id: String, val deletedAt: Long)
    data class RowCall(val row: Transcription)
    data class RetainCall(val keep: Set<String>)

    val deletes = ArrayList<DeleteCall>()
    val rows = ArrayList<RowCall>()
    val retains = ArrayList<RetainCall>()

    var rowAnswer = true
    var deleteAnswer = true
    var retainAnswer = 0
    var throwOn: Throwable? = null

    override fun applyRemoteDelete(id: String, deletedAt: Long): Boolean {
        throwOn?.let { throw it }
        deletes.add(DeleteCall(id, deletedAt))
        return deleteAnswer
    }

    override fun applyRemoteRow(row: Transcription): Boolean {
        throwOn?.let { throw it }
        rows.add(RowCall(row))
        return rowAnswer
    }

    override fun retainOnly(keep: Set<String>): Int {
        throwOn?.let { throw it }
        retains.add(RetainCall(keep.toSet()))
        return retainAnswer
    }

    fun rowIds(): List<String> = rows.map { it.row.id }
    fun deleteIds(): List<String> = deletes.map { it.id }
    fun deleteCalls(): List<String> = deletes.map { "${it.id}@${it.deletedAt}" }
    fun retainKeeps(): List<Set<String>> = retains.map { it.keep }
}

/** A recording QueueProbe: a set of queued ids, an ordered queuedIds list, a call counter. */
internal class RecordingQueueProbe : QueueProbe {

    val queued = HashSet<String>()
    var queuedIdsList: List<String> = emptyList()
    var isQueuedCalls = 0
    var queuedIdsCalls = 0

    override fun isQueued(clientId: String): Boolean {
        isQueuedCalls++
        return clientId in queued
    }

    override fun queuedIds(): List<String> {
        queuedIdsCalls++
        return queuedIdsList
    }
}
