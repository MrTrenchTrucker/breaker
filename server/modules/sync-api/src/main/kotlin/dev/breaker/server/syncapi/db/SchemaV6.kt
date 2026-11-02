package dev.breaker.server.syncapi.db

import dev.breaker.server.syncapi.accounts.UsernameRules

/** Migration 6: creates the signin_attempts table for the wrong-password limit (new table, not columns on accounts). */
internal object SchemaV6 {
    private val createSigninAttempts: String =
        "CREATE TABLE signin_attempts (" +
            "  username_lower TEXT NOT NULL PRIMARY KEY " +
            "CHECK (length(username_lower) BETWEEN 1 AND ${UsernameRules.MAX_LENGTH} AND username_lower = lower(username_lower)), " +
            "  wrong_count    INTEGER NOT NULL DEFAULT 0 CHECK (wrong_count >= 0), " +
            "  locked_until_ms INTEGER NOT NULL DEFAULT 0 CHECK (locked_until_ms >= 0), " +
            "  last_attempt_ms INTEGER NOT NULL CHECK (last_attempt_ms >= 0))"

    val MIGRATION: Migration = Migration(
        6,
        listOf(createSigninAttempts),
    )
}
