package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Typeface
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.Theme
import java.util.Locale

/*
 * The words a label shows, as the renderer draws them, and the type a label is drawn in.
 *
 * A node keeps its text as written. Capitals are a way of drawing the text, and the
 * locale is fixed so the same words give the same capitals on every phone. The face and
 * the size come from the same role functions the renderer's labels use, so a text row
 * drawn by its own view matches a label of the same role.
 */

/** [text] in capitals when [caps] is set, in the root locale; otherwise [text] as it is. */
internal fun displayText(text: String, caps: Boolean): String =
    if (caps) text.uppercase(Locale.ROOT) else text

/** [text] without its final full stop when it has one: exactly one character goes, and no other text changes. */
internal fun dropFinalPeriod(text: String): String =
    if (text.endsWith(".")) text.dropLast(1) else text

/** The platform text appearance for a role: large for display text, medium otherwise. */
internal fun appearanceOf(role: TypeRole): Int = when (role) {
    TypeRole.DISPLAY -> android.R.style.TextAppearance_DeviceDefault_Large
    TypeRole.BODY -> android.R.style.TextAppearance_DeviceDefault_Medium
    TypeRole.MONO -> android.R.style.TextAppearance_DeviceDefault_Medium
}

/**
 * The typeface for a role, looked up by the family name the tokens give.
 *
 * No font file is bundled. The system resolves the name; a name it does
 * not know gives the platform default typeface.
 */
internal fun typefaceOf(role: TypeRole, theme: Theme, italic: Boolean = false): Typeface {
    val family = when (role) {
        TypeRole.DISPLAY -> theme.type.displayFamily
        TypeRole.BODY -> theme.type.bodyFamily
        TypeRole.MONO -> theme.type.monoFamily
    }
    return Typeface.create(family, styleOf(role, italic))
}

/** The face style of a role: plain, italic, or bold italic for display text when [italic] is set. */
private fun styleOf(role: TypeRole, italic: Boolean): Int = when {
    !italic -> Typeface.NORMAL
    role == TypeRole.DISPLAY -> Typeface.BOLD_ITALIC
    else -> Typeface.ITALIC
}

/**
 * The text size of a role in pixels: the size the platform appearance of the role gives a label.
 * It is read from the same resource that [appearanceOf] names, the one a label applies.
 */
internal fun roleTextSizePx(context: Context, role: TypeRole): Float {
    val attributes = context.obtainStyledAttributes(appearanceOf(role), intArrayOf(android.R.attr.textSize))
    try {
        return attributes.getDimension(0, 0f)
    } finally {
        attributes.recycle()
    }
}
