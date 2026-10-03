package dev.breaker.dictation.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order of [HistorySql.CREATE_ALL], as far as anything promises one.
 *
 * Nothing in [HistorySql] says which table comes first. The one order that matters is that an index is
 * created after the table it is on: a fresh database refuses `CREATE INDEX` on a table that does not exist
 * yet. That is all this pins, and it reads the statements as text, so it does not depend on a database opening.
 */
class SchemaOrderTest {

    private val tablePattern = Regex("""^CREATE TABLE IF NOT EXISTS (\w+) \(""")
    private val indexPattern = Regex("""^CREATE INDEX IF NOT EXISTS (\w+) ON (\w+) \(""")

    @Test
    fun `every index is created after the table it is on`() {
        val statements = HistorySql.CREATE_ALL
        val tablePositions = statements.withIndex()
            .mapNotNull { (position, sql) -> tablePattern.find(sql)?.let { it.groupValues[1] to position } }
            .toMap()
        val indexes = statements.withIndex()
            .mapNotNull { (position, sql) ->
                indexPattern.find(sql)?.let { Triple(it.groupValues[1], it.groupValues[2], position) }
            }

        assertEquals("tables found in the list", setOf("transcriptions", "tombstones"), tablePositions.keys)
        assertEquals("indexes found in the list", 2, indexes.size)
        assertEquals("every statement is a table or an index", statements.size, tablePositions.size + indexes.size)
        for ((name, table, position) in indexes) {
            val tablePosition = tablePositions[table]
            assertTrue("$name is on $table, which the list does not create", tablePosition != null)
            assertTrue(
                "$name is created at position $position, before its table $table at $tablePosition",
                position > tablePosition!!,
            )
        }
    }
}
