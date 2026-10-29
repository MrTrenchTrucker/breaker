package dev.breaker.dictation.wiring

/**
 * The keyboard-driven session state the coordinator tracks alongside its take.
 *
 * One instance is held by the coordinator; its [onKeyboardReading] entry updates it and returns the decision to act on, so this file stays off the main-thread dispatch of a take. It holds:
 * whether a keyboard session is active ([keyboardSessionActive]), whether this session has raised a tile it
 * had not shown before ([keyboardRaised]), that the user closed the tile while the session was live
 * ([closedDuringSession]) — reset at the end of a session — and when the last acting pair change happened
 * ([lastChangeMs], the anchor). The previous (ime, focus) pair is tracked for the gate: initial state is null
 * / anchor 0. It names no Android type, so it reads from a plain JVM test.
 */
internal class KeyboardTileSession {

    /** True while one keyboard session has produced at least one reading that acted on a change. */
    internal var keyboardSessionActive: Boolean = false
        private set

    /** True once this session has raised a tile it had not shown before; reset when the session ends. */
    internal var keyboardRaised: Boolean = false
        private set

    /** The user closed the tile while this session was active; reset to false at the end of a session. */
    internal var closedDuringSession: Boolean = false
        private set

    private var lastChangeMs: Long = 0L
    private var previousPair: Pair<Boolean, Boolean>? = null

    /**
     * Reads one keyboard reading on the main thread and returns the decision to act on. A change inside the
     * debounce window is swallowed with no state updated, an unchanged pair does nothing at all, the first
     * reading always acts without a debounce, and any other acting change records itself so a later change
     * measures against this anchor. Pure of its own side effects: it never shows or hides the tile — the
     * coordinator does that through its own path.
     */
    fun onKeyboardReading(ime: Boolean, focus: Boolean, timeMs: Long): KeyboardDecision {
        val pair = ime to focus
        // An unchanged pair does nothing at all — no state update, NoOp returned — before the debounce check.
        if (previousPair == pair) return KeyboardDecision.NoOp
        // A change inside the debounce window is swallowed; nothing updates yet.
        if (timeMs - lastChangeMs < DEBOUNCE_MS) return KeyboardDecision.NoOp
        val decision = decision(ime, focus, keyboardRaised, closedDuringSession, timeMs, lastChangeMs)
        // Record the pair and anchor so a later change measures against this moment.
        previousPair = pair
        lastChangeMs = timeMs
        // A keyboard reading arrived while none was active starts this session.
        if (!keyboardSessionActive && ime) keyboardSessionActive = true
        // The session ends on every acted reading with the ime gone — both a Hide and a NoOp restore reset its flags together.
        if (!ime) {
            keyboardSessionActive = false
            keyboardRaised = false
            closedDuringSession = false
        }
        return decision
    }

    /** Report one SHOW's outcome from the coordinator so this can record that a raised tile was raised. */
    fun reportShown(wasShowing: Boolean, shown: Boolean) {
        if (shown && !wasShowing) keyboardRaised = true
    }

    /** The current previous pair — read by a gate test to pin first-act semantics without a coordinator. */
    fun lastPair(): Pair<Boolean, Boolean>? = previousPair

    /** The anchor time of the last acting change — exposed so tests can measure against the window. */
    fun anchorMs(): Long = lastChangeMs

    /** True when [timeMs] falls inside the debounce window that opened at the last acting change. */
    fun inWindow(timeMs: Long): Boolean = timeMs - lastChangeMs < DEBOUNCE_MS

    /** Keeps the raised mark while a keyboard-gone hide is held for a take in flight, so the hide can still apply. */
    fun keepRaised() {
        keyboardRaised = true
    }

    /** Clears the raised mark once a keyboard hide has taken the tile down. */
    fun releaseRaised() {
        keyboardRaised = false
    }

    /** Remember a user close only while this session is live; clears it when the session ends (called on the coordinator's close path). */
    fun setCloseWhileActive(active: Boolean) {
        closedDuringSession = active
    }

    /** A user close: remembers a close while this session is live and releases the raised mark, so a later keyboard-gone reading does not take the closed tile down. */
    fun onCloseRequested() {
        setCloseWhileActive(keyboardSessionActive)
        keyboardRaised = false
    }
}
