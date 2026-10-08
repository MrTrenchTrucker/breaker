package dev.breaker.server.whisper.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * One SQLite connection, reached through one single-lane dispatcher.
 *
 * Calling [transaction] or [checkpointTruncate] after [close] throws
 * [IllegalStateException].
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
        check(!connection.isClosed) { "whisper-server: the database is closed" }
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

    /**
     * Empties the write-ahead log into the main file and truncates it. Returns true
     * when nothing was left behind, false when the checkpoint could not finish.
     */
    suspend fun checkpointTruncate(): Boolean = withContext(dispatcher) {
        check(!connection.isClosed) { "whisper-server: the database is closed" }
        // WAL frames keep copies of pages that were already overwritten, so audio and
        // results that were just erased stay readable in the -wal file until it is
        // truncated. The pragma cannot run inside a transaction, so this runs on the
        // same lane after the caller's transaction has returned. It is best effort:
        // a reader that holds the log open makes it report busy, and that is not an
        // error for the caller, whose own change is already committed.
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rows ->
                    // The first column is 1 when the checkpoint was blocked.
                    rows.next() && rows.getInt(1) == 0
                }
            }
        } catch (failure: SQLException) {
            false
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
            require(path.isNotBlank()) { "whisper-server: the database path is blank" }
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
            enableSecureDelete(connection)
            if (!inMemory) {
                enableWal(connection)
            }
        }

        // Freed pages are zeroed. Without this, deleted audio and results stay
        // readable in the database file. SQLite answers a refused setting with the
        // old value instead of an error, so the value is read back.
        private fun enableSecureDelete(connection: Connection) {
            connection.executeStatement("PRAGMA secure_delete = ON")
            val value = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA secure_delete").use { rows ->
                    if (rows.next()) rows.getInt(1) else null
                }
            }
            check(value == 1) {
                "whisper-server: could not turn on secure_delete (got $value)"
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
                "whisper-server: could not switch the database to WAL journal mode (got $mode)"
            }
        }
    }
}
