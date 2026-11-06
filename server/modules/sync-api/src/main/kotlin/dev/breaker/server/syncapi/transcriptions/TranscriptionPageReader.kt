package dev.breaker.server.syncapi.transcriptions

import java.sql.Connection

// The read side of the changes-since feed: one page of an account's transcriptions in change order.
// It runs inside the transaction the store opens; it writes nothing.
internal object TranscriptionPageReader {

    /** One page of the account's feed, ordered by change number ascending. Runs in one read
     * transaction: head and purged_through are read first, then the gone check, then the rows.
     * [since] is null for the first page (live rows only), otherwise the exclusive change cursor. */
    fun read(connection: Connection, accountId: Long, since: Long?): PageResult {
        val (head, purgedThrough) =
            connection.prepareStatement(SELECT_HEAD_AND_PURGED_SQL).use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api: no account $accountId" }
                    rows.getLong(1) to rows.getLong(2)
                }
            }

        if (since != null && since < purgedThrough) return PageResult.Gone

        val rows = if (since != null) {
            readPageRows(connection, accountId, since)
        } else {
            readLiveRows(connection, accountId)
        }

        val capSize = PAGE_SIZE_CAP
        val maxRecords = PAGE_MAX_RECORDS
        var running = 0L
        val emitted = ArrayList<TranscriptionPageRecord>()
        var more = false
        var scanned = 0
        for (row in rows) {
            scanned++
            val recordSize = if (row.deleted == 1) 201L else row.encrypted.length + 200L
            // A record that alone passes the cap is still emitted alone; the next row then
            // sets more.
            if (emitted.isNotEmpty() && (emitted.size >= maxRecords || running + recordSize > capSize)) {
                more = true
                break
            }
            emitted.add(pageRecord(row))
            running += recordSize
        }
        if (scanned > emitted.size) more = true

        val next = if (more && emitted.isNotEmpty()) emitted.last().change else head
        return PageResult.Page(emitted, next, more)
    }

    private fun pageRecord(row: PageRow): TranscriptionPageRecord =
        TranscriptionPageRecord(
            row.clientId,
            row.changeNo,
            row.deleted == 1,
            if (row.deleted == 1) null else row.keyVersion,
            if (row.deleted == 1) null else row.encrypted,
            row.storedAtMs,
        )

    private fun readPageRows(connection: Connection, accountId: Long, since: Long): List<PageRow> =
        connection.prepareStatement(SELECT_PAGE_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setLong(2, since)
            statement.executeQuery().use { rows -> readPageRowsFrom(rows) }
        }

    private fun readLiveRows(connection: Connection, accountId: Long): List<PageRow> =
        connection.prepareStatement(SELECT_LIVE_PAGE_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows -> readPageRowsFrom(rows) }
        }

    private fun readPageRowsFrom(rows: java.sql.ResultSet): List<PageRow> {
        val result = ArrayList<PageRow>()
        while (rows.next()) {
            result.add(
                PageRow(
                    rows.getString(1),
                    rows.getInt(2),
                    rows.getString(3),
                    rows.getLong(4),
                    rows.getLong(5),
                    rows.getInt(6),
                ),
            )
        }
        return result
    }

    // The page queries fetch one row more than a page holds, so the reader can tell whether more follows.
    private const val PAGE_MAX_RECORDS = 100
    private const val PAGE_SIZE_CAP = 4194304L

    private const val SELECT_HEAD_AND_PURGED_SQL = "SELECT change_seq, purged_through FROM accounts WHERE id = ?"

    private const val SELECT_LIVE_PAGE_SQL =
        "SELECT client_id, key_version, encrypted, stored_at_ms, change_no, deleted FROM transcriptions " +
            "WHERE account_id = ? AND deleted = 0 ORDER BY change_no LIMIT 101"

    private const val SELECT_PAGE_SQL =
        "SELECT client_id, key_version, encrypted, stored_at_ms, change_no, deleted FROM transcriptions " +
            "WHERE account_id = ? AND change_no > ? ORDER BY change_no LIMIT 101"
}
