package dev.breaker.dictation.wiring

/**
 * Remembers whether the user closed the tile by dragging it onto the close target.
 *
 * Dictation stays switched on (the armed service and its notification stay). While the tile is
 * closed this way, a shake shows the tile and begins a take; if the show is refused or fails, no
 * take begins and the flag stays set. Opening the launcher or switching dictation off and on
 * clears the flag.
 */
internal class TileShowGuard(
    private val tile: TilePort,
    private val modelNotice: ModelNotice,
) {
    private var userClosed: Boolean = false

    /** True while the tile is closed this way and a shake has not shown it again. */
    val isClosed: Boolean
        get() = userClosed

    fun onCloseRequested() {
        userClosed = true
        guarded { tile.hide() }
    }

    fun clear() {
        userClosed = false
    }

    fun onBegin(shown: (TileShow) -> Unit): Boolean {
        // A user-closed tile may be off screen, so the on-screen check is waived while the flag holds;
        // the show below still has to succeed before a take begins.
        if (!userClosed) return false
        userClosed = false
        val result: TileShow = try {
            tile.show()
        } catch (e: Exception) {
            TileShow.FAILED
        }
        shown(result)
        if (result != TileShow.SHOWN) {
            // A refused or failed show keeps the flag set and begins nothing.
            userClosed = true
            guarded { modelNotice.showTileUnavailable() }
            return true
        }
        return false
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // A tile or notification that fails must not stop the take or reach the caller.
        }
    }
}
