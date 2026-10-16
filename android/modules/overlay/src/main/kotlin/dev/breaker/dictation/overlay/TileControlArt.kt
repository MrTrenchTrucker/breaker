package dev.breaker.dictation.overlay

/**
 * The path data of the two recording buttons, the cancel cross and the send check. The numbers are the
 * path data of the approved svg files breaker_btn_cancel and breaker_btn_send (1024 by 1024 units). The
 * light and dark files carry the same geometry; colours are not here.
 */
internal object ControlArt {
    private val cancelOps = intArrayOf(
        TileArt.OP_MOVE,
        TileArt.OP_LINE, TileArt.OP_LINE, TileArt.OP_LINE, TileArt.OP_LINE,
        TileArt.OP_LINE, TileArt.OP_LINE, TileArt.OP_LINE, TileArt.OP_LINE,
        TileArt.OP_LINE, TileArt.OP_LINE, TileArt.OP_LINE,
        TileArt.OP_CLOSE,
    )
    private val cancelXy = floatArrayOf(
        710f, 371.93f, 586.93f, 495f, 710f, 618.07f, 635.07f, 693f,
        512f, 569.93f, 388.93f, 693f, 314f, 618.07f, 437.07f, 495f,
        314f, 371.93f, 388.93f, 297f, 512f, 420.07f, 635.07f, 297f,
    )
    private val sendOps = intArrayOf(
        TileArt.OP_MOVE,
        TileArt.OP_LINE, TileArt.OP_LINE, TileArt.OP_LINE, TileArt.OP_LINE,
        TileArt.OP_LINE,
        TileArt.OP_CLOSE,
    )
    private val sendXy = floatArrayOf(
        314f, 518.5f, 459.86f, 664.35f, 710f, 385.69f,
        643.1f, 325.65f, 456.36f, 533.79f, 377.53f, 454.96f,
    )

    /** The cancel cross: one move, eleven lines and a close, 24 numbers in path order. */
    val cancel: ArtShape = ArtShape(ArtPart.CANCEL_CROSS, cancelOps, cancelXy)

    /** The send check: one move, five lines and a close, 12 numbers in path order. */
    val send: ArtShape = ArtShape(ArtPart.SEND_CHECK, sendOps, sendXy)
}
