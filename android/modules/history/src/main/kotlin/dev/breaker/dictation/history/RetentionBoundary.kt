package dev.breaker.dictation.history

/**
 * "Outside the retention window", written down once.
 *
 * Both the SQL and the in-memory database used by the tests have to agree about
 * this, and they are written separately — one is a string SQLite parses, the
 * other is a Kotlin comparison. A rule stated in two places is a rule that will
 * eventually be true in one of them.
 *
 * So: the SQL predicates below are the rule that ships, [HistorySql] quotes them
 * into its `WHERE` clauses, and [isExpired] is the same rule in Kotlin. The
 * shipped purge runs the SQL; [isExpired] is modelled and tested, not shipped,
 * and `RetentionBoundaryAgreementTest` fails if the two ever drift apart.
 */
internal object RetentionBoundary {
    /**
     * True when a row created at [createdAt] has fallen outside a window that
     * closed at [cutoff].
     *
     * Strictly before. A row created at exactly [cutoff] is in date. The Kotlin
     * form of [CREATED_AT_PREDICATE]. It is modelled and tested, not shipped: the
     * shipped purge applies [CREATED_AT_PREDICATE] in SQL.
     */
    fun isExpired(createdAt: Long, cutoff: Long): Boolean = createdAt < cutoff

    /** The same rule as SQL, for [HistorySql] to build its predicates from. */
    const val CREATED_AT_PREDICATE: String = "created_at < ?"

    /** The same rule for tombstones, which expire on their own clock. */
    const val DELETED_AT_PREDICATE: String = "deleted_at < ?"
}
