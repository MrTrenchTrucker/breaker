package dev.breaker.dictation.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SqliteSyncQueueDatabase.onUpgrade` delegates to [runOnUpgrade], so the migration step logic now
 * lives in `SyncQueueMigrator.kt`. That file cannot run on a plain JVM, so what it dispatches is
 * checked on its source text, the way the queue and cursor source tests check theirs: comments
 * blanked, references kept, each check a pure function from source text to violations. It runs on
 * short samples first, where a wrong one must be reported and a correct one must not, and then on
 * the real file. A missing file or function is a failure, never a pass.
 */
class SyncQueueMigratorSourceTest {

    private val migratorRefs = listOf(
        "MIGRATE_V1_TO_V2", "MIGRATE_V2_TO_V3", "MIGRATE_V3_TO_V4",
    )

    /** The real migrator file's text, with comments blanked. */
    private fun migratorSource(): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/sync/SyncQueueMigrator.kt")
        check(file.isFile) { "android_sync: expected SyncQueueMigrator.kt under src/main/kotlin/dev/breaker/dictation/sync" }
        return AdapterSource.code(file.readText())
    }

    /** A sample of source with comments blanked, for the control samples. */
    private fun code(source: String): String = AdapterSource.code(source)

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("android_sync: control: $what must be reported, got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("android_sync: control: $what must not be reported", emptyList<String>(), problems)

    private fun refProblems(source: String): List<String> {
        val text = code(source)
        return migratorRefs.filterNot { Regex("\\bSyncQueueSql\\.\\s*$it\\b").containsMatchIn(text) }
            .map { "no reference to SyncQueueSql.$it" }
    }

    /** The dispatch has a branch for each real upgrade path and runs the migrations in order. */
    private fun dispatchProblems(source: String): List<String> {
        val text = code(source)
        val problems = mutableListOf<String>()
        val steps = listOf(
            "oldVersion == 1 && newVersion == 2" to "MIGRATE_V1_TO_V2",
            "oldVersion == 2 && newVersion == 3" to "MIGRATE_V2_TO_V3",
            "oldVersion == 3 && newVersion == 4" to "MIGRATE_V3_TO_V4",
        )
        steps.forEach { (branch, statement) ->
            if (!text.contains(branch)) problems += "the dispatch has no branch for $branch"
            else if (!text.contains("SyncQueueSql.$statement")) problems += "the $branch branch does not run $statement"
        }
        val v13At = text.indexOf("oldVersion == 1 && newVersion == 3")
        if (v13At < 0) {
            problems += "the dispatch has no branch for old version 1 to 3"
        } else {
            val close = text.indexOf('}', v13At)
            if (close < 0) {
                problems += "the 1 to 3 branch is never closed"
            } else {
                val branch = text.substring(v13At, close)
                val v1To2 = branch.indexOf("SyncQueueSql.MIGRATE_V1_TO_V2")
                val v2To3 = branch.indexOf("SyncQueueSql.MIGRATE_V2_TO_V3")
                if (v1To2 < 0) problems += "the 1 to 3 branch never runs MIGRATE_V1_TO_V2"
                if (v2To3 < 0) problems += "the 1 to 3 branch never runs MIGRATE_V2_TO_V3"
                if (v1To2 >= 0 && v2To3 >= 0 && v1To2 > v2To3) problems += "the 1 to 3 branch runs the migrations in the wrong order"
            }
        }
        val v24At = text.indexOf("oldVersion == 2 && newVersion == 4")
        if (v24At < 0) {
            problems += "the dispatch has no branch for old version 2 to 4"
        } else {
            val close = text.indexOf('}', v24At)
            if (close >= 0) {
                val branch = text.substring(v24At, close)
                if (branch.indexOf("SyncQueueSql.MIGRATE_V2_TO_V3") < 0) problems += "the 2 to 4 branch never runs MIGRATE_V2_TO_V3"
                if (branch.indexOf("SyncQueueSql.MIGRATE_V3_TO_V4") < 0) problems += "the 2 to 4 branch never runs MIGRATE_V3_TO_V4"
            }
        }
        val v14At = text.indexOf("oldVersion == 1 && newVersion == 4")
        if (v14At < 0) {
            problems += "the dispatch has no branch for old version 1 to 4"
        } else {
            val close = text.indexOf('}', v14At)
            if (close >= 0) {
                val branch = text.substring(v14At, close)
                listOf("MIGRATE_V1_TO_V2", "MIGRATE_V2_TO_V3", "MIGRATE_V3_TO_V4").forEach { statement ->
                    if (branch.indexOf("SyncQueueSql.$statement") < 0) problems += "the 1 to 4 branch never runs $statement"
                }
            }
        }
        return problems
    }

    // ---- samples ----

    private fun sample(body: String): String = "internal fun runOnUpgrade(exec: (String) -> Unit, oldVersion: Int, newVersion: Int) {\n$body\n}\n"

    private val goodBody =
        "        when {\n" +
            "            oldVersion == 1 && newVersion == 2 -> exec(SyncQueueSql.MIGRATE_V1_TO_V2)\n" +
            "            oldVersion == 2 && newVersion == 3 -> exec(SyncQueueSql.MIGRATE_V2_TO_V3)\n" +
            "            oldVersion == 3 && newVersion == 4 -> exec(SyncQueueSql.MIGRATE_V3_TO_V4)\n" +
            "            oldVersion == 1 && newVersion == 3 -> {\n" +
            "                exec(SyncQueueSql.MIGRATE_V1_TO_V2)\n" +
            "                exec(SyncQueueSql.MIGRATE_V2_TO_V3)\n" +
            "            }\n" +
            "            oldVersion == 2 && newVersion == 4 -> {\n" +
            "                exec(SyncQueueSql.MIGRATE_V2_TO_V3)\n" +
            "                exec(SyncQueueSql.MIGRATE_V3_TO_V4)\n" +
            "            }\n" +
            "            oldVersion == 1 && newVersion == 4 -> {\n" +
            "                exec(SyncQueueSql.MIGRATE_V1_TO_V2)\n" +
            "                exec(SyncQueueSql.MIGRATE_V2_TO_V3)\n" +
            "                exec(SyncQueueSql.MIGRATE_V3_TO_V4)\n" +
            "            }\n" +
            "            else -> { }\n" +
            "        }"

    // ---- tests ----

    /** A failure here means the migrator dropped a migration statement constant, so a path runs a different statement than the tested one. */
    @Test
    fun `the migrator references every migration statement constant`() {
        assertFires("a missing constant", refProblems("val x = 1"), "no reference to SyncQueueSql.MIGRATE_V1_TO_V2")
        val refs = migratorRefs.joinToString("\n") { "x(SyncQueueSql.$it)" }
        assertQuiet("every constant named", refProblems(refs))
        assertEquals("android_sync: the migrator must reference every migration statement", emptyList<String>(), refProblems(migratorSource()))
    }

    /** A failure here means a phone that upgrades along a path gets no migration, or runs the steps in the wrong order. */
    @Test
    fun `the migrator dispatches every old version to the schema version`() {
        assertQuiet("a correct sample", dispatchProblems(sample(goodBody)))
        val noV23 = sample(goodBody.replace("oldVersion == 2 && newVersion == 3 -> exec(SyncQueueSql.MIGRATE_V2_TO_V3)\n", ""))
        assertFires("the missing 2 to 3 branch", dispatchProblems(noV23), "the dispatch has no branch for")
        val oneMigration = sample(goodBody.replace("exec(SyncQueueSql.MIGRATE_V2_TO_V3)\n                exec(SyncQueueSql.MIGRATE_V3_TO_V4)\n            }", "exec(SyncQueueSql.MIGRATE_V3_TO_V4)\n            }"))
        assertFires("the 2 to 4 branch without the first migration", dispatchProblems(oneMigration), "never runs MIGRATE_V2_TO_V3")
        val swapped = sample(goodBody.replace("exec(SyncQueueSql.MIGRATE_V1_TO_V2)\n                exec(SyncQueueSql.MIGRATE_V2_TO_V3)\n            }\n            oldVersion == 2", "exec(SyncQueueSql.MIGRATE_V2_TO_V3)\n                exec(SyncQueueSql.MIGRATE_V1_TO_V2)\n            }\n            oldVersion == 2"))
        assertFires("the 1 to 3 branch in the wrong order", dispatchProblems(swapped), "wrong order")
        assertEquals("android_sync: the migrator must handle the old versions with the right migrations in order", emptyList<String>(), dispatchProblems(migratorSource()))
    }
}
