package dev.breaker.dictation.ui.render

import android.content.Context
import android.view.View
import dev.breaker.dictation.ui.render.controls.TextFieldView
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.theme.Theme

/**
 * The display-only field for a value: the kit's text field showing the node's text, with no
 * typing and no touch. A muted value (nothing set yet) has its words in the muted colour; the fill
 * and the outline do not change. The art does not draw a muted field, so this is the smallest look
 * from existing tokens.
 */
internal fun valueFieldView(context: Context, node: ValueField, theme: Theme): View =
    TextFieldView(context, node.text, theme, node.muted).apply {
        isFocusable = false
        isClickable = false
    }
