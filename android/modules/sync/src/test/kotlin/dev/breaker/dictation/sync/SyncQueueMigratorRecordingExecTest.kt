package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The migrator's upgrade dispatch driven through a RECORDING exec: the production [runOnUpgrade]
 * (SyncQueueMigrator.kt, src/main) is called directly and every statement it hands to exec is
 * recorded, in order. No database, no DesktopSqlite (whose init runs CREATE_ALL and would create
 * pending_save whether or not the migrator ran), no JdbcSyncQueueDatabase twin. This kills the
 * "4->5 branch does nothing" mutant by assertion name: with the branch removed the recorded list
 * for 4->5 is empty, and the multi-step paths lose their last step.
 */
class SyncQueueMigratorRecordingExecTest {

    private fun recorded(oldVersion: Int, newVersion: Int): List<String> {
        val executed = mutableListOf<String>()
        runOnUpgrade({ executed.add(it) }, oldVersion, newVersion)
        return executed
    }

    @Test
    fun `4 to 5 executes exactly MIGRATE_V4_TO_V5`() {
        assertEquals(
            "android_sync: the 4->5 branch must run MIGRATE_V4_TO_V5 (the pending_save CREATE)",
            listOf(SyncQueueSql.MIGRATE_V4_TO_V5),
            recorded(4, 5),
        )
    }

    @Test
    fun `3 to 5 executes V3_TO_V4 then V4_TO_V5 in order`() {
        assertEquals(
            "android_sync: the 3->5 branch must run V3_TO_V4 then V4_TO_V5 in order",
            listOf(SyncQueueSql.MIGRATE_V3_TO_V4, SyncQueueSql.MIGRATE_V4_TO_V5),
            recorded(3, 5),
        )
    }

    @Test
    fun `2 to 5 executes V2_TO_V3 then V3_TO_V4 then V4_TO_V5 in order`() {
        assertEquals(
            "android_sync: the 2->5 branch must run V2_TO_V3, V3_TO_V4, V4_TO_V5 in order",
            listOf(SyncQueueSql.MIGRATE_V2_TO_V3, SyncQueueSql.MIGRATE_V3_TO_V4, SyncQueueSql.MIGRATE_V4_TO_V5),
            recorded(2, 5),
        )
    }

    @Test
    fun `1 to 5 executes V1_TO_V2 then V2_TO_V3 then V3_TO_V4 then V4_TO_V5 in order`() {
        assertEquals(
            "android_sync: the 1->5 branch must run V1_TO_V2, V2_TO_V3, V3_TO_V4, V4_TO_V5 in order",
            listOf(
                SyncQueueSql.MIGRATE_V1_TO_V2,
                SyncQueueSql.MIGRATE_V2_TO_V3,
                SyncQueueSql.MIGRATE_V3_TO_V4,
                SyncQueueSql.MIGRATE_V4_TO_V5,
            ),
            recorded(1, 5),
        )
    }

    @Test
    fun `an unknown upgrade path executes nothing`() {
        assertEquals(
            "android_sync: no other upgrade path exists; the dispatch must run no statement",
            emptyList<String>(),
            recorded(5, 6),
        )
    }
}
