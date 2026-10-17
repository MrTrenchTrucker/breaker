package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens

/**
 * What the app has told the tile to show: the state, the sound level, the notice and the description.
 *
 * It holds the values and the rules between them, and builds the [TileFace] the window draws. It never
 * changes the state by itself; only [setState] does. It has no window, no clock and no platform class.
 *
 * **Level.** The level is kept from 0.0 to 1.0 (a value that is not a number is 0) and only counts while the
 * state is recording; it goes back to 0 when the state enters or leaves recording, so a level pushed
 * before recording starts is never shown.
 *
 * **Notice.** A notice is cleaned by [NoticeRules] when it is stored. It is ignored while recording, and any
 * change of state removes it.
 */
internal class TileModel {

    /** The state the app last pushed. Idle until the app pushes another. */
    var state: TileState = TileState.IDLE
        private set

    /** The notice to show, already cleaned, or null for none. */
    var notice: String? = null
        private set

    /** The description for accessibility, or null for none. */
    var description: String? = null
        private set

    private var level: Float = 0f

    /** The shape the window has for the current state and notice. */
    val shape: TileShape
        get() = shapeOf(state, notice)

    /** Store [next]. A different state removes the notice, and entering or leaving recording sets the level back to 0. */
    fun setState(next: TileState) {
        if (next == state) return
        if (state == TileState.RECORDING || next == TileState.RECORDING) level = 0f
        state = next
        notice = null
    }

    /** Store [value] kept from 0.0 to 1.0; a value that is not a number is stored as 0. */
    fun setLevel(value: Float) {
        level = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
    }

    /** Store [text] as the notice after cleaning it; ignored while recording. Blank text leaves no notice. */
    fun showNotice(text: String) {
        if (state == TileState.RECORDING) return
        notice = NoticeRules.normalise(text)
    }

    /** Remove the notice. */
    fun clearNotice() {
        notice = null
    }

    /** Store [text] as the description, or none when null. */
    fun setDescription(text: String?) {
        description = text
    }

    /** What the window draws now, in the colours of [theme]. No segment is lit unless the state is recording. */
    fun face(theme: ThemeMode): TileFace = TileFace(
        state = state,
        shape = shape,
        litSegments = if (state == TileState.RECORDING) LedMeter.lit(level) else 0,
        segments = LedMeter.SEGMENTS,
        look = TileStyle.look(state, TruckingTokens.palette(theme)),
        notice = notice,
        description = description,
    )
}
