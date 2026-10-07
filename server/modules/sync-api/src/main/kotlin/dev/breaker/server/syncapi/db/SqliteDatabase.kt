package dev.breaker.server.syncapi.db

import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * One SQLite connection, reached through one single-lane dispatcher.
 *
 * Calling [transaction] after [close] throws [IllegalStateException].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SqliteDatabase private constructor(
    private val connection: Connection,
) : AutoCloseable {

    // The connection is shared mutable state and a SQLite database has a single
    // writer anyway. Running every block on a one-lane dispatcher makes each
    // transaction run start to finish before the next begins; the blocks are not
    // suspending, so nothing can interleave inside one.
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    suspend fun <T> transaction(block: (Connection) -> T): T = withContext(dispatcher) {
        check(!connection.isClosed) { "sync-api: the database is closed" }
        // autoCommit stays on and BEGIN is sent by hand. With autoCommit off the
        // driver sends a plain deferred BEGIN, whose first write can fail with
        // SQLITE_BUSY halfway through. BEGIN IMMEDIATE takes the write lock up front.
        connection.executeStatement("BEGIN IMMEDIATE")
        try {
            val result = block(connection)
            connection.executeStatement("COMMIT")
            result
        } catch (failure: Throwable) {
            connection.rollbackAfter(failure)
            throw failure
        }
    }

    // JDBC makes a second close a no-op, so cleanup code need not track who closed first.
    override fun close() {
        connection.close()
    }

    companion object {
        private const val IN_MEMORY = ":memory:"

        /** [path] is a file path or ":memory:". Migrations have run when this returns. */
        fun open(path: String, steps: List<Migration> = Migrations.ALL): SqliteDatabase {
            require(path.isNotBlank()) { "sync-api: the database path is blank" }
            val inMemory = path == IN_MEMORY
            val connection = DriverManager.getConnection(if (inMemory) "jdbc:sqlite::memory:" else "jdbc:sqlite:$path")
            try {
                configure(connection, inMemory)
                Migrations.migrate(connection, steps)
                return SqliteDatabase(connection)
            } catch (failure: Throwable) {
                // Without this a failed start leaks an open handle on the file.
                try {
                    connection.close()
                } catch (closeFailure: Exception) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

        private fun configure(connection: Connection, inMemory: Boolean) {
            // Foreign keys are off by default and the setting is per connection.
            connection.executeStatement("PRAGMA foreign_keys = ON")
            connection.executeStatement("PRAGMA busy_timeout = 5000")
            if (!inMemory) {
                enableWal(connection)
            }
        }

        // SQLite answers a refused journal mode with the old mode instead of an
        // error (some network file systems cannot do WAL), so the answer is checked.
        private fun enableWal(connection: Connection) {
            val mode = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA journal_mode = WAL").use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
            check(mode.equals("wal", ignoreCase = true)) {
                "sync-api: could not switch the database to WAL journal mode (got $mode)"
            }
        }
    }
}
