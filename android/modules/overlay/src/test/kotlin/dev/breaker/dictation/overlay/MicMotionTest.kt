package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home mic's one motion decision, as a pure function. Each test pins a precedence rule rather than a happy
 * path, so a function that reorders two arms, drops a condition, or names the wrong state fails here.
 *
 * The states are the Home mic's own [TileState] names. A tile that is switched on and not recording is
 * [TileState.ARMED]; [TileState.IDLE] is the switched-off tile, which is why the breathing motion belongs to
 * ARMED and not to IDLE.
 *
 * Every input is passed in, including whether the system lets animations run, so the function under test reads
 * no platform type, no clock and no file: these tests call it directly and need no Android.
 */
class MicMotionTest {

    /** The four states the Home mic reaches through its own face that carry no motion of their own. */
    private val quietStates = listOf(
        TileState.MIC_BUSY,
        TileState.SENDING,
        TileState.FAILED,
        TileState.SENT,
        TileState.SENT_LOCAL,
    )

    /** A failure here means a switched-off mic is not dim: OFF is the only state that dims, and it beats every other input. */
    @Test
    fun `the switched off mic glows dim and still even while it looks armed`() {
        for (state in TileState.values()) {
            assertEquals(
                "overlay: a switched off $state mic must be dim and still",
                MicMotion.DIM_STEADY,
                micMotion(state, switchOn = false, ready = true, visible = true, animationsOn = true),
            )
        }
        assertEquals(
            "overlay: the off mic beats a recording state",
            MicMotion.DIM_STEADY,
            micMotion(TileState.RECORDING, switchOn = false, ready = true, visible = true, animationsOn = true),
        )
        assertEquals(
            "overlay: the off mic beats a breathing state",
            MicMotion.DIM_STEADY,
            micMotion(TileState.ARMED, switchOn = false, ready = true, visible = true, animationsOn = true),
        )
    }

    /** A failure here means motion still runs on a Home screen that is not visible, so the glow would burn power for nothing. */
    @Test
    fun `a mic on a Home screen that is not visible moves not at all`() {
        for (state in TileState.values()) {
            assertEquals(
                "overlay: an invisible $state mic must hold still",
                MicMotion.STEADY,
                micMotion(state, switchOn = true, ready = true, visible = false, animationsOn = true),
            )
        }
        assertEquals(
            "overlay: the invisible recording mic stops following the level",
            MicMotion.STEADY,
            micMotion(TileState.RECORDING, switchOn = true, ready = true, visible = false, animationsOn = true),
        )
        assertEquals(
            "overlay: the invisible armed and ready mic stops breathing",
            MicMotion.STEADY,
            micMotion(TileState.ARMED, switchOn = true, ready = true, visible = false, animationsOn = true),
        )
    }

    /** A failure here means motion still runs with the system animator scale at zero, where the user asked for no animation at all. */
    @Test
    fun `a mic with the system animations turned off moves not at all`() {
        for (state in TileState.values()) {
            assertEquals(
                "overlay: a $state mic with animations off must hold still",
                MicMotion.STEADY,
                micMotion(state, switchOn = true, ready = true, visible = true, animationsOn = false),
            )
        }
        assertEquals(
            "overlay: the recording mic with animations off stops following the level",
            MicMotion.STEADY,
            micMotion(TileState.RECORDING, switchOn = true, ready = true, visible = true, animationsOn = false),
        )
        assertEquals(
            "overlay: the armed and ready mic with animations off stops breathing",
            MicMotion.STEADY,
            micMotion(TileState.ARMED, switchOn = true, ready = true, visible = true, animationsOn = false),
        )
    }

    /** A failure here means the level pulse runs in a state that is not recording, so a still or a finished mic would follow the level. */
    @Test
    fun `only a recording mic follows the live level`() {
        assertEquals(
            "overlay: the recording mic must follow the level",
            MicMotion.LEVEL_PULSE,
            micMotion(TileState.RECORDING, switchOn = true, ready = true, visible = true, animationsOn = true),
        )
        for (state in TileState.values().filter { it != TileState.RECORDING }) {
            assertNotEquals(
                "overlay: a $state mic must not follow the level",
                MicMotion.LEVEL_PULSE,
                micMotion(state, switchOn = true, ready = true, visible = true, animationsOn = true),
            )
        }
        assertNotEquals(
            "overlay: a recording mic with animations off must not follow the level",
            MicMotion.LEVEL_PULSE,
            micMotion(TileState.RECORDING, switchOn = true, ready = true, visible = true, animationsOn = false),
        )
    }

    /** A failure here means recording and breathing are not ordered, so the glow would fight itself while the mic is open. */
    @Test
    fun `a recording mic follows the level and never breathes`() {
        assertEquals(
            "overlay: the recording mic must follow the level even while it is ready",
            MicMotion.LEVEL_PULSE,
            micMotion(TileState.RECORDING, switchOn = true, ready = true, visible = true, animationsOn = true),
        )
        assertNotEquals(
            "overlay: the recording mic must not breathe",
            MicMotion.BREATHE,
            micMotion(TileState.RECORDING, switchOn = true, ready = true, visible = true, animationsOn = true),
        )
    }

    /** A failure here means the breathing motion is attached to the wrong tile state, which would breathe the switched-off mic and hold the ready one still. */
    @Test
    fun `an armed and ready mic breathes and the switched off idle mic does not`() {
        assertEquals(
            "overlay: ARMED, switched on and ready, is the breathing state",
            MicMotion.BREATHE,
            micMotion(TileState.ARMED, switchOn = true, ready = true, visible = true, animationsOn = true),
        )
        assertEquals(
            "overlay: IDLE, the switched-off tile, must never breathe",
            MicMotion.DIM_STEADY,
            micMotion(TileState.IDLE, switchOn = false, ready = false, visible = true, animationsOn = true),
        )
        assertNotEquals(
            "overlay: IDLE must not answer the way ARMED does",
            micMotion(TileState.ARMED, switchOn = true, ready = true, visible = true, animationsOn = true),
            micMotion(TileState.IDLE, switchOn = false, ready = false, visible = true, animationsOn = true),
        )
        assertEquals(
            "overlay: an armed but not ready mic holds still at its own strength",
            MicMotion.STEADY,
            micMotion(TileState.ARMED, switchOn = true, ready = false, visible = true, animationsOn = true),
        )
    }

    /** A failure here means the view's switched-on test answers the wrong way round, which sends every motion but the dim one dark while the rest of this file stays green. */
    @Test
    fun `the switched on test answers false only for the idle state`() {
        assertEquals(
            "overlay: IDLE is the switched-off state, so it is the one state that is not on",
            false,
            switchOnFor(TileState.IDLE),
        )
        for (state in TileState.entries) {
            if (state == TileState.IDLE) continue
            assertTrue(
                "overlay: $state is switched on, so switchOnFor must answer true",
                switchOnFor(state),
            )
        }
    }

    /** A failure here means a busy, sending, failed or finished mic is dimmed like the off mic, so a failure would look exactly like dictation being switched off. */
    @Test
    fun `the busy sending failed and finished states hold at their own strength and are not dim`() {
        for (state in quietStates) {
            assertEquals(
                "overlay: a $state mic must hold at its own strength",
                MicMotion.STEADY,
                micMotion(state, switchOn = true, ready = true, visible = true, animationsOn = true),
            )
            assertNotEquals(
                "overlay: a $state mic must not be dim, which is the off mic alone",
                MicMotion.DIM_STEADY,
                micMotion(state, switchOn = true, ready = true, visible = true, animationsOn = true),
            )
            assertNotEquals(
                "overlay: a $state mic must not move",
                MicMotion.LEVEL_PULSE,
                micMotion(state, switchOn = true, ready = true, visible = true, animationsOn = true),
            )
        }
        assertNotEquals(
            "overlay: the failed state and the off state must not answer the same way",
            micMotion(TileState.IDLE, switchOn = false, ready = true, visible = true, animationsOn = true),
            micMotion(TileState.FAILED, switchOn = true, ready = true, visible = true, animationsOn = true),
        )
    }

    /** A failure here means an invisible or motionless mic is dimmed like the off mic, so the user could not tell "not on screen" from "switched off". */
    @Test
    fun `a still mic holds at its own strength and is never dim`() {
        for (state in quietStates + TileState.RECORDING + TileState.ARMED) {
            assertEquals(
                "overlay: an invisible $state mic must hold at its own strength",
                MicMotion.STEADY,
                micMotion(state, switchOn = true, ready = true, visible = false, animationsOn = true),
            )
            assertEquals(
                "overlay: a $state mic with animations off must hold at its own strength",
                MicMotion.STEADY,
                micMotion(state, switchOn = true, ready = true, visible = true, animationsOn = false),
            )
            assertNotEquals(
                "overlay: a still $state mic must not be dim",
                MicMotion.DIM_STEADY,
                micMotion(state, switchOn = true, ready = true, visible = false, animationsOn = true),
            )
            assertNotEquals(
                "overlay: a $state mic with animations off must not be dim",
                MicMotion.DIM_STEADY,
                micMotion(state, switchOn = true, ready = true, visible = true, animationsOn = false),
            )
        }
        assertNotEquals(
            "overlay: the invisible armed mic and the off mic must not answer the same way",
            micMotion(TileState.IDLE, switchOn = false, ready = true, visible = true, animationsOn = true),
            micMotion(TileState.ARMED, switchOn = true, ready = true, visible = false, animationsOn = true),
        )
    }

    /** A failure here means the decision moved: a state's answer changed, or the same inputs no longer give the same answer. */
    @Test
    fun `every input combination answers the one motion its state calls for`() {
        val answers = HashMap<String, MicMotion>()
        for (state in TileState.values()) {
            for (switchOn in listOf(true, false)) {
                for (ready in listOf(true, false)) {
                    for (visible in listOf(true, false)) {
                        for (animationsOn in listOf(true, false)) {
                            val key = "$state $switchOn $ready $visible $animationsOn"
                            val expected = when {
                                !switchOn -> MicMotion.DIM_STEADY
                                !visible || !animationsOn -> MicMotion.STEADY
                                state == TileState.RECORDING -> MicMotion.LEVEL_PULSE
                                state == TileState.ARMED && ready -> MicMotion.BREATHE
                                else -> MicMotion.STEADY
                            }
                            assertEquals("overlay: $key", expected, micMotion(state, switchOn, ready, visible, animationsOn))
                            assertEquals(
                                "overlay: $key asked twice must answer once",
                                micMotion(state, switchOn, ready, visible, animationsOn),
                                micMotion(state, switchOn, ready, visible, animationsOn),
                            )
                            answers[key] = expected
                        }
                    }
                }
            }
        }
        assertEquals(
            "overlay: the control expects the scan to have reached every combination", TileState.values().size * 16, answers.size)
        val atFullStrength = TileState.values().associateWith {
            micMotion(it, switchOn = true, ready = true, visible = true, animationsOn = true)
        }
        assertEquals(
            "overlay: the eight states with the switch on and nothing holding them back must answer the three motions of the decision, and none of them dim",
            mapOf(
                TileState.IDLE to MicMotion.STEADY,
                TileState.ARMED to MicMotion.BREATHE,
                TileState.RECORDING to MicMotion.LEVEL_PULSE,
                TileState.SENDING to MicMotion.STEADY,
                TileState.FAILED to MicMotion.STEADY,
                TileState.SENT to MicMotion.STEADY,
                TileState.SENT_LOCAL to MicMotion.STEADY,
                TileState.MIC_BUSY to MicMotion.STEADY,
            ),
            atFullStrength,
        )
    }
}
