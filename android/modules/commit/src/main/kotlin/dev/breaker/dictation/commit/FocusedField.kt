package dev.breaker.dictation.commit

/** What a focused field did with the text it was handed. */
internal enum class FieldCommit { ACCEPTED, REFUSED }

/**
 * A text field of our own keyboard that can take text.
 *
 * Called only inside the main-thread hop. It may throw: the service treats a
 * throwing field as a field that refused.
 */
internal interface FocusedField {
    /** Put [text] into the field and say whether the field took it. */
    fun commitText(text: String): FieldCommit
}

/**
 * Where the service finds the field to type into.
 *
 * Returns a field only while our keyboard is active in a text field; null means
 * there is nowhere to type and the clipboard is the way out.
 */
internal fun interface FocusedFieldSource {
    fun current(): FocusedField?
}
