package dev.breaker.dictation.overlay

/**
 * What a call to `FloatingTile.show()` came to, and so what the app should do next.
 *
 * `show()` never throws for the cases below: a missing permission and a refusal
 * by the window system are ordinary answers, not errors. Only [SHOWN] means the
 * tile is on screen after the call.
 */
enum class ShowResult {
    /** The tile was hidden and is now on screen, at its saved position. Nothing more to do. */
    SHOWN,

    /** The tile was already on screen. The call changed nothing and no second window was added. */
    ALREADY_SHOWN,

    /**
     * The app may not draw over other apps yet, so nothing was added and the tile is still hidden.
     *
     * The app should send the user to the system overlay-permission page and
     * call `show()` again once the user comes back.
     */
    PERMISSION_MISSING,

    /**
     * The permission is there, but the window system refused to add the window.
     * Nothing is on screen and the tile is still hidden. A later `show()` may try again.
     */
    FAILED,
}
