package dev.breaker.server.syncapi.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SchemaV2MigrationTest : TempDatabaseTest() {

    @Test
    fun `a version 1 database migrates to version 2 with its account rows intact`() {
        val first = openTemp(listOf(SchemaV1.MIGRATION))
        val verifierHash = ByteArray(32) { index -> (index + 40).toByte() }
        val createdAt = 1_700_000_123_456L
        inTransaction(first) { connection ->
            assertEquals("sync-api: setup database user_version", 1, TestDatabases.userVersion(connection))
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
            assertEquals("sync-api: user_version after the upgrade", 2, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: accounts and tokens must both exist after the upgrade",
                listOf("accounts", "tokens"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('accounts', 'tokens') ORDER BY name",
                ),
            )
            assertEquals(
                "sync-api: the stored account was lost or duplicated by the upgrade",
                listOf(1L),
                TestDatabases.longs(connection, "SELECT count(*) FROM accounts"),
            )
            connection.prepareStatement(
                "SELECT id, username, username_lower, role, verifier_hash, created_at_ms FROM accounts",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue("sync-api: the upgraded database lost the account row", rows.next())
                    assertEquals("sync-api: username changed by the upgrade", "Carol.Old", rows.getString("username"))
                    assertEquals("sync-api: username_lower changed by the upgrade", "carol.old", rows.getString("username_lower"))
                    assertEquals("sync-api: role changed by the upgrade", "admin", rows.getString("role"))
                    assertArrayEquals(
                        "sync-api: verifier_hash changed by the upgrade",
                        verifierHash,
                        rows.getBytes("verifier_hash"),
                    )
                    assertEquals("sync-api: created_at_ms changed by the upgrade", createdAt, rows.getLong("created_at_ms"))
                }
            }
            assertEquals(
                "sync-api: the new tokens table must start empty",
                listOf(0L),
                TestDatabases.longs(connection, "SELECT count(*) FROM tokens"),
            )
        }

        // The account kept its id, so a token row can point at it.
        inTransaction(second) { connection ->
            val owner: Long = TestDatabases.longs(connection, "SELECT id FROM accounts").single()
            connection.prepareStatement(
                "INSERT INTO tokens (account_id, kind, token_hash, created_at_ms) VALUES (?, 'session', ?, 1)",
            ).use { statement ->
                statement.setLong(1, owner)
                statement.setBytes(2, ByteArray(32) { 9 })
                statement.executeUpdate()
            }
            assertEquals(
                "sync-api: a token row for the migrated account was not stored",
                listOf(1L),
                TestDatabases.longs(connection, "SELECT count(*) FROM tokens"),
            )
        }
    }
}
