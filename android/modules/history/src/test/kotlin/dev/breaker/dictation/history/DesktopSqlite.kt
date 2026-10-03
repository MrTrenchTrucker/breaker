package dev.breaker.dictation.history

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

/**
 * A real SQLite engine for the JVM tests: the desktop SQLite that ships inside
 * the test-only `sqlite-jdbc` jar, opened in memory, with this module's own
 * schema ([HistorySql.CREATE_ALL]) created on it.
 *
 * **The limit, stated here and on the module card.** This is the desktop SQLite
 * in the jar, not the phone's. The SQL therefore has to stay in the subset the
 * two share, and what these tests prove is that the statements mean what they
 * say on a real engine. That the phone's own SQLite agrees comes with the
 * on-device launch test. `SqliteHistoryDatabase`, the Android adapter, needs
 * Android APIs and runs in no JVM test.
 *
 * **What is modelled, and not checked against Android.** Three things here
 * stand in for the platform, and are written by hand:
 *
 * - [delete] assembles `DELETE FROM table WHERE where` itself, as
 *   `SQLiteDatabase.delete(table, where, args)` does. The table, the `WHERE`
 *   and the argument list it is given come from the adapter's source text (see
 *   [AdapterStatements]); the assembly is this class's own.
 * - [JdbcHistoryDatabase], which runs on this class, wraps a transaction by
 *   switching the connection's autocommit off and back on, where the adapter
 *   uses `beginTransaction` and `endTransaction`.
 * - [JdbcHistoryDatabase] maps a result row from the driver's own values, not
 *   through an Android `Cursor`.
 *
 * **How arguments are bound.** The adapter binds arguments in two ways, and this
 * class does the same two:
 *
 * - [query] and [delete] bind every argument as **text**, the way
 *   `SQLiteDatabase.rawQuery` and `SQLiteDatabase.delete` do with their
 *   `String` argument arrays. That includes the retention cutoff, which the
 *   adapter passes as `cutoff.toString()`. Whether a text-bound cutoff compares
 *   as a number is exactly the question the tests ask.
 * - [exec] binds by type, the way `execSQL(sql, Object[])` does.
 *
 * The URL is a fixed in-memory one. Nothing here builds a JDBC URL from input.
 */
internal class DesktopSqlite : AutoCloseable {

    val connection: Connection = DriverManager.getConnection(URL)

    init {
        createSchema()
    }

    /** Creates the module's tables and indexes. Safe to call again: every statement is `IF NOT EXISTS`. */
    fun createSchema() {
        connection.createStatement().use { statement ->
            HistorySql.CREATE_ALL.forEach { statement.execute(it) }
        }
    }

    /** Runs [sql] with every argument bound as text, like `rawQuery`. Every value comes back as text or a number. */
    fun query(sql: String, vararg textArgs: String): List<List<Any?>> =
        connection.prepareStatement(sql).use { statement ->
            bindText(statement, textArgs)
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

    /** The first column of every row [query] returns. */
    fun firstColumn(sql: String, vararg textArgs: String): List<Any?> = query(sql, *textArgs).map { it[0] }

    /**
     * `DELETE FROM [table] WHERE [where]` with every argument bound as text.
     *
     * This is how `SQLiteDatabase.delete(table, where, args)` builds its statement, written out by hand
     * and not checked against Android. Callers pass the table and `WHERE` read from the adapter. A null or
     * blank `WHERE`, which the platform turns into a delete of every row, never reaches here: reading the
     * adapter refuses one. Returns the number of rows removed.
     */
    fun delete(table: String, where: String, vararg textArgs: String): Int =
        update("DELETE FROM $table WHERE $where", *textArgs)

    /** Runs [sql] with arguments bound by type, like `execSQL(sql, Object[])`. Returns rows changed. */
    fun exec(sql: String, vararg args: Any?): Int =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value ->
                val position = index + 1
                when (value) {
                    null -> statement.setNull(position, Types.NULL)
                    is String -> statement.setString(position, value)
                    is Long -> statement.setLong(position, value)
                    is Int -> statement.setInt(position, value)
                    else -> throw IllegalArgumentException("no binding for ${value::class}")
                }
            }
            statement.executeUpdate()
        }

    override fun close() = connection.close()

    /** Runs the statement [sql] with every argument bound as text. Returns rows changed. */
    private fun update(sql: String, vararg textArgs: String): Int =
        connection.prepareStatement(sql).use { statement ->
            bindText(statement, textArgs)
            statement.executeUpdate()
        }

    private fun bindText(statement: PreparedStatement, textArgs: Array<out String>) {
        textArgs.forEachIndexed { index, value -> statement.setString(index + 1, value) }
    }

    companion object {
        private const val URL = "jdbc:sqlite::memory:"
    }
}
