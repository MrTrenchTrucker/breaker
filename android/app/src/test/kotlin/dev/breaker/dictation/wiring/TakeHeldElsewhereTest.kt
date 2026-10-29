package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The held-elsewhere flag of a take port: the owned port answers whether a path other than its own kind holds
 * the take, and a port that does not override the flag answers false. A read that throws never reaches the
 * close; the close then cancels as it would with no other holder.
 */
internal class TakeHeldElsewhereTest {

    @Test
    fun `the tile take port reports its own take as not held elsewhere`() {
        val owner = TakeOwner()
        owner.begun(TakeOwnerKind.TILE)
        val port = OwnedTakePort(FakeTake(), owner, TakeOwnerKind.TILE)
        assertFalse("app: the tile take port must not report its own take as held elsewhere", port.heldElsewhere)
    }

    @Test
    fun `the tile take port reports a take held by the Home as held elsewhere`() {
        val owner = TakeOwner()
        owner.begun(TakeOwnerKind.HOME)
        val port = OwnedTakePort(FakeTake(), owner, TakeOwnerKind.TILE)
        assertTrue("app: the tile take port must report a take the Home holds as held elsewhere", port.heldElsewhere)
    }

    @Test
    fun `the tile take port reports no holder as not held elsewhere`() {
        val port = OwnedTakePort(FakeTake(), TakeOwner(), TakeOwnerKind.TILE)
        assertFalse("app: the tile take port must report no holder as not held elsewhere", port.heldElsewhere)
    }

    @Test
    fun `a take port that does not override the held-elsewhere flag reports false`() {
        val take: TakePort = FakeTake()
        assertFalse("app: the default held-elsewhere flag must be false", take.heldElsewhere)
    }

    @Test
    fun `a throwing held-elsewhere read is absorbed and the close still cancels and hides`() {
        val rig = TileRig(arm = false)
        val throwing: TakePort = object : TakePort by rig.take {
            override val heldElsewhere: Boolean
                get() = throw IllegalStateException("the holder read failed")
        }
        val coordinator = TileCoordinator(
            rig.tile, rig.main, rig.background, throwing, rig.model, rig.notice, rig.opener,
            rig.clipboard, {}, rig.history,
        )
        coordinator.onArmedChanged(true)
        coordinator.onBegin()
        rig.settle()
        coordinator.onCloseRequested()
        rig.settle()
        assertEquals("app: a close whose holder read throws must still cancel the take once", 1, rig.take.cancelCalls)
        assertFalse("app: a close whose holder read throws must still hide the tile", rig.tile.shown)
    }
}
