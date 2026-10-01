package dev.breaker.dictation.core.port

/**
 * Turns a raw transcript into structured text.
 *
 * Implemented by the format module, twice: the server's language model on the
 * server path, and a deterministic rule-based formatter when the phone is on
 * its own.
 *
 * Formatting is non-destructive. It may add punctuation, casing and list
 * structure; it may not add, drop or reorder what was said. The diff check
 * belongs to the format module, whose golden tests enforce it — this port only
 * states the rule.
 */
interface Formatter {
    /**
     * Format [rawText] for insertion. The result carries no content that was not
     * in [rawText].
     */
    fun format(rawText: String): String
}
