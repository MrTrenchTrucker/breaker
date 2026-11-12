package dev.breaker.server.syncapi.transcriptions

import java.sql.Connection
import java.sql.ResultSet

// The read side of the older-rows route: one page of an account's live rows strictly older than a
// bound, newest first. It runs inside the transaction the store opens; it writes nothing.

// A page holds at most OLDER_PAGE_ROWS rows and at most OLDER_PAGE_BYTES of cost. The cost of a row is
// its encrypted length plus 200, as in the feed page; a record that alone passes the cap is still
// emitted alone, and the next row then sets more.
internal const val OLDER_PAGE_ROWS: Int = 50
internal const val OLDER_PAGE_BYTES: Long = 4L * 1024L * 1024L

/** One page of the older read: [records] newest first. [more] is true when a row was left for the next call. */
internal data class OlderPage(val records: List<TranscriptionPageRecord>, val more: Boolean)

internal object TranscriptionOlderReader {

    /**
     * One page of the account's live rows older than [bound], newest first. The query reads at most 51
     * rows; the cost loop keeps at most [OLDER_PAGE_ROWS] rows and [OLDER_PAGE_BYTES] of cost.
     * Writes nothing.
     */
    fun read(connection: Connection, accountId: Long, bound: OlderBound): OlderPage {
        val rows = connection.prepareStatement(SELECT_OLDER_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setLong(2, bound.storedAtMs)
            statement.setLong(3, bound.storedAtMs)
            // change_no is never negative, so with no change part (0) no row at exactly the bound time
            // passes change_no < 0: the bound is strict.
            statement.setLong(4, bound.change ?: 0L)
            statement.executeQuery().use { resultSet -> readRows(resultSet) }
        }
        var running = 0L
        val emitted = ArrayList<TranscriptionPageRecord>()
        for (row in rows) {
            val recordSize = row.encrypted.length + 200L
            if (emitted.isNotEmpty() &&
                (emitted.size >= OLDER_PAGE_ROWS || running + recordSize > OLDER_PAGE_BYTES)
            ) {
                break
            }
            emitted.add(
                TranscriptionPageRecord(
                    row.clientId,
                    row.changeNo,
                    false,
                    row.keyVersion,
                    row.encrypted,
                    row.storedAtMs,
                ),
            )
            running += recordSize
        }
        return OlderPage(emitted, emitted.size < rows.size)
    }

    private fun readRows(resultSet: ResultSet): List<PageRow> {
        val result = ArrayList<PageRow>()
        while (resultSet.next()) {
            result.add(
                PageRow(
                    resultSet.getString(1),
                    resultSet.getInt(2),
                    resultSet.getString(3),
                    resultSet.getLong(4),
                    resultSet.getLong(5),
                    resultSet.getInt(6),
                ),
            )
        }
        return result
    }

    // Newest first. LIMIT 51 is one row more than a page holds, so the reader can tell whether more follows.
    private const val SELECT_OLDER_SQL =
        "SELECT client_id, key_version, encrypted, stored_at_ms, change_no, deleted FROM transcriptions " +
            "WHERE account_id = ? AND deleted = 0 AND (stored_at_ms < ? OR (stored_at_ms = ? AND change_no < ?)) " +
            "ORDER BY stored_at_ms DESC, change_no DESC LIMIT 51"
}
