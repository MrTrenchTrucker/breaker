package dev.breaker.dictation.sync

/** The result of applying one pull result to the PullSink port. */
internal class ApplyReport(
    val stored: Int,
    val refusedByTombstone: Int,
    val skippedQueued: Int,
    val deleted: Int,
    val unreadable: Int,
    val removedByPrune: Int,
    val pruned: Boolean,
) {
    override fun toString(): String =
        "ApplyReport(" +
            "stored=$stored, " +
            "refusedByTombstone=$refusedByTombstone, " +
            "skippedQueued=$skippedQueued, " +
            "deleted=$deleted, " +
            "unreadable=$unreadable, " +
            "removedByPrune=$removedByPrune, " +
            "pruned=$pruned)"
}
