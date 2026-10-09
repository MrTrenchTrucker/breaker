package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a tap means in each state on each zone: the whole table of 8 states by 5 zones, written out
 * one row to a cell, so a change to any single cell is seen.
 */
class TileRoutingTest {

    private val none = TileAction.NONE

    private val table: List<Triple<TileState, TileZone, TileAction>> = listOf(
        Triple(TileState.IDLE, TileZone.MIC, TileAction.TAP),
        Triple(TileState.IDLE, TileZone.CANCEL, none),
        Triple(TileState.IDLE, TileZone.SEND, none),
        Triple(TileState.IDLE, TileZone.STRIP, none),
        Triple(TileState.IDLE, TileZone.NONE, none),

        Triple(TileState.ARMED, TileZone.MIC, TileAction.BEGIN),
        Triple(TileState.ARMED, TileZone.CANCEL, none),
        Triple(TileState.ARMED, TileZone.SEND, none),
        Triple(TileState.ARMED, TileZone.STRIP, none),
        Triple(TileState.ARMED, TileZone.NONE, none),

        Triple(TileState.RECORDING, TileZone.MIC, TileAction.SEND),
        Triple(TileState.RECORDING, TileZone.CANCEL, TileAction.CANCEL),
        Triple(TileState.RECORDING, TileZone.SEND, TileAction.SEND),
        Triple(TileState.RECORDING, TileZone.STRIP, none),
        Triple(TileState.RECORDING, TileZone.NONE, none),

        Triple(TileState.SENDING, TileZone.MIC, none),
        Triple(TileState.SENDING, TileZone.CANCEL, none),
        Triple(TileState.SENDING, TileZone.SEND, none),
        Triple(TileState.SENDING, TileZone.STRIP, none),
        Triple(TileState.SENDING, TileZone.NONE, none),

        Triple(TileState.FAILED, TileZone.MIC, TileAction.TAP),
        Triple(TileState.FAILED, TileZone.CANCEL, none),
        Triple(TileState.FAILED, TileZone.SEND, none),
        Triple(TileState.FAILED, TileZone.STRIP, none),
        Triple(TileState.FAILED, TileZone.NONE, none),

        Triple(TileState.SENT, TileZone.MIC, TileAction.TAP),
        Triple(TileState.SENT, TileZone.CANCEL, none),
        Triple(TileState.SENT, TileZone.SEND, none),
        Triple(TileState.SENT, TileZone.STRIP, none),
        Triple(TileState.SENT, TileZone.NONE, none),

        Triple(TileState.SENT_LOCAL, TileZone.MIC, TileAction.TAP),
        Triple(TileState.SENT_LOCAL, TileZone.CANCEL, none),
        Triple(TileState.SENT_LOCAL, TileZone.SEND, none),
        Triple(TileState.SENT_LOCAL, TileZone.STRIP, none),
        Triple(TileState.SENT_LOCAL, TileZone.NONE, none),
        Triple(TileState.MIC_BUSY, TileZone.MIC, TileAction.TAP),
        Triple(TileState.MIC_BUSY, TileZone.CANCEL, none),
        Triple(TileState.MIC_BUSY, TileZone.SEND, none),
        Triple(TileState.MIC_BUSY, TileZone.STRIP, none),
        Triple(TileState.MIC_BUSY, TileZone.NONE, none),
    )

    /** A failure means a state, a zone or an action was added, removed or reordered, so the table below no longer covers everything. */
    @Test
    fun `the enums are the ones the table covers`() {
        assertEquals(
            "overlay: the tile states expected in this order",
            listOf(TileState.IDLE, TileState.ARMED, TileState.RECORDING, TileState.SENDING, TileState.FAILED, TileState.SENT, TileState.SENT_LOCAL, TileState.MIC_BUSY),
            TileState.values().toList(),
        )
        assertEquals(
            "overlay: the zones expected in this order",
            listOf(TileZone.MIC, TileZone.CANCEL, TileZone.SEND, TileZone.STRIP, TileZone.NONE),
            TileZone.values().toList(),
        )
        assertEquals(
            "overlay: the actions expected in this order",
            listOf(TileAction.NONE, TileAction.TAP, TileAction.BEGIN, TileAction.CANCEL, TileAction.SEND),
            TileAction.values().toList(),
        )
    }

    /** A failure means the table does not hold exactly one row for each of the 40 state and zone pairs. */
    @Test
    fun `the table has one row for each of the 40 pairs`() {
        assertEquals("overlay: the table expected 40 rows", 40, table.size)
        val pairs = table.map { it.first to it.second }.toSet()
        assertEquals("overlay: the table expected 40 different state and zone pairs", 40, pairs.size)
        for (state in TileState.values()) for (zone in TileZone.values()) {
            assertEquals("overlay: the table expected a row for $state on $zone", true, (state to zone) in pairs)
        }
    }

    /** A failure means a tap in some state on some zone is routed to the wrong action; the message names the pair. */
    @Test
    fun `every one of the 35 cells routes as written`() {
        table.forEach { (state, zone, expected) ->
            assertEquals("overlay: a tap on $zone while $state expected $expected", expected, TileRouting.action(state, zone))
        }
    }
}
