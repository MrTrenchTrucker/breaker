package dev.breaker.dictation.probe

import dev.breaker.dictation.overlay.GlowShape
import dev.breaker.dictation.overlay.GlowShapes

/** The glow shape a stored word names; a missing, empty or unknown word selects nothing. */
fun glowShapeFromWord(word: String?): GlowShape? = GlowShape.entries.firstOrNull { it.name == word }

/** Applies [shape] to the Home mic's halo when it is not null; returns the shape now selected. */
fun applyGlowShape(shape: GlowShape?): GlowShape {
    if (shape != null) GlowShapes.screen = shape
    return GlowShapes.screen
}
