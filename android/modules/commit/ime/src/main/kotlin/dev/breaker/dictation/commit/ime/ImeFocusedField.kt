package dev.breaker.dictation.commit.ime

import android.view.inputmethod.InputConnection
import dev.breaker.dictation.commit.FieldCommit
import dev.breaker.dictation.commit.FocusedField

/**
 * The text field our keyboard is currently typing into.
 *
 * Only the keyboard service builds one, and only while a field is focused; the
 * commit service calls it on the main thread.
 */
internal class ImeFocusedField(private val connection: InputConnection) : FocusedField {

    override fun commitText(text: String): FieldCommit =
        if (connection.commitText(text, CURSOR_AFTER_TEXT)) FieldCommit.ACCEPTED else FieldCommit.REFUSED

    private companion object {
        // A positive position puts the cursor after the inserted text, which is
        // where the user keeps typing.
        const val CURSOR_AFTER_TEXT: Int = 1
    }
}
