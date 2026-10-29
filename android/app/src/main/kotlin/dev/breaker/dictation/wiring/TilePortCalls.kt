package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState

/**
 * The port calls of the tile coordinator that may throw. Each one answers a fixed value when its port throws,
 * so the exception never reaches the caller. Plain Kotlin: it names no Android type.
 */
internal class TilePortCalls(
    private val take: TakePort,
    private val modelReady: ModelReady,
    private val background: Background,
) {
    fun sessionNow(): DictationState =
        try {
            take.sessionState
        } catch (e: Exception) {
            DictationState.IDLE
        }


    fun isModelReady(): Boolean =
        try {
            modelReady.isReady()
        } catch (e: Exception) {
            false
        }


    /** True when [block] was handed to the background; a background that refuses is not an exception here. */
    fun submit(block: () -> Unit): Boolean =
        try {
            background.submit(block)
            true
        } catch (e: Exception) {
            false
        }

    /** The take port's held-elsewhere flag; a port that throws answers false, so the close still cancels. */
    fun heldElsewhereNow(): Boolean =
        try {
            take.heldElsewhere
        } catch (e: Exception) {
            false
        }

}
