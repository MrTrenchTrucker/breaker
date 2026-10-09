package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.usecase.SendResult
import dev.breaker.dictation.overlay.TileState

/** The route extra value that makes the launcher open on the speech model screen. */
const val ROUTE_MODEL_VALUE: String = "model"

/** What an attempt to put the floating tile on screen came to. */
enum class TileShow {
    /** The tile is up (it also stands for a tile that was already up). */
    SHOWN,

    /** The user has not allowed the tile to draw over other apps. */
    NO_PERMISSION,

    /** The window system refused the tile. */
    FAILED,
}

/** The floating tile as the coordinator sees it. Every member is called on the main thread only. */
interface TilePort {
    /** Puts the tile on screen. */
    fun show(): TileShow

    /** Takes the tile off screen. Calls no callback. */
    fun hide()

    /** Shows [state]. The tile never changes its own state. */
    fun setState(state: TileState)

    /** Shows a short plain sentence beside the tile. */
    fun showNotice(text: String)

    /** Removes the sentence. */
    fun clearNotice()
}

/** Runs a block on the main thread, in order. A call from another thread never runs the block inline. */
interface MainPost {
    fun post(block: () -> Unit)
}

/** Runs blocks one at a time, in the order they were submitted, off the main thread. */
interface Background {
    fun submit(block: () -> Unit)
}

/** One dictation as the coordinator drives it. The runner is the real implementation. */
interface TakePort {
    /** Where the dictation is. */
    val sessionState: DictationState

    /** Starts listening. */
    fun begin(): BeginResult

    /** Stops listening and turns the audio into text. Blocks. */
    fun finish(): FinishResult

    /** Puts the text where the user is typing; null when there is nothing to send. */
    fun send(): SendResult?

    /** Drops the dictation. */
    fun cancel()
}

/** Whether the speech model the settings select is installed and unpacked. */
interface ModelReady {
    fun isReady(): Boolean
}

/** The notifications that tell the user what the tile cannot. */
interface ModelNotice {
    /** The speech model is missing; a tap on the notification opens the launcher on the model screen. */
    fun showMissing()

    /** The tile could not be shown; a tap on the notification opens the launcher. */
    fun showTileUnavailable()

    /** Removes both notifications. */
    fun clear()
}

/** Opens Breaker's own launcher screen. */
interface Opener {
    /** [route] is the route extra value to carry, or null for the plain launcher. */
    fun openLauncher(route: String?)
}
