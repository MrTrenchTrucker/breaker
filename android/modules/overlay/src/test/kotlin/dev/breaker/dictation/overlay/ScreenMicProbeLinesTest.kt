package dev.breaker.dictation.overlay

import kotlin.math.floor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostic probe lines of the app-screen mic: where the time sits, which fields follow it, and that no line is
 * produced while no sink is set. The numbers come from the layout functions themselves, so the test pins the ORDER and
 * the POSITION of every field, not the layout.
 */
class ScreenMicProbeLinesTest {
    private val side = 300
    private val lines = mutableListOf<String>()

    @After
    fun clearSink() {
        ScreenMicProbeKit.logSink(null)
    }

    private fun collect() {
        lines.clear()
        ScreenMicProbeKit.logSink { lines.add(it) }
    }

    private fun fields(line: String): List<String> = line.split(":")

    @Test
    fun `a touch down line carries its time in the second field and the touch fields after it`() {
        collect()
        ScreenMicProbeKit.downLog(1234L, TileShape.RECORDING, TileState.RECORDING, 10.5f, 20.5f, side)
        val f = fields(lines.single())
        assertEquals("1", f[0])
        assertEquals("1234", f[1])
        assertEquals(TileShape.RECORDING.ordinal.toString(), f[2])
        assertEquals(TileState.RECORDING.ordinal.toString(), f[3])
        assertEquals(TileLayout.zoneAt(10, 20, TileShape.RECORDING, side).ordinal.toString(), f[5])
        assertEquals(10.0f.toString(), f[6])
        assertEquals(20.0f.toString(), f[7])
        assertEquals(8, f.size)
    }

    @Test
    fun `the action field of a touch line is what the tap on that point means`() {
        collect()
        val cancelPoint = TileLayout.cancelCell(side).left + 5f
        val sendPoint = TileLayout.sendCell(side).left + 5f
        val onCancel = MicTapZone.resolve(TileState.RECORDING, TileShape.RECORDING, cancelPoint, 20f, side)
        val onSend = MicTapZone.resolve(TileState.RECORDING, TileShape.RECORDING, sendPoint, 20f, side)
        assertTrue("overlay: control: the two points must mean different, non-zero actions", onCancel != onSend && onCancel.ordinal != 0 && onSend.ordinal != 0)
        ScreenMicProbeKit.downLog(1L, TileShape.RECORDING, TileState.RECORDING, cancelPoint, 20f, side)
        ScreenMicProbeKit.downLog(2L, TileShape.RECORDING, TileState.RECORDING, sendPoint, 20f, side)
        ScreenMicProbeKit.upLog(3L, TileShape.RECORDING, TileState.RECORDING, sendPoint, 20f, side)
        assertEquals(onCancel.ordinal.toString(), fields(lines[0])[4])
        assertEquals(onSend.ordinal.toString(), fields(lines[1])[4])
        assertEquals(onSend.ordinal.toString(), fields(lines[2])[4])
    }

    @Test
    fun `a touch up line has the same fields as a touch down line under its own tag`() {
        collect()
        ScreenMicProbeKit.downLog(1L, TileShape.RECORDING, TileState.RECORDING, 5f, 6f, side)
        ScreenMicProbeKit.upLog(2L, TileShape.RECORDING, TileState.RECORDING, 5f, 6f, side)
        val down = fields(lines[0])
        val up = fields(lines[1])
        assertEquals("5", up[0])
        assertEquals("2", up[1])
        assertEquals(down.drop(2), up.drop(2))
    }

    @Test
    fun `a touch cancel line holds the time then the shape and state`() {
        collect()
        ScreenMicProbeKit.cancelLog(99L, TileShape.RECORDING, TileState.RECORDING)
        assertEquals(
            "6:99:${TileShape.RECORDING.ordinal}:${TileState.RECORDING.ordinal}",
            lines.single(),
        )
    }

    @Test
    fun `the touch map line holds the screen point, the touch point, the zone, then the four rectangles in order`() {
        collect()
        ScreenMicProbeKit.touchMapLog(777L, intArrayOf(11, 22), 33.7f, 44.2f, side)
        val f = fields(lines.single())
        val cancelDrawn = ControlLayout.cancel(side)
        val sendDrawn = ControlLayout.send(side)
        val cancelCell = TileLayout.cancelCell(side)
        val sendCell = TileLayout.sendCell(side)
        val expected = listOf(
            "7", "777", "11", "22", "33", "44",
            TileLayout.zoneAt(33, 44, TileShape.RECORDING, side).ordinal.toString(),
            floor(cancelDrawn.left).toInt().toString(), floor(cancelDrawn.top).toInt().toString(),
            floor(cancelDrawn.right).toInt().toString(), floor(cancelDrawn.bottom).toInt().toString(),
            floor(sendDrawn.left).toInt().toString(), floor(sendDrawn.top).toInt().toString(),
            floor(sendDrawn.right).toInt().toString(), floor(sendDrawn.bottom).toInt().toString(),
            cancelCell.left.toString(), cancelCell.top.toString(), cancelCell.right.toString(), cancelCell.bottom.toString(),
            sendCell.left.toString(), sendCell.top.toString(), sendCell.right.toString(), sendCell.bottom.toString(),
        )
        assertEquals(expected, f)
    }

    @Test
    fun `with no sink set no probe line is produced and nothing throws`() {
        collect()
        ScreenMicProbeKit.logSink(null)
        ScreenMicProbeKit.downLog(1L, TileShape.RECORDING, TileState.RECORDING, 1f, 2f, side)
        ScreenMicProbeKit.upLog(2L, TileShape.RECORDING, TileState.RECORDING, 1f, 2f, side)
        ScreenMicProbeKit.cancelLog(3L, TileShape.RECORDING, TileState.RECORDING)
        ScreenMicProbeKit.touchMapLog(4L, intArrayOf(0, 0), 1f, 2f, side)
        assertTrue(lines.isEmpty())
    }
}
