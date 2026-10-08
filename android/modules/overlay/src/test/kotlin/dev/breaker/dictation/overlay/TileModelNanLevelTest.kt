package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the tile model draws when the sound level is not a number: the meter shows nothing, and the
 * model is not left in a state where later real levels are lost. The only thing visible from outside
 * is the number of lit segments of the face.
 */
class TileModelNanLevelTest {

    private fun recording(): TileModel = TileModel().also { it.setState(TileState.RECORDING) }

    private fun TileModel.lit(): Int = face(ThemeMode.LIGHT).litSegments

    /** A failure means a level that is not a number keeps the meter lit, or the real level never lit it. */
    @Test
    fun `a level that is not a number after a real level lights nothing`() {
        val model = recording()
        model.setLevel(0.5f)
        assertEquals("overlay: level 0.5 expected the meter's own count", LedMeter.lit(0.5f), model.lit())
        assertTrue("overlay: level 0.5 expected at least one lit segment", model.lit() > 0)
        model.setLevel(Float.NaN)
        assertEquals("overlay: a level that is not a number expected 0 lit segments", 0, model.lit())
    }

    /** A failure means a level that is not a number, pushed first, lights a segment when recording starts. */
    @Test
    fun `a level that is not a number pushed before recording lights nothing`() {
        val model = TileModel()
        model.setLevel(Float.NaN)
        model.setState(TileState.RECORDING)
        assertEquals("overlay: a level that is not a number before recording expected 0 lit segments", 0, model.lit())
    }

    /** A failure means a level that is not a number leaves the old high level on the meter. */
    @Test
    fun `a level that is not a number drops a high level to zero and does not keep it`() {
        val model = recording()
        model.setLevel(1f)
        assertEquals("overlay: level 1 expected every segment lit", LedMeter.SEGMENTS, model.lit())
        model.setLevel(Float.NaN)
        assertEquals("overlay: a level that is not a number after level 1 expected 0 lit segments", 0, model.lit())
    }

    /** A failure means a level that is not a number leaves the model unable to show later real levels. */
    @Test
    fun `a real level after a level that is not a number lights the meter again`() {
        val model = recording()
        model.setLevel(Float.NaN)
        assertEquals("overlay: a level that is not a number expected 0 lit segments", 0, model.lit())
        model.setLevel(0.5f)
        assertEquals("overlay: level 0.5 after a level that is not a number expected the meter's own count", LedMeter.lit(0.5f), model.lit())
        assertTrue("overlay: level 0.5 after a level that is not a number expected at least one lit segment", model.lit() > 0)
    }
}
