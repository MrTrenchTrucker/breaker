package dev.breaker.server.whisper.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Types
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
    private val JOB_COLUMNS: Set<String> = setOf(
        "id",
        "owner_account_id",
        "status",
        "attempts",
        "error",
        "result",
        "created_at_ms",
        "started_at_ms",
        "finished_at_ms",
        "audio",
    )

    fun openTemp(steps: List<Migration> = Migrations.ALL): TempDatabase {
        val directory = Files.createTempDirectory("whisper-server-test")
        val file = directory.resolve("whisper-server.db")
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

    /**
     * A complete valid queued row for `jobs`, without `id`. [overrides] replace single
     * columns; a null value in [overrides] means SQL NULL and keeps the key in the row.
     */
    fun validJobRow(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val row = LinkedHashMap<String, Any?>()
        row["owner_account_id"] = 1L
        row["status"] = "queued"
        row["attempts"] = 0
        row["created_at_ms"] = 0L
        row["audio"] = ByteArray(8) { 1 }
        // A misspelt column would otherwise add a column and make the caller's
        // single-defect row fail for the wrong reason.
        for (key in overrides.keys) {
            require(JOB_COLUMNS.contains(key)) { "whisper-server test helper: unknown jobs column '$key'" }
        }
        row.putAll(overrides)
        return row
    }

    fun insertJobRow(connection: Connection, row: Map<String, Any?>) {
        val columns = row.keys.toList()
        val sql = "INSERT INTO jobs (" + columns.joinToString(", ") + ") VALUES (" +
            columns.joinToString(", ") { "?" } + ")"
        connection.prepareStatement(sql).use { statement ->
            for ((index, column) in columns.withIndex()) {
                val value: Any? = row[column]
                val position = index + 1
                when (value) {
                    null -> statement.setNull(position, Types.NULL)
                    is ByteArray -> statement.setBytes(position, value)
                    is String -> statement.setString(position, value)
                    is Int -> statement.setLong(position, value.toLong())
                    is Long -> statement.setLong(position, value)
                    else -> throw IllegalArgumentException(
                        "whisper-server test helper: unsupported value type for '$column'",
                    )
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

    /** The columns of [table] in the order SQLite stores them. */
    fun columnNames(connection: Connection, table: String): List<String> =
        strings(connection, "SELECT name FROM pragma_table_info('$table') ORDER BY cid")

    fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        require(needle.isNotEmpty()) { "whisper-server test helper: the needle is empty" }
        val last = haystack.size - needle.size
        var start = 0
        while (start <= last) {
            var offset = 0
            while (offset < needle.size && haystack[start + offset] == needle[offset]) {
                offset++
            }
            if (offset == needle.size) {
                return true
            }
            start++
        }
        return false
    }

    /**
     * The names of the files in the temporary directory (database, -wal, -shm and
     * anything else) whose bytes contain [needle], sorted.
     */
    fun filesHolding(temp: TempDatabase, needle: ByteArray): List<String> {
        val names = ArrayList<String>()
        val files = temp.directory.toFile().listFiles() ?: return names
        for (file in files) {
            if (file.isFile && containsBytes(file.readBytes(), needle)) {
                names.add(file.name)
            }
        }
        names.sort()
        return names
    }

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
