package dev.breaker.dictation.ui.render

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.breaker.dictation.ui.screen.auth.AuthPolicy
import dev.breaker.dictation.ui.theme.Theme

/**
 * The install code box: one line, no suggestions, the Done key, and a length limit counted in
 * UTF-16 units against a runaway paste. The approval says a plain text field, so it is NOT
 * masked: it carries no [InputType.TYPE_TEXT_VARIATION_PASSWORD], the flag that masks the password
 * box. An install code is not a secret, so it is read as a String.
 */
internal class InstallCodeEntryView(
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
        imeOptions = EditorInfo.IME_ACTION_DONE
        filters = arrayOf(InputFilter.LengthFilter(AuthPolicy.INSTALL_CODE_MAX_CHARS))
    }

    /** The typed install code. */
    fun typedCode(): String = text.toString()
}
