package dev.breaker.server.syncapi.db

/** Migration 11: creates the rate_limit_counters table for per-address rate limits. */
internal object SchemaV11 {
    private val createRateLimitCounters: String =
        "CREATE TABLE rate_limit_counters (" +
            "  route            TEXT NOT NULL, " +
            "  address          TEXT NOT NULL, " +
            "  count            INTEGER NOT NULL DEFAULT 0 CHECK (count >= 0), " +
            "  window_start_ms  INTEGER NOT NULL CHECK (window_start_ms >= 0), " +
            "  PRIMARY KEY (route, address))"

    val MIGRATION: Migration = Migration(
        11,
        listOf(createRateLimitCounters),
    )
}
