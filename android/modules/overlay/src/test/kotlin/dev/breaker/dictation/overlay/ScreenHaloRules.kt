package dev.breaker.dictation.overlay

/**
 * The two approved constraints of the Home mic's halo, as pure functions from a rule input to a list of
 * violations: the upward reach is checked against the card bottom above the mic centre (the DRAW holds it
 * there, through [GlowSpec.upwardReachDp], and this rule checks that it was), and the halo stays inside
 * the page column (its sideways reach never exceeds half the column width), so the halo cannot leave the
 * window. Every rule is a pure function from an input to a list of problems, in the shape of
 * [ViewDrawingRules], and a construct the rule cannot read is a violation, never a pass.
 */
internal object ScreenHaloRules {

    /** The names of the two approved constraints, so a control names the one that fired. */
    const val UPWARD = "the upward reach is not held to the card bottom"
    const val COLUMN = "the halo is not inside the page column"

    /** One rule input: the cell in dp, the reach in half-cell widths, and the page column in dp. */
    internal data class Input(
        val cellSideDp: Float,
        val reach: HaloReach,
        val columnWidthDp: Float,
    )

    /** The upward reach that is DRAWN: the pre-cap reach held to the card bottom by [GlowSpec.upwardReachDp]. */
    fun upwardDp(input: Input): Float = GlowSpec.upwardReachDp(input.reach, input.cellSideDp)

    /**
     * The upward reach BEFORE the cap: the reach the halo would draw if [GlowSpec] did not hold it. This is
     * what [upwardProblems] checks, because [upwardDp] is capped by construction and can never pass the
     * card bottom, so comparing it against the cap would prove nothing.
     */
    fun upwardUncappedDp(input: Input): Float = input.reach.upward * input.cellSideDp / 2f

    /** The sideways reach in dp: one reach in half-cell widths times half the cell side. */
    fun sidewaysDp(input: Input): Float = input.reach.sideways * input.cellSideDp / 2f

    /** The column's half width in dp, the widest sideways reach that still stays inside the column. */
    fun halfColumnDp(input: Input): Float = input.columnWidthDp / 2f

    /** The upward reach against the card bottom: [UPWARD] when the uncapped reach passes the card's bottom edge. */
    fun upwardProblems(input: Input): List<String> =
        if (upwardUncappedDp(input) <= ArtGeometry.cardBottomDp(input.cellSideDp)) emptyList() else listOf(UPWARD)

    /** The sideways reach against the column: [COLUMN] when the halo's edge leaves the page column. */
    fun columnProblems(input: Input): List<String> =
        if (sidewaysDp(input) <= halfColumnDp(input)) emptyList() else listOf(COLUMN)

    /** Both constraints at once, for [inputs]: the first problem of each input, so a control can name what it broke. */
    fun violations(inputs: List<Input>): List<String> =
        inputs.flatMap { upwardProblems(it) + columnProblems(it) }
}
