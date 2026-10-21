package dev.breaker.dictation.history

/**
 * The record that a transcription was deleted, kept so the delete can travel.
 *
 * Deleting a row from the phone's own database is not the same as the row being
 * gone. The Local Server has its own copy and the user's other devices have
 * theirs. A tombstone is what carries the delete to them: sync is to push it,
 * and each device is to remove the row. The record itself goes once it has aged
 * out of the tombstone window. Age is all the module goes by: it cannot know
 * whether sync has carried a tombstone.
 *
 * A tombstone holds an identifier, a time and a reason. It never holds the text,
 * so a purge of the tombstone table cannot leak a transcript, and its
 * [toString] is safe to put in a log (F32).
 */
internal data class Tombstone(
    val id: String,
    val deletedAt: Long,
    val reason: Reason,
) {
    init {
        require(id.isNotBlank()) { "A tombstone needs a non-blank id" }
        require(deletedAt >= 0) { "deletedAt cannot be negative: $deletedAt" }
    }

    /** Where the delete came from. Recorded, never guessed at. */
    enum class Reason(val stored: String) {
        /** The user long-pressed the row in history on this device. */
        USER("user"),

        /** The row aged out of the retention window (F28). */
        RETENTION("retention"),

        /** The delete arrived from the web front end, already made elsewhere. */
        REMOTE("remote"),
        ;

        companion object {
            private val byStored = entries.associateBy(Reason::stored)

            /** The reason as stored, or null when the stored value is unknown. */
            fun fromStored(stored: String): Reason? = byStored[stored]
        }
    }

    /**
     * One row of the `tombstones` table, exactly as it is stored.
     *
     * The shape is the table's — `id, deleted_at, reason` — and [reason] is kept
     * as the word written on the disk rather than as a [Reason], for the same
     * reason [TranscriptionRow] keeps its `source` as text: a stored word this
     * module does not know is then something the caller can decide about instead
     * of something the read has to guess at, and a row that can be read by hand
     * is worth more than a compact one.
     *
     * It is nested here because it is a tombstone's stored shape and nothing
     * else, and because the module's own card test reads only top-level
     * declarations: a nested type is not a module type and needs no card line.
     */
    internal data class StoredRow(
        val id: String,
        val deletedAt: Long,
        val reason: String,
    ) {
        init {
            require(id.isNotBlank()) { "A tombstone row needs a non-blank id" }
            require(deletedAt >= 0) { "deletedAt cannot be negative: $deletedAt" }
        }

        /**
         * The tombstone this row stands for.
         *
         * @throws MappingFailure when the stored reason is not one of the known
         *   words. A row this module cannot interpret is a defect to hear about,
         *   not a delete to quietly drop: a tombstone that vanishes is a delete
         *   the server never learns about, and the deleted row comes back on the
         *   next sync.
         */
        fun toTombstone(): Tombstone =
            toTombstoneOrNull()
                ?: throw MappingFailure("Tombstone $id has an unknown reason '$reason'")

        /**
         * The tombstone, or null when the stored [reason] is not one of the known
         * words. [toTombstone] is the strict form; this is the form a reader uses
         * when a row it cannot interpret is a row to skip and report, not a reason
         * to lose every tombstone behind it.
         */
        internal fun toTombstoneOrNull(): Tombstone? {
            val reason = toReasonOrNull() ?: return null
            return Tombstone(id = id, deletedAt = deletedAt, reason = reason)
        }

        /** The stored reason as a domain value, or null when it is not one of the known words. */
        fun toReasonOrNull(): Reason? = Reason.fromStored(reason)
    }
}
