package dev.breaker.dictation.ui.screen.firstrun

import kotlin.math.floor

/*
 * The sizes of the first-run pages, kept in one plain place.
 *
 * The render files hold no number of their own: every distance they use is read here by
 * name, and the conversion from density-independent pixels to pixels is done here as well.
 * The art gives the step mark as 50 px wide and 12 px tall at 1.5 px per dp, which is the
 * 33 dp and 8 dp below.
 */

/** The sizes and the conversions the first-run views use. */
internal object FirstRunLayout {
    /** The space kept inside the edge of every page, on all four sides. */
    const val PAGE_PADDING_DP: Float = 16f

    /** The space left above each element of a page, below the one before it. */
    const val GAP_DP: Float = 12f

    /** The width of one slanted step mark: the art's 50 px at 1.5 px per dp. */
    const val MARK_W_DP: Float = 33f

    /** The height of one slanted step mark: the art's 12 px at 1.5 px per dp. */
    const val MARK_H_DP: Float = 8f

    /** The width of a permission picture in the art, in pixels. */
    const val PICTURE_WIDTH_PX: Int = 960

    /** The height of a permission picture in the art, in pixels. */
    const val PICTURE_HEIGHT_PX: Int = 640

    /** The tag the download button carries, so the view can find it to give it focus. */
    const val DOWNLOAD_BUTTON_TAG: String = "first.run.download.button"

    /** The height in dp of a picture that is [widthDp] wide, keeping the art's 3 to 2 shape. */
    fun pictureHeightDp(widthDp: Float): Float = widthDp * PICTURE_HEIGHT_PX / PICTURE_WIDTH_PX

    /** The pixels of [dp] at [density], to the nearest whole pixel and never below zero. */
    fun dpToPx(dp: Float, density: Float): Int = floor(dp * density + HALF).toInt().coerceAtLeast(0)

    /** Half a pixel, the offset that turns a floor into the nearest whole pixel. */
    private const val HALF: Float = 0.5f
}
