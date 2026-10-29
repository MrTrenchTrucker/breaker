package dev.breaker.server.syncapi.db

import org.junit.Assert.assertEquals
import org.junit.Test

internal class SchemaV3Test : TempDatabaseTest() {

    @Test
    fun `a fresh file database is at version 3 with the transcription table`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 3, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: transcriptions table missing after the migrations",
                listOf("transcriptions"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'transcriptions'",
                ),
            )
        }
    }

    @Test
    fun `the transcription index covers account stored time and client id in that order`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals(
                "sync-api: index transcriptions_account_stored must cover account_id, stored_at_ms, client_id in that order",
                listOf("account_id", "stored_at_ms", "client_id"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM pragma_index_info('transcriptions_account_stored') ORDER BY seqno",
                ),
            )
        }
    }

    @Test
    fun `a version 2 database upgrades to version 3 and keeps its account and token`() {
        val first = openTemp(listOf(SchemaV1.MIGRATION, SchemaV2.MIGRATION))
        inTransaction(first) { connection ->
            TestDatabases.insertAccountRow(
                connection,
                TestDatabases.validAccountRow(mapOf("username" to "dana", "username_lower" to "dana")),
            )
            val owner: Long = TestDatabases.longs(connection, "SELECT id FROM accounts").single()
            connection.prepareStatement(
                "INSERT INTO tokens (account_id, kind, token_hash, created_at_ms) VALUES (?, 'session', ?, 1)",
            ).use { statement ->
                statement.setLong(1, owner)
                statement.setBytes(2, ByteArray(32) { 9 })
                statement.executeUpdate()
            }
        }

        val second = reopen(first)

        inTransaction(second) { connection ->
            assertEquals("sync-api: user_version after the upgrade", 3, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: the account must survive the upgrade",
                listOf(1L),
                TestDatabases.longs(connection, "SELECT count(*) FROM accounts"),
            )
            assertEquals(
                "sync-api: the token must survive the upgrade",
                listOf(1L),
                TestDatabases.longs(connection, "SELECT count(*) FROM tokens"),
            )
            assertEquals(
                "sync-api: the new table must start empty",
                listOf(0L),
                TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions"),
            )
        }
    }
}
