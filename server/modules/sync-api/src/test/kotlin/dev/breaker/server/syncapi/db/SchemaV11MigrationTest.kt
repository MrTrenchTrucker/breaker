package dev.breaker.server.syncapi.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SchemaV11MigrationTest : TempDatabaseTest() {

    private val versionsOneToTen = listOf(
        SchemaV1.MIGRATION, SchemaV2.MIGRATION, SchemaV3.MIGRATION, SchemaV4.MIGRATION, SchemaV5.MIGRATION,
        SchemaV6.MIGRATION, SchemaV7.MIGRATION, SchemaV8.MIGRATION, SchemaV9.MIGRATION, SchemaV10.MIGRATION,
    )

    private fun addAccount(temp: TempDatabase, name: String): Long =
        inTransaction(temp) { connection ->
            TestDatabases.insertAccountRow(
                connection,
                TestDatabases.validAccountRow(mapOf("username" to name, "username_lower" to name)),
            )
            TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = '$name'").single()
        }

    private fun count(temp: TempDatabase, sql: String): Long =
        inTransaction(temp) { connection -> TestDatabases.longs(connection, sql).single() }

    private fun definitionOf(temp: TempDatabase, name: String): List<String> =
        inTransaction(temp) { connection ->
            TestDatabases.strings(connection, "SELECT sql FROM sqlite_master WHERE name = '$name'")
        }

    private fun insertCounter(route: String, address: String, countValue: Int): String =
        "INSERT INTO rate_limit_counters (route, address, count, window_start_ms) VALUES ('$route', '$address', $countValue, 0)"

    @Test
    fun `migration 11 applies to a fresh database with the rate_limit_counters columns`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 11, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: rate_limit_counters columns",
                listOf("route", "address", "count", "window_start_ms"),
                TestDatabases.strings(connection, "SELECT name FROM pragma_table_info('rate_limit_counters')"),
            )
        }
    }

    @Test
    fun `the primary key is the route and the address in that order`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals(
                "sync-api: primary key columns",
                listOf("route", "address"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM pragma_table_info('rate_limit_counters') WHERE pk > 0 ORDER BY pk",
                ),
            )
        }
    }

    @Test
    fun `an upgrade from version 10 keeps the account and starts with an empty counter table`() {
        val first = openTemp(versionsOneToTen)
        val aliceId = addAccount(first, "alice")
        inTransaction(first) { connection ->
            assertEquals("sync-api: setup database user_version", 10, TestDatabases.userVersion(connection))
        }
        val second = reopen(first)
        inTransaction(second) { connection ->
            assertEquals("sync-api: user_version after the upgrade", 11, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: the account row is kept with its id",
                listOf(aliceId),
                TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = 'alice'"),
            )
        }
        assertEquals("sync-api: the counter table starts empty", 0L, count(second, "SELECT count(*) FROM rate_limit_counters"))
    }

    @Test
    fun `a migrated database and a fresh database hold the same counter table definition`() {
        val fresh = openTemp()
        val migrated = reopen(openTemp(versionsOneToTen))
        assertEquals("sync-api: table definition", definitionOf(fresh, "rate_limit_counters"), definitionOf(migrated, "rate_limit_counters"))
        assertEquals("sync-api: the definition exists", 1, definitionOf(fresh, "rate_limit_counters").size)
    }

    @Test
    fun `one address on two routes is two rows and a repeated route and address is refused`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            connection.executeStatement(insertCounter("register", "10.0.0.1", 1))
            connection.executeStatement(insertCounter("salt", "10.0.0.1", 1))
        }
        assertEquals("sync-api: two rows for one address", 2L, count(temp, "SELECT count(*) FROM rate_limit_counters"))
        try {
            inTransaction(temp) { connection -> connection.executeStatement(insertCounter("register", "10.0.0.1", 2)) }
            throw AssertionError("sync-api: the database accepted a repeated route and address")
        } catch (failure: Exception) {
            assertTrue(
                "sync-api: refused, but not as a repeated key: ${failure.message}",
                failure.message.orEmpty().contains("UNIQUE", ignoreCase = true) ||
                    failure.message.orEmpty().contains("PRIMARY", ignoreCase = true),
            )
        }
    }

    @Test
    fun `a negative count is refused`() {
        val temp = openTemp()
        try {
            inTransaction(temp) { connection -> connection.executeStatement(insertCounter("register", "10.0.0.1", -1)) }
            throw AssertionError("sync-api: the database accepted a negative count")
        } catch (failure: Exception) {
            assertTrue(
                "sync-api: refused, but not by the CHECK: ${failure.message}",
                failure.message.orEmpty().contains("CHECK", ignoreCase = true),
            )
        }
    }
}
