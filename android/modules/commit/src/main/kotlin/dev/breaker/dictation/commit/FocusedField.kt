package dev.breaker.dictation.commit

/** What a focused field did with the text it was handed. */
enum class FieldCommit { ACCEPTED, REFUSED }

/**
 * A text field that a text-insert mechanism (ADR-022) has published as
 * focused, and that can take text.
 *
 * A text-insert mechanism (for example `commit/accessibility`, built as its
 * own Gradle module) implements this and publishes it through
 * `adapter.FocusedFieldHolder.publish`. It is called only inside the
 * main-thread hop of an explicit send, and only there. It may throw: the
 * service treats a throwing field as a field that refused.
 */
interface FocusedField {
    /** Put [text] into the field and say whether the field took it. */
    fun commitText(text: String): FieldCommit
}

/**
 * Where the service finds the field to type into.
 *
 * Returns a field only while a text-insert mechanism has published one as
 * focused; null means there is nowhere to type and the clipboard is the way
 * out.
 */
internal fun interface FocusedFieldSource {
    fun current(): FocusedField?
}
