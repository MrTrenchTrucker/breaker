package dev.breaker.server.syncapi.db

import dev.breaker.server.syncapi.accounts.AccountFixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SchemaV3MigrationTest : TempDatabaseTest() {

    @Test
    fun `a fresh database ends at the latest version with the transcriptions table and key version 0 for a new account`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 5, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: the transcriptions table is missing after the migrations",
                listOf("transcriptions"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'transcriptions'",
                ),
            )
            TestDatabases.insertAccountRow(connection, TestDatabases.validAccountRow())
            assertEquals(
                "sync-api: a new account must start at key version 0",
                listOf(0L),
                TestDatabases.longs(connection, "SELECT key_version FROM accounts"),
            )
        }
    }

    @Test
    fun `a version 2 database migrates to the latest version and keeps the account at key version 0`() {
        val first = openTemp(listOf(SchemaV1.MIGRATION, SchemaV2.MIGRATION))
        val verifierHash = ByteArray(32) { index -> (index + 40).toByte() }
        val createdAt = AccountFixtures.FIXED_MILLIS + 456L
        inTransaction(first) { connection ->
            assertEquals("sync-api: setup database user_version", 2, TestDatabases.userVersion(connection))
            TestDatabases.insertAccountRow(
                connection,
                TestDatabases.validAccountRow(
                    mapOf(
                        "username" to "Carol.Old",
                        "username_lower" to "carol.old",
                        "role" to "admin",
                        "verifier_hash" to verifierHash,
                        "created_at_ms" to createdAt,
                    ),
                ),
            )
        }

        val second = reopen(first)

        inTransaction(second) { connection ->
            assertEquals("sync-api: user_version after the upgrade", 5, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: the account was lost or duplicated by the upgrade",
                listOf(1L),
                TestDatabases.longs(connection, "SELECT count(*) FROM accounts"),
            )
            connection.prepareStatement(
                "SELECT username, username_lower, role, verifier_hash, created_at_ms, key_version FROM accounts",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue("sync-api: the upgraded database lost the account row", rows.next())
                    assertEquals("sync-api: username changed by the upgrade", "Carol.Old", rows.getString("username"))
                    assertEquals(
                        "sync-api: username_lower changed by the upgrade",
                        "carol.old",
                        rows.getString("username_lower"),
                    )
                    assertEquals("sync-api: role changed by the upgrade", "admin", rows.getString("role"))
                    assertArrayEquals(
                        "sync-api: verifier_hash changed by the upgrade",
                        verifierHash,
                        rows.getBytes("verifier_hash"),
                    )
                    assertEquals(
                        "sync-api: created_at_ms changed by the upgrade",
                        createdAt,
                        rows.getLong("created_at_ms"),
                    )
                    assertEquals("sync-api: key_version of an upgraded account", 0, rows.getInt("key_version"))
                }
            }
            assertEquals(
                "sync-api: the new transcriptions table must start empty",
                listOf(0L),
                TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions"),
            )
        }
    }
}
