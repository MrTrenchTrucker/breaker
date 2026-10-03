package dev.breaker.dictation.history

/**
 * What the retention cleanup removed.
 *
 * [purged] and [tombstoned] are counts, and in the normal case they are equal:
 * every expired row is deleted *and* gets a tombstone, because the server holds
 * its own copy and has to be told. [cutoff] is the instant the window closed,
 * carried out so a caller can see exactly which rows were on the wrong side of
 * it — the boundary is the part worth being able to look at.
 */
data class PurgeReport(
    val purged: Int,
    val tombstoned: Int,
    val cutoff: Long,
) {
    init {
        require(purged >= 0) { "purged cannot be negative: $purged" }
        require(tombstoned >= 0) { "tombstoned cannot be negative: $tombstoned" }
    }

    /**
     * True when the number of tombstones written equals the number of rows removed.
     *
     * A purge that deleted rows without tombstoning them would leave the server
     * holding transcriptions the phone no longer has, and the next sync would
     * push them straight back. That is the same user-visible loss as never
     * purging, so it is a state this class refuses to describe as fine.
     */
    val isConsistent: Boolean get() = purged == tombstoned
}

/** What a tombstone sweep removed. */
internal data class TombstonePurgeReport(
    val dropped: Int,
    val cutoff: Long,
) {
    init {
        require(dropped >= 0) { "dropped cannot be negative: $dropped" }
    }
}
