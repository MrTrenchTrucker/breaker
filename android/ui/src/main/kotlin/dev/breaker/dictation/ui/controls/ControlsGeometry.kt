package dev.breaker.dictation.ui.controls

import java.util.Locale
import kotlin.math.PI
import kotlin.math.tan

/**
 * The drawn form of a stored label: capitals, in the ROOT locale. The labels are English
 * and the art draws them in capitals, so the locale is ROOT and the phone's is never used:
 * on a Turkish phone the default locale would turn a stored "i" into a dotted capital.
 */
internal fun drawnLabel(stored: String): String = stored.uppercase(Locale.ROOT)

/**
 * A rectangle in dp: [x] and [y] are its top-left corner, [width] and [height] its size.
 */
internal data class Rect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * The parts of a button, in dp. [lip] is the band under the face and [litLine] the line along its top;
 * either is null when the state draws neither, and [labelTop] is the top edge of the label's line.
 */
internal data class ButtonParts(
    val face: Rect,
    val lip: Rect?,
    val litLine: Rect?,
    val labelTop: Float,
)

/**
 * The parts of a switch, in dp: the track, the thumb, and the outline, which is the track while the switch
 * is off and null while it is on.
 */
internal data class SwitchParts(val track: Rect, val thumb: Rect, val outline: Rect?)

/**
 * One step mark, in dp: a parallelogram [width] wide and [height] tall whose top edge is shifted right
 * by [slant].
 */
internal data class StepMark(val x: Float, val y: Float, val width: Float, val height: Float, val slant: Float)

/**
 * The numbers the controls are drawn with, in dp. The art gives them in px at 1.5 px per dp, and each
 * constant says its px. This is plain Kotlin with no platform names: the view that draws these numbers
 * belongs to another layer.
 */
internal object ControlsGeometry {

    /** The lit line's inset from the face's left and right edges: 2 px. */
    const val LIT_INSET_DP: Float = 4f / 3f
    /** The lit line's offset from the face's top edge: 1 px. */
    const val LIT_OFFSET_DP: Float = 2f / 3f
    /** The label text size the art passes as its "cap" parameter: 20 px. Used as the text size, not a measured cap height. */
    const val LABEL_CAP_DP: Float = 40f / 3f
    /** The pressed label sits this far below the raised one: 1 px. */
    const val LABEL_LIFT_DP: Float = 2f / 3f
    /** The outline's thickness: 2 px. */
    const val OUTLINE_DP: Float = 4f / 3f
    /** The thumb's left edge while the switch is on: 36 px. */
    const val THUMB_ON_OFFSET_DP: Float = 24f
    /** The thumb's width and height while on: 32 px. */
    const val THUMB_ON_SIZE_DP: Float = 64f / 3f
    /** The thumb's left edge while off: 5 px. */
    const val THUMB_OFF_OFFSET_DP: Float = 10f / 3f
    /** The thumb's width and height while off: 30 px. */
    const val THUMB_OFF_SIZE_DP: Float = 20f
    /** The badge's height: 40 px. */
    const val BADGE_H_DP: Float = 80f / 3f
    /** The switch track's width: the art's own 72 by 40 px track at 1.5 px per dp, 48 dp. */
    const val TRACK_W_DP: Float = 48f
    /** The switch track's height: the art's own 72 by 40 px track at 1.5 px per dp, 40 px, 80 / 3 dp. */
    const val TRACK_H_DP: Float = 80f / 3f
    /** The badge's width before its text: 40 px. */
    const val BADGE_PAD_DP: Float = 80f / 3f
    /** The badge's width per character of its text: 13 px. */
    const val BADGE_CHAR_DP: Float = 26f / 3f
    /** The badge label text size the art passes as its "cap" parameter: 15 px. Used as the text size, not a measured cap height. */
    const val BADGE_CAP_DP: Float = 10f
    /** The first step mark's left edge: 30 px. */
    const val STEP_INSET_DP: Float = 20f
    /** The gap between two step marks: 12 px. */
    const val STEP_GAP_DP: Float = 8f
    /** The side padding of a button, 16 dp each side: the art has no padding rule (its buttons are a fixed 170 px wide); 16 dp each side is the kit's own choice. */
    const val BUTTON_SIDE_DP: Float = 16f
    /** The width the art draws every button that is not full width: 170 px. */
    const val BUTTON_MIN_WIDTH_DP: Float = 340f / 3f
    /** The corner radius of both thumbs: 3 px. */
    const val THUMB_CORNER_DP: Float = 2f

    /**
     * The parts of the raised button, [w] wide and [h] tall, at the origin. The normal state carries
     * the face, the lip under it and the lit line on top; the pressed state is the face pushed down by
     * [ControlsFactors.PRESS_DP] with no lip, its top line shaded by the lip factor instead.
     */
    fun button(w: Float, h: Float, pressed: Boolean): ButtonParts {
        val lip = ControlsFactors.LIP_DP
        return if (pressed) {
            ButtonParts(
                face = Rect(0f, ControlsFactors.PRESS_DP, w, h - lip),
                lip = null,
                litLine = Rect(
                    LIT_INSET_DP,
                    ControlsFactors.PRESS_DP + LIT_OFFSET_DP,
                    w - 2f * LIT_INSET_DP,
                    ControlsFactors.LIT_DP,
                ),
                labelTop = ControlsFactors.PRESS_DP + (h - lip - LABEL_CAP_DP) / 2f + LABEL_LIFT_DP,
            )
        } else {
            ButtonParts(
                face = Rect(0f, 0f, w, h - lip),
                lip = Rect(0f, h - lip, w, lip),
                litLine = Rect(LIT_INSET_DP, LIT_OFFSET_DP, w - 2f * LIT_INSET_DP, ControlsFactors.LIT_DP),
                labelTop = (h - lip - LABEL_CAP_DP) / 2f,
            )
        }
    }

    /** The parts of the neutral button: the surface fill, the muted outline and the lip. */
    fun neutral(w: Float, h: Float): ButtonParts = lipped(w, h)

    /** The parts of the outlined button: the page fill, the accent outline and the lip. */
    fun outlined(w: Float, h: Float): ButtonParts = lipped(w, h)

    /** The parts of a raised button with a lip and no lit line, which the neutral and the outlined button share. */
    private fun lipped(w: Float, h: Float): ButtonParts {
        val lip = ControlsFactors.LIP_DP
        return ButtonParts(
            face = Rect(0f, 0f, w, h - lip),
            lip = Rect(0f, h - lip, w, lip),
            litLine = null,
            labelTop = (h - lip - LABEL_CAP_DP) / 2f,
        )
    }

    /** The parts of the disabled button: the flat surface fill, no lip and no lit line. */
    fun disabled(w: Float, h: Float): ButtonParts = ButtonParts(
        face = Rect(0f, 0f, w, h),
        lip = null,
        litLine = null,
        labelTop = (h - LABEL_CAP_DP) / 2f,
    )

    /**
     * The parts of the switch, a track [w] wide and [h] tall with its thumb. The thumb sits at the
     * art's offsets, centred on the track's height, which for the art's own 72 by 40 px track is the
     * art's own placement.
     */
    fun switch(on: Boolean, w: Float, h: Float): SwitchParts {
        val thumb = if (on) {
            Rect(THUMB_ON_OFFSET_DP, (h - THUMB_ON_SIZE_DP) / 2f, THUMB_ON_SIZE_DP, THUMB_ON_SIZE_DP)
        } else {
            Rect(THUMB_OFF_OFFSET_DP, (h - THUMB_OFF_SIZE_DP) / 2f, THUMB_OFF_SIZE_DP, THUMB_OFF_SIZE_DP)
        }
        val track = Rect(0f, 0f, w, h)
        return SwitchParts(track = track, thumb = thumb, outline = if (on) null else track)
    }

    /** The badge for [text]: the art's height, and its width the padding plus the text at the art's per-character width. */
    fun badge(text: String): Rect =
        Rect(0f, 0f, BADGE_PAD_DP + BADGE_CHAR_DP * text.length, BADGE_H_DP)

    /**
     * The width of a button for a label [labelWidthDp] wide: the art's 170 px, or the label plus the side
     * padding on each side when that is wider.
     */
    fun buttonWidth(labelWidthDp: Float): Float = maxOf(BUTTON_MIN_WIDTH_DP, labelWidthDp + 2f * BUTTON_SIDE_DP)

    /**
     * The width in px of a full width button: the measured row when the parent measured one, else the label's
     * own width, and never below [floorPx].
     */
    fun fullWidthPx(rowPx: Int?, labelPx: Int, floorPx: Int): Int =
        if (rowPx != null) maxOf(rowPx, floorPx) else maxOf(labelPx, floorPx)

    /** Whether step mark [index] (0-based) is lit when the current step is [current] (1-based). */
    fun isStepLit(index: Int, current: Int): Boolean = index < current

    /** The pill of [text], centred in a box [boxW] wide and [boxH] tall, never starting left of or above the box. */
    fun badgePill(text: String, boxW: Float, boxH: Float): Rect {
        val pill = badge(text)
        return Rect(
            x = maxOf(0f, (boxW - pill.width) / 2f),
            y = maxOf(0f, (boxH - pill.height) / 2f),
            width = pill.width,
            height = pill.height,
        )
    }

    /** The text field, [w] wide and [h] tall: the surface fill with the accent outline. */
    fun textField(w: Float, h: Float): Rect = Rect(0f, 0f, w, h)

    /**
     * The [count] step marks, each [w] wide and [h] tall, slanted [ControlsFactors.SLANT_DEG] and
     * spaced the art's gap apart from the art's first inset.
     */
    fun stepMarks(count: Int, w: Float, h: Float): List<StepMark> {
        val slant = tan(ControlsFactors.SLANT_DEG * PI / 180.0).toFloat() * h
        return List(count) { index ->
            StepMark(
                x = STEP_INSET_DP + index * (w + STEP_GAP_DP),
                y = 0f,
                width = w,
                height = h,
                slant = slant,
            )
        }
    }
}
