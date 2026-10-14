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
 * While the flag does not hold, every begin shows the tile first anyway. Show is idempotent: a shown
 * tile changes nothing and pushes nothing, a tile the overlay knows is hidden comes back, and a refused
 * show begins no take and says the tile is unavailable. A tile the window system removed without
 * telling the overlay is not caught here: the overlay still answers that it is shown, so a take can
 * begin with no tile on screen (a device check in the card).
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
            // Show is idempotent, so it is called on every begin: a shown tile changes nothing and
            // pushes nothing, a tile the overlay knows is hidden comes back. A refused show begins no
            // take, says the tile is unavailable, and leaves tileShown alone so the next begin tries
            // again. A tile the window system removed without telling the overlay still answers shown.
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
