package dev.breaker.dictation.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

internal class ProbeSwitchTest {

    /** A switch over fakes that record each call. */
    private class Recorder {
        val holder: MutableList<Boolean> = ArrayList()
        val tile: MutableList<((Int, Boolean) -> Unit)?> = ArrayList()
        val sink: MutableList<Pair<Int, Boolean>> = ArrayList()
        var clears: Int = 0
        val switch: ProbeSwitch = ProbeSwitch(
            setHolder = { on -> holder.add(on) },
            setTileProbe = { listener -> tile.add(listener) },
            onInset = { bottom, visible -> sink.add(Pair(bottom, visible)) },
            clearLog = { clears = clears + 1 },
        )
    }

    @Test
    fun `off turns the holder off and takes the tile probe away`() {
        val recorder = Recorder()
        recorder.switch.apply(KeyboardSignalSetting.OFF)
        assertEquals("probe: OFF must turn the holder off once", listOf(false), recorder.holder)
        assertEquals("probe: OFF must take the tile probe away once", listOf<((Int, Boolean) -> Unit)?>(null), recorder.tile)
    }

    @Test
    fun `off never hands the tile a listener`() {
        val recorder = Recorder()
        recorder.switch.apply(KeyboardSignalSetting.OFF)
        assertEquals("probe: OFF must hand the tile no listener", 0, recorder.tile.count { it != null })
    }

    @Test
    fun `each other value turns the holder on and gives the tile a listener`() {
        for (setting in listOf(KeyboardSignalSetting.FIELD_FOCUS, KeyboardSignalSetting.KEYBOARD_VISIBLE)) {
            val recorder = Recorder()
            recorder.switch.apply(setting)
            assertEquals("probe: $setting must turn the holder on", listOf(true), recorder.holder)
            assertNotNull("probe: $setting must give the tile a listener", recorder.tile.single())
        }
    }

    @Test
    fun `the listener forwards the pair to the sink`() {
        val recorder = Recorder()
        recorder.switch.apply(KeyboardSignalSetting.FIELD_FOCUS)
        val listener = recorder.tile.single()
        assertNotNull("probe: the tile must get a listener", listener)
        listener?.invoke(120, true)
        assertEquals("probe: the listener must forward the pair to the sink", listOf(Pair(120, true)), recorder.sink)
    }

    @Test
    fun `switching back to off after another value takes the listener away again`() {
        val recorder = Recorder()
        recorder.switch.apply(KeyboardSignalSetting.FIELD_FOCUS)
        recorder.switch.apply(KeyboardSignalSetting.OFF)
        assertNull("probe: switching back to OFF must take the listener away", recorder.tile.last())
        assertEquals("probe: switching back to OFF must turn the holder off", false, recorder.holder.last())
    }

    @Test
    fun `off wants no activity inset listener`() {
        assertEquals("probe: OFF must want no activity inset listener", false, activityInsetsWanted(KeyboardSignalSetting.OFF))
    }

    @Test
    fun `each other value wants the activity inset listener`() {
        for (setting in listOf(KeyboardSignalSetting.FIELD_FOCUS, KeyboardSignalSetting.KEYBOARD_VISIBLE)) {
            assertEquals("probe: $setting must want the activity inset listener", true, activityInsetsWanted(setting))
        }
    }

    @Test
    fun `a start with off needs no full apply`() {
        assertEquals("probe: a start with OFF must need no full apply", false, startNeedsFullApply(KeyboardSignalSetting.OFF))
    }

    @Test
    fun `a start with another value needs the full apply`() {
        for (setting in listOf(KeyboardSignalSetting.FIELD_FOCUS, KeyboardSignalSetting.KEYBOARD_VISIBLE)) {
            assertEquals("probe: a start with $setting must need the full apply", true, startNeedsFullApply(setting))
        }
    }

    @Test
    fun `off clears the inset log`() {
        val recorder = Recorder()
        recorder.switch.apply(KeyboardSignalSetting.OFF)
        assertEquals("probe: OFF must clear the inset log once", 1, recorder.clears)
    }

    @Test
    fun `another value does not clear the inset log`() {
        for (setting in listOf(KeyboardSignalSetting.FIELD_FOCUS, KeyboardSignalSetting.KEYBOARD_VISIBLE)) {
            val recorder = Recorder()
            recorder.switch.apply(setting)
            assertEquals("probe: $setting must not clear the inset log", 0, recorder.clears)
        }
    }
}
