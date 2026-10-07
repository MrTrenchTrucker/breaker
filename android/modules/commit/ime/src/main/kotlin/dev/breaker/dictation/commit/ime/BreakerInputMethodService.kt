package dev.breaker.dictation.commit.ime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.breaker.dictation.commit.FocusedFieldRegistry
import dev.breaker.dictation.commit.adapter.FocusedFieldHolder

/**
 * Our keyboard, reduced to the one thing the text commit needs: telling the
 * commit service which text field is focused.
 *
 * No keyboard view and no manifest entry exist yet, so the system does not offer
 * this keyboard to the user. That is why there is no input view override here.
 *
 * The framework creates this service, so the field is shared through
 * [FocusedFieldHolder]. The token kept here is what makes a late "finished" for
 * an older field harmless: it clears only the field this service published last.
 */
class BreakerInputMethodService : InputMethodService() {

    private var token: FocusedFieldRegistry.Token? = null

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        clearPublishedField()
        val connection = currentInputConnection
        if (connection != null && attribute != null && attribute.inputType != InputType.TYPE_NULL) {
            token = FocusedFieldHolder.registry.publish(ImeFocusedField(connection))
        }
    }

    override fun onFinishInput() {
        clearPublishedField()
        super.onFinishInput()
    }

    override fun onDestroy() {
        FocusedFieldHolder.registry.clearAll()
        token = null
        super.onDestroy()
    }

    private fun clearPublishedField() {
        val held = token
        if (held != null) {
            FocusedFieldHolder.registry.clear(held)
        }
        token = null
    }
}
