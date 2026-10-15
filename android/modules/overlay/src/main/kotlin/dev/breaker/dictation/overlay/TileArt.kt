package dev.breaker.dictation.overlay

/**
 * The parts of the microphone art, one per drawn shape. The art is numeric path data taken from the
 * approved svg (1024 by 1024 units); each part names the svg line it comes from.
 */
internal enum class ArtPart {
    TRIGGER_BODY,      // svg line 19 (silhouette, outlined)
    TRIGGER_FACE,      // svg line 20
    TRIGGER_STRIPE,    // svg line 21
    HEAD_BODY,         // svg line 22 (silhouette, outlined; head plus tapered lower body)
    CORD_BODY,         // svg line 23 (silhouette, outlined)
    LOWER_LEFT,        // svg line 24
    LOWER_RIGHT,       // svg line 25
    FRAME_TOP,         // svg line 26
    FRAME_LEFT,        // svg line 27
    FRAME_BOTTOM,      // svg line 28
    FRAME_RIGHT,       // svg line 29
    WELL,              // svg line 30
    SLOT,              // svg lines 31, 33, 35, 37, 39, 41 (the slot body)
    SLOT_TOP,          // svg lines 32, 34, 36, 38, 40, 42 (the strip on top of a slot)
    LEDGE_EDGE,        // svg line 43
    LEDGE_FACE,        // svg line 44
    LEDGE_END_LEFT,    // svg line 45
    LEDGE_END_RIGHT,   // svg line 46
    LETTERS,           // svg line 47 (BREAKER, one shape, 12 contours)
}

/**
 * One closed-contour shape. [ops] holds one code per command (0 move, 1 line, 2 cubic, 3 close); [xy]
 * holds the numbers of the commands in order: two for a move or a line, six for a cubic, none for a close.
 */
internal class ArtShape(val part: ArtPart, val ops: IntArray, val xy: FloatArray)

/** One slot of the meter: the slot body and the strip on its top. */
internal class ArtSlot(val body: ArtShape, val top: ArtShape)

/** The microphone art in drawing order. Every coordinate lies in 0..[BOX]. */
internal object TileArt {
    const val BOX: Float = 1024f
    const val OP_MOVE = 0
    const val OP_LINE = 1
    const val OP_CUBIC = 2
    const val OP_CLOSE = 3

    private val triggerBody = ArtShape(ArtPart.TRIGGER_BODY, intArrayOf(
        0, 1, 2, 1, 2, 1, 2, 1,
        2, 3
    ), floatArrayOf(
        198f, 150f, 222f, 150f, 229.732f, 150f, 236f, 156.268f,
        236f, 164f, 236f, 456f, 236f, 463.732f, 229.732f, 470f,
        222f, 470f, 198f, 470f, 190.268f, 470f, 184f, 463.732f,
        184f, 456f, 184f, 164f, 184f, 156.268f, 190.268f, 150f,
        198f, 150f))
    private val triggerFace = ArtShape(ArtPart.TRIGGER_FACE, intArrayOf(
        0, 1, 2, 1, 2, 1, 2, 1,
        2, 3
    ), floatArrayOf(
        196f, 150f, 198f, 150f, 204.628f, 150f, 210f, 155.372f,
        210f, 162f, 210f, 458f, 210f, 464.628f, 204.628f, 470f,
        198f, 470f, 196f, 470f, 189.372f, 470f, 184f, 464.628f,
        184f, 458f, 184f, 162f, 184f, 155.372f, 189.372f, 150f,
        196f, 150f))
    private val triggerStripe = ArtShape(ArtPart.TRIGGER_STRIPE, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        218f, 174f, 226f, 174f, 226f, 446f, 218f, 446f))
    private val headBody = ArtShape(ArtPart.HEAD_BODY, intArrayOf(
        0, 1, 2, 1, 1, 1, 2, 1,
        2, 1, 1, 1, 2, 3
    ), floatArrayOf(
        304f, 40f, 720f, 40f, 746.51f, 40f, 768f, 61.49f,
        768f, 88f, 768f, 520f, 762f, 520f, 728.58f, 810.26f,
        725.527f, 836.753f, 710.667f, 850f, 684f, 850f, 340f, 850f,
        313.333f, 850f, 298.473f, 836.753f, 295.42f, 810.26f, 262f, 520f,
        256f, 520f, 256f, 88f, 256f, 61.49f, 277.49f, 40f,
        304f, 40f))
    private val cordBody = ArtShape(ArtPart.CORD_BODY, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        468f, 846f, 556f, 846f, 556f, 960f, 468f, 960f))
    private val lowerLeft = ArtShape(ArtPart.LOWER_LEFT, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        262f, 520f, 296f, 520f, 326f, 790f, 295f, 810f))
    private val lowerRight = ArtShape(ArtPart.LOWER_RIGHT, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        762f, 520f, 728f, 520f, 698f, 790f, 729f, 810f))
    private val frameTop = ArtShape(ArtPart.FRAME_TOP, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        300f, 84f, 724f, 84f, 708f, 100f, 316f, 100f))
    private val frameLeft = ArtShape(ArtPart.FRAME_LEFT, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        300f, 84f, 316f, 100f, 316f, 424f, 300f, 440f))
    private val frameBottom = ArtShape(ArtPart.FRAME_BOTTOM, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        300f, 440f, 316f, 424f, 708f, 424f, 724f, 440f))
    private val frameRight = ArtShape(ArtPart.FRAME_RIGHT, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        724f, 84f, 724f, 440f, 708f, 424f, 708f, 100f))
    private val well = ArtShape(ArtPart.WELL, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        316f, 100f, 708f, 100f, 708f, 424f, 316f, 424f))
    private val slot0Body = ArtShape(ArtPart.SLOT, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        333.04f, 112f, 378.49f, 112f, 378.49f, 412f, 333.04f, 412f))
    private val slot0Top = ArtShape(ArtPart.SLOT_TOP, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        333.04f, 112f, 378.49f, 112f, 378.49f, 128f, 333.04f, 128f))
    private val slot1Body = ArtShape(ArtPart.SLOT, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        395.54f, 112f, 440.99f, 112f, 440.99f, 412f, 395.54f, 412f))
    private val slot1Top = ArtShape(ArtPart.SLOT_TOP, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        395.54f, 112f, 440.99f, 112f, 440.99f, 128f, 395.54f, 128f))
    private val slot2Body = ArtShape(ArtPart.SLOT, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        458.03f, 112f, 503.48f, 112f, 503.48f, 412f, 458.03f, 412f))
    private val slot2Top = ArtShape(ArtPart.SLOT_TOP, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        458.03f, 112f, 503.48f, 112f, 503.48f, 128f, 458.03f, 128f))
    private val slot3Body = ArtShape(ArtPart.SLOT, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        520.52f, 112f, 565.97f, 112f, 565.97f, 412f, 520.52f, 412f))
    private val slot3Top = ArtShape(ArtPart.SLOT_TOP, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        520.52f, 112f, 565.97f, 112f, 565.97f, 128f, 520.52f, 128f))
    private val slot4Body = ArtShape(ArtPart.SLOT, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        583.01f, 112f, 628.46f, 112f, 628.46f, 412f, 583.01f, 412f))
    private val slot4Top = ArtShape(ArtPart.SLOT_TOP, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        583.01f, 112f, 628.46f, 112f, 628.46f, 128f, 583.01f, 128f))
    private val slot5Body = ArtShape(ArtPart.SLOT, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        645.51f, 112f, 690.96f, 112f, 690.96f, 412f, 645.51f, 412f))
    private val slot5Top = ArtShape(ArtPart.SLOT_TOP, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        645.51f, 112f, 690.96f, 112f, 690.96f, 128f, 645.51f, 128f))
    private val ledgeEdge = ArtShape(ArtPart.LEDGE_EDGE, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        300f, 440f, 724f, 440f, 724f, 462f, 300f, 462f))
    private val ledgeFace = ArtShape(ArtPart.LEDGE_FACE, intArrayOf(
        0, 1, 1, 1, 3
    ), floatArrayOf(
        330f, 462f, 694f, 462f, 694f, 520f, 330f, 520f))
    private val ledgeEndLeft = ArtShape(ArtPart.LEDGE_END_LEFT, intArrayOf(
        0, 1, 1, 3
    ), floatArrayOf(
        300f, 462f, 330f, 462f, 330f, 520f))
    private val ledgeEndRight = ArtShape(ArtPart.LEDGE_END_RIGHT, intArrayOf(
        0, 1, 1, 3
    ), floatArrayOf(
        724f, 462f, 694f, 462f, 694f, 520f))

    /** Svg lines 19 to 30 in svg order: the twelve body shapes. */
    val bodyShapes: List<ArtShape> = listOf(
        triggerBody, triggerFace, triggerStripe, headBody, cordBody, lowerLeft,
        lowerRight, frameTop, frameLeft, frameBottom, frameRight, well,
    )

    /** The silhouette: exactly the trigger body, the head body and the cord body, in that order. */
    val silhouette: List<ArtShape> = bodyShapes.filter {
        it.part == ArtPart.TRIGGER_BODY || it.part == ArtPart.HEAD_BODY || it.part == ArtPart.CORD_BODY
    }

    /** The six slots, left to right: svg lines 31 and 32, 33 and 34, up to 41 and 42. */
    val slots: List<ArtSlot> = listOf(
        ArtSlot(slot0Body, slot0Top), ArtSlot(slot1Body, slot1Top), ArtSlot(slot2Body, slot2Top),
        ArtSlot(slot3Body, slot3Top), ArtSlot(slot4Body, slot4Top), ArtSlot(slot5Body, slot5Top),
    )

    /** Svg lines 43 to 46: the ledge. */
    val ledgeShapes: List<ArtShape> = listOf(ledgeEdge, ledgeFace, ledgeEndLeft, ledgeEndRight)

    /** Svg line 47: the BREAKER lettering. */
    val letters: ArtShape = ArtShape(ArtPart.LETTERS, TileLetters.ops, TileLetters.xy)

    /** Every shape in drawing order: the body, each slot with its strip, the ledge, then the letters. */
    val all: List<ArtShape> =
        bodyShapes + slots.flatMap { listOf(it.body, it.top) } + ledgeShapes + listOf(letters)
}
