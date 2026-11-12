package dev.breaker.dictation.sync

import dev.breaker.dictation.core.port.OlderBound
import dev.breaker.dictation.core.port.OlderHistory
import dev.breaker.dictation.core.port.OlderResult
import dev.breaker.dictation.core.port.RecordKeys

/**
 * The public adapter that implements the core [OlderHistory] port over [OlderPullTransport].
 *
 * Constructor takes the transport, an optional [RecordKeys] port, a [deleteRow] lambda and an
 * [offered] lambda. The app supplies the lambdas: [deleteRow] enqueues a delete and asks the worker
 * to drain; [offered] is true when sync is on and an addressed session exists.
 *
 * [available] answers [offered]. [older] reads one page through [OlderPageReader]. [delete] runs
 * the [deleteRow] lambda and answers false on any exception, so a failing delete never breaks a read.
 */
class OlderHistorySyncAdapter(
    private val transport: OlderPullTransport,
    private val recordKeys: RecordKeys? = null,
    private val deleteRow: (String) -> Boolean,
    private val offered: () -> Boolean,
) : OlderHistory {

    override fun available(): Boolean = offered()

    override fun older(bound: OlderBound): OlderResult =
        OlderPageReader.read(transport, bound, recordKeys)

    override fun delete(id: String): Boolean =
        try {
            deleteRow(id)
        } catch (e: Exception) {
            false
        }
}
