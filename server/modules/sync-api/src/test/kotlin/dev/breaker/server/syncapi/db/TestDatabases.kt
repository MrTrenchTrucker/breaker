package dev.breaker.server.syncapi.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import org.junit.After

/** A database file in its own temporary directory. [close] removes the directory. */
internal class TempDatabase(val db: SqliteDatabase, val directory: Path, val file: Path) : AutoCloseable {
    /** Closes the connection but leaves the file, so [TestDatabases.reopen] can use it. */
    fun closeKeepingFiles() {
        db.close()
    }

    override fun close() {
        db.close()
        directory.toFile().deleteRecursively()
    }
}

internal object TestDatabases {
    fun openTemp(steps: List<Migration> = Migrations.ALL): TempDatabase {
        val directory = Files.createTempDirectory("sync-api-test")
        val file = directory.resolve("sync-api.db")
        try {
            return TempDatabase(SqliteDatabase.open(file.toString(), steps), directory, file)
        } catch (failure: Throwable) {
            directory.toFile().deleteRecursively()
            throw failure
        }
    }

    /**
     * Opens the file of [old] again on a new connection. Close the old connection first
     * with [TempDatabase.closeKeepingFiles]: [TempDatabase.close] would delete the file.
     */
    fun reopen(old: TempDatabase, steps: List<Migration> = Migrations.ALL): TempDatabase =
        TempDatabase(SqliteDatabase.open(old.file.toString(), steps), old.directory, old.file)

    /** A complete valid row for `accounts`, without `id`. [overrides] replace single columns. */
    fun validAccountRow(overrides: Map<String, Any> = emptyMap()): Map<String, Any> {
        val row = LinkedHashMap<String, Any>()
        row["username"] = "alice"
        row["username_lower"] = "alice"
        row["role"] = "user"
        row["salt"] = ByteArray(16) { 1 }
        row["kdf_memory_kib"] = 65536
        row["kdf_iterations"] = 3
        row["kdf_parallelism"] = 1
        row["kdf_version"] = 1
        row["verifier_algo"] = "pbkdf2-hmac-sha256"
        row["verifier_iters"] = 1000
        row["verifier_salt"] = ByteArray(16) { 2 }
        row["verifier_hash"] = ByteArray(32) { 3 }
        row["created_at_ms"] = 0L
        // A misspelt column would otherwise add a column and make the caller's
        // single-defect row fail for the wrong reason.
        for (key in overrides.keys) {
            require(row.containsKey(key)) { "sync-api test helper: unknown accounts column '$key'" }
        }
        row.putAll(overrides)
        return row
    }

    fun insertAccountRow(connection: Connection, row: Map<String, Any>) {
        val columns = row.keys.toList()
        val sql = "INSERT INTO accounts (" + columns.joinToString(", ") + ") VALUES (" +
            columns.joinToString(", ") { "?" } + ")"
        connection.prepareStatement(sql).use { statement ->
            for ((index, column) in columns.withIndex()) {
                val value: Any = row.getValue(column)
                val position = index + 1
                when (value) {
                    is ByteArray -> statement.setBytes(position, value)
                    is String -> statement.setString(position, value)
                    is Int -> statement.setLong(position, value.toLong())
                    is Long -> statement.setLong(position, value)
                    else -> throw IllegalArgumentException("sync-api test helper: unsupported value type for '$column'")
                }
            }
            statement.executeUpdate()
        }
    }

    fun longs(connection: Connection, sql: String): List<Long> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                val values = ArrayList<Long>()
                while (rows.next()) {
                    values.add(rows.getLong(1))
                }
                values
            }
        }

    fun strings(connection: Connection, sql: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                val values = ArrayList<String>()
                while (rows.next()) {
                    values.add(rows.getString(1))
                }
                values
            }
        }

    fun userVersion(connection: Connection): Int = longs(connection, "PRAGMA user_version").single().toInt()

    fun tableNames(connection: Connection): List<String> =
        strings(connection, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")

    /** Runs [block] and returns the throwable it threw, which must be a [T]. */
    inline fun <reified T : Throwable> expectFailure(what: String, block: () -> Unit): T {
        try {
            block()
        } catch (thrown: Throwable) {
            if (thrown is T) {
                return thrown
            }
            throw AssertionError(
                "$what: expected ${T::class.java.name} but got ${thrown::class.java.name}: ${thrown.message}",
                thrown,
            )
        }
        throw AssertionError("$what: expected ${T::class.java.name} but nothing was thrown")
    }
}

/** Base for tests that open temporary databases: everything opened through it is removed afterwards. */
internal abstract class TempDatabaseTest {
    private val opened = ArrayList<TempDatabase>()

    protected fun openTemp(steps: List<Migration> = Migrations.ALL): TempDatabase {
        val temp = TestDatabases.openTemp(steps)
        opened.add(temp)
        return temp
    }

    /** Closes the connection of [old] (keeping the file) and opens the same file again. */
    protected fun reopen(old: TempDatabase, steps: List<Migration> = Migrations.ALL): TempDatabase {
        old.closeKeepingFiles()
        val temp = TestDatabases.reopen(old, steps)
        opened.add(temp)
        return temp
    }

    protected fun <T> inTransaction(temp: TempDatabase, block: (Connection) -> T): T =
        runBlocking { temp.db.transaction(block) }

    @After
    fun removeTempDatabases() {
        for (temp in opened) {
            temp.close()
        }
    }
}
