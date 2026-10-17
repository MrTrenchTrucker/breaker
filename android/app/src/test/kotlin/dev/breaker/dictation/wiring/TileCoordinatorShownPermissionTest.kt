package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The show before a begin with no user close: a show that answers NO_PERMISSION (the overlay
 * permission was taken away) is refused like a failed one. No take begins, the tile is said to be
 * unavailable once, and the begin does not throw. The rig and the fakes are the ones in TileFakes.kt.
 */
internal class TileCoordinatorShownPermissionTest {
    @Test
    fun `a begin whose show answers no permission without a close begins nothing and says so`() {
        val rig = TileRig()
        rig.tile.showResult = TileShow.NO_PERMISSION
        rig.coordinator.onBegin()  // must not throw
        rig.settle()
        assertEquals("app: a begin whose show answers no permission must not reach the take", 0, rig.take.beginCalls)
        assertEquals("app: a show without permission must say the tile is unavailable once", 1, rig.notice.calls.count { it == "tileUnavailable" })
    }
}
