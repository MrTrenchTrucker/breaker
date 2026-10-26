package dev.breaker.dictation.overlay

/**
 * The Home mic's halo shape and the selector that draws it.
 *
 * This file is **temporary**: it exists so the debug-only shape switch in the app module's `src/debug`
 * source set can select a shape, and it is deleted together with that switch once a shape is picked.
 * Nothing in this file may import `android.` — the module keeps its platform imports confined to its
 * adapter files, which is what makes this logic testable on a plain JVM.
 */

/**
 * The Home mic's halo shape. V1 is the shipped shape: the approved reaches of [GlowSpec.SCREEN].
 * V2 and V3 are visual experiments, drawn only when the app's debug source set selects them.
 */
public enum class GlowShape { V1, V2, V3 }

/**
 * The Home mic's halo shape selector. [screen] is V1 unless the app's debug source set sets another
 * value; the release build holds only V1, and the app's release gate pins that no release source
 * assigns [screen]. A `public` object is required because the debug source set lives in the app
 * module, which is a separate Gradle module and cannot see an `internal` declaration.
 */
public object GlowShapes {

    /** The shape the Home mic's halo draws. V1 unless the app's debug source set sets another. */
    @JvmField
    var screen: GlowShape = GlowShape.V1

    /** The halo reach [shape] draws, in half-cell widths. */
    internal fun reachFor(shape: GlowShape): HaloReach = when (shape) {
        GlowShape.V1 -> GlowSpec.SCREEN
        GlowShape.V2 -> HaloReach(sideways = 2.33f, downward = 1.40f, upward = 1.40f, kind = GlowKind.SCREEN)
        GlowShape.V3 -> HaloReach(sideways = 1.00f, downward = 1.00f, upward = 1.00f, kind = GlowKind.SCREEN)
    }
}
