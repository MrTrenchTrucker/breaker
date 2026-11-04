package dev.breaker.server.syncapi.accounts

import java.sql.Connection

// The rule for the previous wrapped key of an account. After a password change the account holds
// prev_wrapped_dek, the old key wrapped under the new one, so rows still written under an older
// key version can be read. The column is cleared as soon as no stored transcription of the account
// that is not deleted has a key_version below the account's key_version. Delete records carry no
// body, so they never hold the column. The purge of delete records is not built.
//
// This is the one place the rule is written down. The password change and TranscriptionStore (a push
// that replaces a row, and a delete) call it inside the transaction of the write that can change
// the answer, right after that write.
internal object PrevKeyRule {
    // One statement, one bind (the account id). When the column is already NULL, or an old row
    // remains, it changes nothing.
    const val CLEAR_SQL =
        "UPDATE accounts SET prev_wrapped_dek = NULL WHERE id = ? AND prev_wrapped_dek IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM transcriptions t WHERE t.account_id = accounts.id " +
            "AND t.deleted = 0 AND t.key_version < accounts.key_version)"

    // Runs on the caller's connection, so it is part of the caller's transaction.
    fun clearIfNoOldRows(connection: Connection, accountId: Long) {
        connection.prepareStatement(CLEAR_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate()
        }
    }
}
