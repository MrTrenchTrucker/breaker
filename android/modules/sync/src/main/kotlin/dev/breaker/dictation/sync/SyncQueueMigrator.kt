package dev.breaker.dictation.sync

/**
 * The migration dispatch that [SqliteSyncQueueDatabase.SyncQueueOpenHelper.onUpgrade] runs,
 * exposed as a seam so a JVM test can drive the real onUpgrade path against a real SQLite
 * store (the [DesktopSqlite]/[JdbcSyncQueueDatabase] twin) instead of pinning the migration
 * SQL text in the test.
 *
 * [exec] runs one migration statement. This is exactly what onUpgrade runs, statement for
 * statement, in the same order, including the multi-step paths 1->3, 2->4 and 1->4.
 */
internal fun runOnUpgrade(exec: (sql: String) -> Unit, oldVersion: Int, newVersion: Int) {
    when {
        oldVersion == 1 && newVersion == 2 -> exec(SyncQueueSql.MIGRATE_V1_TO_V2)
        oldVersion == 2 && newVersion == 3 -> exec(SyncQueueSql.MIGRATE_V2_TO_V3)
        oldVersion == 3 && newVersion == 4 -> exec(SyncQueueSql.MIGRATE_V3_TO_V4)
        oldVersion == 1 && newVersion == 3 -> {
            exec(SyncQueueSql.MIGRATE_V1_TO_V2)
            exec(SyncQueueSql.MIGRATE_V2_TO_V3)
        }
        oldVersion == 2 && newVersion == 4 -> {
            exec(SyncQueueSql.MIGRATE_V2_TO_V3)
            exec(SyncQueueSql.MIGRATE_V3_TO_V4)
        }
        oldVersion == 1 && newVersion == 4 -> {
            exec(SyncQueueSql.MIGRATE_V1_TO_V2)
            exec(SyncQueueSql.MIGRATE_V2_TO_V3)
            exec(SyncQueueSql.MIGRATE_V3_TO_V4)
        }
        else -> {
            // No other upgrade path exists yet. The queue table is never dropped here: it holds
            // transcriptions that have not been sent yet.
        }
    }
}
