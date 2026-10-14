package dev.breaker.dictation.wiring

/**
 * Shows the tile before a take begins, and remembers when the user closed it by dragging it onto
 * the close target.
 *
 * Two paths reach [onBegin]. While the flag holds, the tile was closed by the user: dictation stays
 * switched on (the armed service and its notification stay), a shake shows the tile and begins a
 * take, and a refused or failed show begins nothing and keeps the flag set. Opening the launcher or
 * switching dictation off and on clears the flag.
 *
 * While the flag does not hold, the tile may still be off screen without the user's close (the window
 * system detached it, or the tile hid itself), and the coordinator's record of the last show cannot
 * see that. Show is idempotent, so every begin shows the tile first: a shown tile changes nothing and
 * pushes nothing, a hidden one comes back, and a refused show begins no take and says the tile is
 * unavailable.
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
        if (!userClosed) {
            // The tile may be off screen without the user's close: the window system can detach it,
            // or the tile can hide itself, and the coordinator's tileShown cannot see that. Show is
            // idempotent, so it is called on every begin: a shown tile changes nothing and pushes
            // nothing, a hidden one comes back. A refused show begins no take, says the tile is
            // unavailable, and leaves tileShown alone so the next begin tries again.
            val result: TileShow = try {
                tile.show()
            } catch (e: Exception) {
                TileShow.FAILED
            }
            if (result != TileShow.SHOWN) {
                guarded { modelNotice.showTileUnavailable() }
                return true
            }
            return false
        }
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
