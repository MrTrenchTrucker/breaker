package dev.breaker.dictation.sync

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * A real SQLite engine for the JVM tests: the desktop SQLite inside the test-only `sqlite-jdbc` jar,
 * with this module's own schema ([SyncQueueSql.CREATE_ALL]) created on it.
 *
 * **The limit.** This is the desktop SQLite in the jar, not the phone's. The SQL therefore has to stay
 * in the subset the two share, and what the tests prove is that the statements mean what they say on a
 * real engine. `SqliteSyncQueueDatabase`, the Android adapter, needs Android APIs and runs in no JVM
 * test.
 *
 * **Where the data lives.** With no file the database is in memory and disappears when the connection
 * closes. With a [file] the database is that file: closing the connection and opening a new
 * [DesktopSqlite] on the same file shows what survived. The test creates the file and deletes it. The
 * JDBC URL is built only from a fixed prefix and the path of that file.
 *
 * **How arguments are bound.** [query] binds every argument as text, as `rawQuery` does with its
 * `String` array. [exec] binds by type, as `execSQL(sql, Object[])` does.
 */
internal class DesktopSqlite(file: File? = null) : AutoCloseable {

    val connection: Connection =
        DriverManager.getConnection(if (file == null) MEMORY_URL else FILE_URL_PREFIX + file.absolutePath)

    init {
        createSchema()
    }

    /** Creates the module's tables. Safe to call again: every statement is `IF NOT EXISTS`. */
    fun createSchema() {
        connection.createStatement().use { statement ->
            SyncQueueSql.CREATE_ALL.forEach { statement.execute(it) }
        }
    }

    /** Runs [sql] with every argument bound as text. Every value comes back as text, a number as a Long, or null. */
    fun query(sql: String, vararg textArgs: String): List<List<Any?>> =
        connection.prepareStatement(sql).use { statement ->
            textArgs.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rs ->
                val columns = rs.metaData.columnCount
                buildList {
                    while (rs.next()) {
                        add(
                            (1..columns).map { index ->
                                when (val value = rs.getObject(index)) {
                                    is Number -> value.toLong()
                                    else -> value
                                }
                            },
                        )
                    }
                }
            }
        }

    /** Runs [sql] with arguments bound by type. Returns the number of rows changed. */
    fun exec(sql: String, vararg args: Any?): Int =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value ->
                val position = index + 1
                when (value) {
                    null -> statement.setNull(position, Types.NULL)
                    is String -> statement.setString(position, value)
                    is Long -> statement.setLong(position, value)
                    is Int -> statement.setInt(position, value)
                    else -> throw IllegalArgumentException("android_sync: no binding for ${value::class}")
                }
            }
            statement.executeUpdate()
        }

    override fun close() = connection.close()

    private companion object {
        const val MEMORY_URL = "jdbc:sqlite::memory:"
        const val FILE_URL_PREFIX = "jdbc:sqlite:"
    }
}
