package dev.breaker.server.syncapi.db

import java.sql.Connection
import java.sql.SQLException

internal object Migrations {
    val ALL: List<Migration> = listOf(SchemaV1.MIGRATION)

    /**
     * Brings the database behind [connection] up to the last version in [steps]
     * and returns the `user_version` it ends at.
     */
    fun migrate(connection: Connection, steps: List<Migration>): Int {
        // Checked before the database is touched: a broken list is a programming
        // error and must not leave a half-applied history behind.
        requireConsecutiveFromOne(steps)
        val known = steps.lastOrNull()?.version ?: 0
        val current = readUserVersion(connection)
        if (current > known) {
            throw MigrationException(
                "sync-api: database schema version $current is newer than this code knows ($known)",
            )
        }
        for (step in steps) {
            if (step.version > current) {
                applyStep(connection, step)
            }
        }
        return readUserVersion(connection)
    }

    private fun requireConsecutiveFromOne(steps: List<Migration>) {
        for ((index, step) in steps.withIndex()) {
            val expected = index + 1
            if (step.version != expected) {
                throw MigrationException(
                    "sync-api: migration versions must be consecutive from 1, " +
                        "but position $expected has version ${step.version}",
                )
            }
        }
    }

    // Each step has its own transaction so a failure leaves the database at the last
    // good version. SQLite rolls back DDL and the header field behind user_version
    // with the rest of the transaction, so the version is written last, inside it:
    // it can never get ahead of the schema it describes.
    private fun applyStep(connection: Connection, step: Migration) {
        try {
            connection.executeStatement("BEGIN IMMEDIATE")
            for (sql in step.statements) {
                connection.executeStatement(sql)
            }
            connection.executeStatement("PRAGMA user_version = ${step.version}")
            connection.executeStatement("COMMIT")
        } catch (failure: Exception) {
            connection.rollbackAfter(failure)
            throw MigrationException(
                "sync-api: migration to schema version ${step.version} failed: ${failure.message}",
                failure,
            )
        }
    }

    private fun readUserVersion(connection: Connection): Int {
        try {
            return connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    if (!rows.next()) {
                        throw MigrationException("sync-api: PRAGMA user_version returned no row")
                    }
                    rows.getInt(1)
                }
            }
        } catch (failure: SQLException) {
            throw MigrationException("sync-api: could not read the schema version: ${failure.message}", failure)
        }
    }
}
