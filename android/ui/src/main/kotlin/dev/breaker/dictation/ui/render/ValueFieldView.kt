package dev.breaker.dictation.ui.render

import android.content.Context
import android.view.View
import dev.breaker.dictation.ui.render.controls.TextFieldView
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.theme.Theme

/**
 * The display-only field for a value: the kit's text field showing the node's text, with no
 * typing and no touch. The kit field draws its words in one colour, so the node's muted flag is
 * not drawn yet; that needs a kit change.
 */
internal fun valueFieldView(context: Context, node: ValueField, theme: Theme): View =
    TextFieldView(context, node.text, theme).apply {
        isFocusable = false
        isClickable = false
    }
