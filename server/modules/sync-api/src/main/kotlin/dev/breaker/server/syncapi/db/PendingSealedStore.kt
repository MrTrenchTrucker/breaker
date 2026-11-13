package dev.breaker.server.syncapi.db

import java.sql.Connection
import java.sql.PreparedStatement

// The pending-sealed store: one row per still-sealed agent-token job result awaiting
// the owner's unseal and re-upload. The write path is insert, reached by POST
// /internal/v1/pending-sealed; whisper-server does not call it yet. A row is opaque to
// this module: sealed_box is the base64 of a crypto_box_seal output, and the store does
// not judge whether whisper-server or a database writer produced it.
// Honest limit (T26, ADR-018): GET /v1/sync/pending-sealed returns whatever this store
// holds. It cannot tell a sealed box whisper-server actually produced from one a database
// writer inserted directly — both are equally well-formed crypto_box_seal output, and the
// owner's client converts either kind the same way.

// Rows one account may hold. POST /v1/sync deletes the account's pending row with that client id in the same
// transaction as the push that lands (PendingSealedStoreDelete), so a converted row is freed at once. A row the
// owner never converts stays until a reset, a password change or the first key store clears it, and this cap is
// what bounds the table per account meanwhile.
internal const val PENDING_PER_ACCOUNT_MAX: Int = 100

// The most DECODED sealed bytes one row may hold, so that what the owner re-uploads fits POST /v1/sync.
// The owner unseals S sealed bytes to S - 48 plaintext bytes (32 ephemeral key + 16 tag) and encrypts them
// again into a record of 12 nonce + (S - 48) + 16 tag = S - 20 bytes (ADR-006; the wire framing of that
// record is decided, not built). Its base64 text is 4 * ceil((S - 20) / 3) characters, and the JSON body
// around it is at most 107 characters ({"client_id":"<36>","key_version":<10 digits>,"encrypted":"...",
// "resave":false}), taken as 109 to leave 2 spare. With S = 786,368: 4 * ceil(786,348 / 3) = 4 * 262,116
// = 1,048,464, and 1,048,464 + 109 = 1,048,573, which is at most SYNC_BODY_CAP_BYTES (1,048,576).
// One byte more (S = 786,369) gives 4 * 262,117 + 109 = 1,048,577, over the cap.
internal const val MAX_SEALED_BYTES: Int = 786_368

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

    // Insert one row in a single transaction. The account is read first (a missing one is NoSuchAccount, not
    // a caught foreign-key error), then the id (present under any account is Duplicate: nothing changes and
    // nothing says whose row it is), then the account's row count (at PENDING_PER_ACCOUNT_MAX is PendingFull),
    // and only then the row is written.
    suspend fun insert(
        accountId: Long,
        id: String,
        sealedBox: String,
        createdAt: String,
    ): PendingInsertResult =
        db.transaction<PendingInsertResult> { connection ->
            if (!hasRow(connection, "SELECT 1 FROM accounts WHERE id = ?") { it.setLong(1, accountId) }) {
                return@transaction PendingInsertResult.NoSuchAccount
            }
            if (hasRow(connection, "SELECT 1 FROM pending_sealed WHERE id = ?") { it.setString(1, id) }) {
                return@transaction PendingInsertResult.Duplicate
            }
            if (countOf(connection, accountId) >= PENDING_PER_ACCOUNT_MAX) {
                return@transaction PendingInsertResult.PendingFull
            }
            connection.prepareStatement(
                "INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, id)
                statement.setLong(2, accountId)
                statement.setString(3, sealedBox)
                statement.setString(4, createdAt)
                statement.executeUpdate()
            }
            PendingInsertResult.Stored
        }

    private fun hasRow(connection: Connection, sql: String, bind: (PreparedStatement) -> Unit): Boolean =
        connection.prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { rows -> rows.next() }
        }

    private fun countOf(connection: Connection, accountId: Long): Int =
        connection.prepareStatement("SELECT COUNT(*) FROM pending_sealed WHERE account_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sync-api: pending_sealed count could not be read" }
                rows.getInt(1)
            }
        }
}

// The result of a pending-sealed insert.
internal sealed class PendingInsertResult {
    object Stored : PendingInsertResult()        // 201 {"stored":true}
    object Duplicate : PendingInsertResult()     // 200 {"stored":false}
    object PendingFull : PendingInsertResult()   // 409 {"error":"pending_full"}
    object NoSuchAccount : PendingInsertResult() // 404 {"error":"no_such_account"}
}

internal data class PendingSealedRow(
    val id: String,
    val sealedBox: String,
    val createdAt: String,
)

// Deletes one pending_sealed row of [accountId] with [id] on the caller's connection. Runs in
// the caller's transaction: no db.transaction of its own, no suspend. Mirrors
// PendingSealedStoreClear.deleteAllOn. Returns how many rows were deleted (0 or 1).
internal object PendingSealedStoreDelete {
    fun deleteOneOn(connection: Connection, accountId: Long, id: String): Int =
        connection.prepareStatement("DELETE FROM pending_sealed WHERE account_id = ? AND id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, id)
            statement.executeUpdate()
        }
}

// Write path: deletes all pending_sealed rows of [accountId] on the caller's connection.
// Mirrors TranscriptionPurge.deleteAllOn.
internal object PendingSealedStoreClear {
    fun deleteAllOn(connection: Connection, accountId: Long): Int =
        connection.prepareStatement("DELETE FROM pending_sealed WHERE account_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate()
        }
}
