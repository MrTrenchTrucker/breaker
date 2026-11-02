package dev.breaker.server.syncapi.db

/** Migration 7: creates the server_settings table for the wrong-password limit admin switch. */
internal object SchemaV7 {
    private val createServerSettings: String =
        "CREATE TABLE server_settings (" +
            "  setting_key TEXT NOT NULL PRIMARY KEY CHECK (length(setting_key) BETWEEN 1 AND 64), " +
            "  int_value INTEGER NOT NULL)"

    val MIGRATION: Migration = Migration(
        7,
        listOf(createServerSettings),
    )
}
