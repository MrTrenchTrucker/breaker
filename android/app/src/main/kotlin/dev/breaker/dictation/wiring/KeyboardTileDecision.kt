package dev.breaker.dictation.wiring

/**
 * The decision for one keyboard reading. A sealed type so the coordinator can branch exactly on
 * show, hide, or do nothing without a string compare. No Android types here.
 */
sealed interface KeyboardDecision {
    /** The tile should be put back on screen. */
    object Show : KeyboardDecision

    /** The tile should be taken off screen. */
    object Hide : KeyboardDecision

    /** The window state should not change for this reading. */
    object NoOp : KeyboardDecision
}

/** The debounce window in milliseconds: a reading inside it gets NoOp from the pure function and from the session, and the coordinator holds the newest such reading and re-evaluates it once when the window ends. Named once so that every debounce check in the keyboard tile uses the same value. */
internal const val DEBOUNCE_MS: Long = 250

/**
 * The pure show/hide decision for the keyboard tile. It maps four session flags over two time stamps
 * to one of [KeyboardDecision.Show], [KeyboardDecision.Hide] or [KeyboardDecision.NoOp].
 *
 * The inputs are exactly six: [imeWindowPresent] (a keyboard window is open), [fieldFocused] (an
 * editable field is focused), [keyboardRaised] (this session raised a tile that was not showing
 * before it started) and [closedDuringSession] (the user closed the tile while this session was
 * active). [nowMs] is the current monotonic millisecond count and [lastChangeMs] is the count
 * at the last change of the inputs; only their difference is used, never as a calendar time.
 * The result is pure: no state beyond what it is given, so a test can pin every path by calling it.
 */
internal fun decision(
    imeWindowPresent: Boolean,
    fieldFocused: Boolean,
    keyboardRaised: Boolean,
    closedDuringSession: Boolean,
    nowMs: Long,
    lastChangeMs: Long,
): KeyboardDecision {
    // 1. A change inside the debounce window is swallowed; nothing changes yet.
    if (nowMs - lastChangeMs < DEBOUNCE_MS) return KeyboardDecision.NoOp
    // 2. A keyboard up with an editable focus shows the tile, unless the user closed it this session.
    if (imeWindowPresent && fieldFocused && !closedDuringSession) return KeyboardDecision.Show
    // 3. A keyboard up with a focus the user closed stays hidden for the rest of this session.
    if (imeWindowPresent && fieldFocused && closedDuringSession) return KeyboardDecision.NoOp
    // 4. A keyboard open with no editable focus neither shows nor hides the tile.
    if (imeWindowPresent && !fieldFocused) return KeyboardDecision.NoOp
    // 5. No keyboard window: restore to the pre-keyboard state — hide only if this session raised it.
    return if (!imeWindowPresent && keyboardRaised) KeyboardDecision.Hide else KeyboardDecision.NoOp
}
