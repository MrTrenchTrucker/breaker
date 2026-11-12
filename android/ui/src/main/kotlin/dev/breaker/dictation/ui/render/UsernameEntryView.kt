package dev.breaker.dictation.ui.render

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.breaker.dictation.ui.screen.auth.AuthPolicy
import dev.breaker.dictation.ui.theme.Theme

/**
 * The user name box: one line, no suggestions, the Next key, and a length limit counted in UTF-16
 * units, the way the auth client counts. A user name is not secret, so it is read as a String.
 */
internal class UsernameEntryView(
    context: Context,
    theme: Theme,
    label: String,
) : AuthFieldView(
    context,
    theme,
    label,
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
) {

    init {
        imeOptions = EditorInfo.IME_ACTION_NEXT
        filters = arrayOf(InputFilter.LengthFilter(AuthPolicy.USERNAME_MAX_CHARS))
    }

    /** The typed user name. */
    fun typedName(): String = text.toString()
}
