package dev.breaker.dictation.wiring

/** Holds a microphone take that arrived while a begin was pending, and replays it on settle. Plain: no android names, no logging, threads or clocks. */
internal class MicTakeHold(private val onReplay: () -> Unit) {
    private var held: Boolean = false

    fun onMicTakenWhilePending() { held = true }

    fun replayIfHeld(): Boolean {
        if (!held) return false
        held = false
        onReplay()
        return true
    }

    fun clear() { held = false }

    fun isHeld(): Boolean = held
}
