package dev.breaker.dictation.ui.render

import android.content.Context
import android.text.InputType
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import dev.breaker.dictation.ui.theme.Theme

/** The one NUL character, written over each typed character before the box is emptied. */
private const val ZERO_TEXT = "\u0000"

/** A menu callback that refuses every menu, so no cut, copy or paste menu opens on the box. */
private object RefusingMenu : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

    override fun onDestroyActionMode(mode: ActionMode) {}
}

/**
 * The password box: masked, no suggestions, no long press and no selection menu.
 *
 * [takeTyped] hands out the typed characters as a new array that the caller owns and must zero.
 * [wipeTyped] overwrites every character of the box with NUL and then empties it, and it runs when
 * the view leaves the window. The platform text buffer may keep an internal copy of what was typed
 * (for example inside the input method or in a spell or undo span), and that cannot be proven wiped
 * without a device, so this is a best effort and not a guarantee.
 */
internal class PasswordEntryView(
    context: Context,
    theme: Theme,
    label: String,
) : AuthFieldView(
    context,
    theme,
    label,
    InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
) {

    init {
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isLongClickable = false
        customSelectionActionModeCallback = RefusingMenu
        customInsertionActionModeCallback = RefusingMenu
    }

    /** A new array holding the typed characters; the caller zeroes it. */
    fun takeTyped(): CharArray {
        val out = CharArray(text.length)
        text.getChars(0, text.length, out, 0)
        return out
    }

    /** Overwrites each typed character with NUL, then empties the box. */
    fun wipeTyped() {
        val typed = text
        for (index in 0 until typed.length) {
            typed.replace(index, index + 1, ZERO_TEXT)
        }
        typed.clear()
    }

    override fun onDetachedFromWindow() {
        wipeTyped()
        super.onDetachedFromWindow()
    }
}
