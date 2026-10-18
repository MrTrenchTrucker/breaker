package dev.breaker.dictation.overlay

/*
 * The close target picture as path data: the truck, the straps behind the load, the load and the straps in front.
 * Each number is copied from the idle close target art file, with an f suffix.
 */

/** Path data of the close target art, on its 740 by 200 canvas, drawn with one uniform scale. */
internal object CloseTargetArt {

    /** The stroke width of the straps, in art units. */
    const val STRAP_ART: Float = 3f

    /** The truck: its sub-paths 1 to 31, 311 commands. */
    val TRUCK: List<PathCmd> = CloseTargetTruckOne.COMMANDS + CloseTargetTruckTwo.COMMANDS

    /** The straps drawn behind the load: the fourth path of the art. */
    val STRAPS_BEHIND: List<PathCmd> = listOf(
        MoveTo(252.7f, 105.1f),
        LineTo(180f, 146f),
        MoveTo(266.9f, 126.9f),
        LineTo(278f, 146f),
        MoveTo(325.3f, 105.1f),
        LineTo(398f, 146f),
        MoveTo(311.1f, 126.9f),
        LineTo(300f, 146f),
    )

    /** The load, two closed polygons: the fifth path of the art. */
    val LOAD: List<PathCmd> = listOf(
        MoveTo(345.5f, 45.9f),
        LineTo(200.9f, 140f),
        LineTo(244.9f, 140f),
        LineTo(358.5f, 66.1f),
        ClosePath,
        MoveTo(219.5f, 66.1f),
        LineTo(333.1f, 140f),
        LineTo(377.1f, 140f),
        LineTo(232.5f, 45.9f),
        ClosePath,
    )

    /** The straps drawn in front of the load: the sixth path of the art. */
    val STRAPS_OVER: List<PathCmd> = listOf(
        MoveTo(252.7f, 105.1f),
        LineTo(266.9f, 126.9f),
        MoveTo(325.3f, 105.1f),
        LineTo(311.1f, 126.9f),
    )
}
