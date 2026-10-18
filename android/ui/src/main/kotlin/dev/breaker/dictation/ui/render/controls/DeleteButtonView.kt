package dev.breaker.dictation.ui.render.controls

import android.content.Context
import dev.breaker.dictation.ui.controls.ControlPart
import dev.breaker.dictation.ui.theme.Theme

/**
 * The destructive button as one Android view: the main button's own shape in
 * the danger colours — the danger face, its lip and its lit line, and the
 * label in the on colour, which on the dark theme is the dark background.
 *
 * It is [Button3dView] with the parts it draws overridden, so the look stays
 * in one place and only the face changes.
 */
internal class DeleteButtonView(
    context: Context,
    /** The words on the button, as the caller stores them. Drawn in capitals. */
    label: String,
    /** The theme the parts are painted in. */
    theme: Theme,
    /** Whether the button takes the compact floor, passed up to the main button. */
    compact: Boolean = false,
) : Button3dView(context, label, theme, compact) {

    override fun facePart(): ControlPart = ControlPart.FACE_DANGER

    override fun pressedFacePart(): ControlPart = ControlPart.FACE_DANGER

    override fun lipPart(): ControlPart = ControlPart.LIP_DANGER

    override fun litLinePart(): ControlPart = ControlPart.LIT_LINE_DANGER

    override fun topLinePart(): ControlPart = ControlPart.LIP_DANGER
}
