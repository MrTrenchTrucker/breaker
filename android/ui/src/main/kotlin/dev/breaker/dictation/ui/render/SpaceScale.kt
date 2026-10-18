package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.screen.Space
import dev.breaker.shared.tokens.TruckingMetrics

/*
 * The spacing scale, read from the design tokens by name.
 *
 * A screen names a size such as Space.M. This function is the one place a name
 * becomes a distance, and it reads each value from the metrics by its own name,
 * so a value changed in the tokens reaches every box without an edit here.
 */

/**
 * The distance in dp that [space] names in [metrics]. Space.NONE is no distance.
 */
internal fun spaceDp(metrics: TruckingMetrics, space: Space): Int = when (space) {
    Space.NONE -> 0
    Space.XS -> metrics.spaceXsDp
    Space.S -> metrics.spaceSDp
    Space.M -> metrics.spaceMDp
    Space.L -> metrics.spaceLDp
    Space.XL -> metrics.spaceXlDp
}
