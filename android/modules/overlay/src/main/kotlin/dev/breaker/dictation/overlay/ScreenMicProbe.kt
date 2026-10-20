package dev.breaker.dictation.overlay

/**
 * The debug probe's handle on the Home mic: the receiver in the app's debug source set reaches the mic only
 * through this interface and never through the view's internals. Debug-probe use only; it carries no behaviour of
 * its own.
 */
public interface ScreenMicProbe {
    /** Push [state] to the mic, on the main thread, the same way the app pushes state. True when it pushed. */
    fun probeSetState(state: TileState): Boolean

    /** Route each log line of the mic's touch path to [log]; pass null to stop logging. */
    fun probeTouchLog(log: ((String) -> Unit)?)
}
