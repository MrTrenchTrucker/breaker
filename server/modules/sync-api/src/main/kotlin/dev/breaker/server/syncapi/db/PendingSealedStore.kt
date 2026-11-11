package dev.breaker.server.syncapi.db

import java.sql.Connection

// The pending-sealed store: one row per still-sealed agent-token job result awaiting
// the owner's unseal and re-upload. Reads only; no write path is built yet (the
// whisper-server -> sync-api transport is undecided, Phase 19/20). A row is opaque to
// this module: sealed_box is the base64 of a crypto_box_seal output, and the store does
// not judge whether whisper-server or a database writer produced it.
//
// Honest limit (T26, ADR-018): GET /v1/sync/pending-sealed returns whatever this store
// holds. It cannot tell a sealed box whisper-server actually produced from one a database
// writer inserted directly — both are equally well-formed crypto_box_seal output, and the
// owner's client converts either kind the same way.
internal class PendingSealedStore(private val db: SqliteDatabase) {
    /** Every still-present row of [accountId], oldest first by created_at then id. */
    suspend fun list(accountId: Long): List<PendingSealedRow> =
        db.transaction<List<PendingSealedRow>> { connection ->
            connection.prepareStatement(
                "SELECT id, sealed_box, created_at FROM pending_sealed " +
                    "WHERE account_id = ? ORDER BY created_at ASC, id ASC",
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    val found = ArrayList<PendingSealedRow>()
                    while (rows.next()) {
                        found.add(
                            PendingSealedRow(
                                rows.getString(1),
                                rows.getString(2),
                                rows.getString(3),
                            ),
                        )
                    }
                    found
                }
            }
        }
}

internal data class PendingSealedRow(
    val id: String,
    val sealedBox: String,
    val createdAt: String,
)

// Write path: deletes the pending_sealed rows of [accountId] on the caller's
// connection, inside the caller's transaction. No own transaction (nesting
// deadlocks the single write lane). Mirrors TranscriptionPurge.deleteAllOn.
internal object PendingSealedStoreClear {
    fun deleteAllOn(connection: Connection, accountId: Long): Int =
        connection.prepareStatement("DELETE FROM pending_sealed WHERE account_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate()
        }
}
