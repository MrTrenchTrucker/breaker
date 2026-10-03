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
}
