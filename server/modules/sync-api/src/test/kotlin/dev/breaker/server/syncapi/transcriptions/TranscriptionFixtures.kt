package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import kotlinx.coroutines.runBlocking

/**
 * Test-only helpers for the transcription store tests. The store never changes accounts.key_version;
 * these helpers do, by direct SQL, so a test can set the version it needs.
 */
internal object TranscriptionFixtures {
    const val CLIENT_ONE = "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f"
    const val CLIENT_TWO = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    const val CLIENT_THREE = "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e"

    fun setKeyVersion(temp: TempDatabase, accountId: Long, keyVersion: Int) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET key_version = ? WHERE id = ?").use { statement ->
                    statement.setInt(1, keyVersion)
                    statement.setLong(2, accountId)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun rowCount(temp: TempDatabase, accountId: Long): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT count(*) FROM transcriptions WHERE account_id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test helper: count returned no row" }
                    rows.getLong(1)
                }
            }
        }
    }
}
