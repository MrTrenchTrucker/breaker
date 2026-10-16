package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/**
 * The part of a control a colour belongs to, one per role the art draws. A part names a role and
 * [partColor] decides what the role looks like in a mode; no part carries a colour of its own.
 */
internal enum class ControlPart {
    FACE,             // the filled face of a raised button
    FACE_PRESSED,     // the filled face of a raised button while it is pushed down
    FACE_DANGER,      // the filled face of a destructive button
    FACE_WARNING,     // the filled face of a warning badge
    FACE_SURFACE,     // the flat surface fill
    FACE_BACKGROUND,  // the page fill behind the outlined button
    LIP,              // the lip under a filled face
    LIP_DANGER,       // the lip under a destructive face
    LIP_NEUTRAL,      // the lip under the neutral face
    LIP_ACCENT,       // the lip under the outlined face
    LIT_LINE,         // the lit line on a filled face
    LIT_LINE_DANGER,  // the lit line on a destructive face
    SHADOW_LINE,      // the shadow line on a pressed face
    LABEL,            // the label on a filled face
    LABEL_NEUTRAL,    // the label on the neutral face
    LABEL_DISABLED,   // the label on a disabled button
    LABEL_ACCENT,     // the label on the outlined face
    OUTLINE,          // the muted outline
    OUTLINE_ACCENT,   // the accent outline
    TRACK,            // the switch track while on
    TRACK_OFF,        // the switch track while off
    THUMB,            // the thumb while on
    THUMB_OFF,        // the thumb while off
    FIELD,            // the field fill
    FIELD_OUTLINE,    // the field outline
    FIELD_TEXT_MUTED, // the words of a muted field; the art is silent about it
    STEP_DONE,        // a done step mark
    STEP_TODO,        // a todo step mark
}

/**
 * Which of the three status badges a badge is; the kind picks the fill.
 */
internal enum class BadgeKind {
    /** Dictation is ready: the primary fill. */
    READY,

    /** Committed by the phone model after the server path failed: the warning fill. */
    PHONE_ONLY,

    /** The last try did not work: the danger fill. */
    FAILED,
}

/**
 * The colour of one [ControlPart] in [theme], and the only place a slot becomes a value. Every part
 * maps to a slot of the palette; the lip, the lit line and the shadow line shade the face they sit on
 * by the named factors, and the label on a filled face takes the on slot.
 */
internal object ControlsPalette {

    fun partColor(part: ControlPart, theme: Theme): Int {
        val face = theme.color(PaletteSlot.PRIMARY).argb
        val hover = theme.color(PaletteSlot.PRIMARY_HOVER).argb
        val danger = theme.color(PaletteSlot.DANGER).argb
        return when (part) {
            ControlPart.FACE -> face
            ControlPart.FACE_PRESSED -> hover
            ControlPart.FACE_DANGER -> danger
            ControlPart.FACE_WARNING -> theme.color(PaletteSlot.STATE_WARNING).argb
            ControlPart.FACE_SURFACE -> theme.color(PaletteSlot.SURFACE).argb
            ControlPart.FACE_BACKGROUND -> theme.color(PaletteSlot.BACKGROUND).argb
            ControlPart.LIP -> shade(face, ControlsFactors.LIP)
            ControlPart.LIP_DANGER -> shade(danger, ControlsFactors.LIP)
            ControlPart.LIP_NEUTRAL -> shade(theme.color(PaletteSlot.TEXT_MUTED).argb, ControlsFactors.NEUTRAL_LIP)
            ControlPart.LIP_ACCENT -> shade(theme.color(PaletteSlot.ACCENT).argb, ControlsFactors.LIP)
            ControlPart.LIT_LINE -> shade(face, ControlsFactors.LIT_LINE)
            ControlPart.LIT_LINE_DANGER -> shade(danger, ControlsFactors.LIT_LINE)
            ControlPart.SHADOW_LINE -> shade(hover, ControlsFactors.LIP)
            ControlPart.LABEL -> theme.color(PaletteSlot.ON).argb
            ControlPart.LABEL_NEUTRAL -> theme.color(PaletteSlot.TEXT).argb
            ControlPart.LABEL_DISABLED -> theme.color(PaletteSlot.TEXT_MUTED).argb
            ControlPart.LABEL_ACCENT -> theme.color(PaletteSlot.ACCENT).argb
            ControlPart.OUTLINE -> theme.color(PaletteSlot.TEXT_MUTED).argb
            ControlPart.OUTLINE_ACCENT -> theme.color(PaletteSlot.ACCENT).argb
            ControlPart.TRACK -> theme.color(PaletteSlot.PRIMARY).argb
            ControlPart.TRACK_OFF -> theme.color(PaletteSlot.SURFACE).argb
            ControlPart.THUMB -> theme.color(PaletteSlot.ON).argb
            ControlPart.THUMB_OFF -> theme.color(PaletteSlot.TEXT_MUTED).argb
            ControlPart.FIELD -> theme.color(PaletteSlot.SURFACE).argb
            ControlPart.FIELD_OUTLINE -> theme.color(PaletteSlot.ACCENT).argb
            ControlPart.FIELD_TEXT_MUTED -> theme.color(PaletteSlot.TEXT_MUTED).argb
            ControlPart.STEP_DONE -> theme.color(PaletteSlot.ACCENT).argb
            ControlPart.STEP_TODO -> theme.color(PaletteSlot.TEXT_MUTED).argb
        }
    }

    /** The fill part a badge of [kind] takes, read from the palette by part. */
    fun badgeFacePart(kind: BadgeKind): ControlPart = when (kind) {
        BadgeKind.READY -> ControlPart.FACE
        BadgeKind.PHONE_ONLY -> ControlPart.FACE_WARNING
        BadgeKind.FAILED -> ControlPart.FACE_DANGER
    }
}
