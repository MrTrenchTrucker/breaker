package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PassThroughNoiseSuppressor], the suppressor that does nothing.
 *
 * ### Why it is worth a test at all
 *
 * It is public and in the module's registry, and it had no test. It is also the
 * object every take runs when the app's native library carries no neural
 * suppressor, so it is on the live path for anyone shipping without one — a
 * defect here is silence that looks like clean audio.
 *
 * The class does nothing, so there is little to get wrong in the arithmetic.
 * What can go wrong is its *contract*, and the contract has three parts worth
 * holding:
 *
 * - it must report `isActive == false`, so a take can say it was not suppressed
 *   rather than leaving a caller to infer that from the audio;
 * - it must return a **copy**, not the array it was handed. The interface says the
 *   input is left untouched. Returning the same array would let a caller that
 *   writes into the result corrupt the frame the suppressor was given, and the
 *   capture hands the same frame onward;
 * - it must return the same length, because the interface requires implementations
 *   to return exactly as many samples as they were given.
 */
class PassThroughNoiseSuppressorTest {

    private val suppressor: NoiseSuppressor = PassThroughNoiseSuppressor

    private fun frameOf(vararg samples: Float): FloatArray = samples.copyOf()


    /**
     * Exact element-wise equality. JUnit's `assertArrayEquals` has no
     * `FloatArray` overload, so this compares the contents and reports the first
     * difference by index rather than falling back to identity.
     */
    private fun assertSamplesEqual(
        expected: FloatArray,
        actual: FloatArray,
        what: String,
    ) {
        assertEquals("$what: length ${actual.size} vs ${expected.size}", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("$what: first difference at index $i", expected[i], actual[i])
        }
    }

    @Test
    fun `it reports that it does nothing`() {
        assertFalse(
            "a pass-through suppressor must say it is inactive, or a take cannot " +
                "report that it went unsuppressed",
            suppressor.isActive,
        )
    }

    @Test
    fun `it returns the samples unchanged`() {
        val frame = frameOf(0f, 0.25f, -0.5f, 1f, -1f, 0.125f)
        assertSamplesEqual(
            frame,
            suppressor.process(frame),
            "a pass-through suppressor must return the samples it was given, "
                + "bit for bit",
        )
    }

    @Test
    fun `it returns a copy, so the result cannot write back into the input`() {
        val frame = frameOf(0.25f, -0.5f, 1f)
        val result = suppressor.process(frame)

        assertNotSame(
            "process must return a copy: handing back the same array lets a caller " +
                "that writes into the result corrupt the frame it was given",
            frame,
            result,
        )

        // The observable consequence, not just the identity check: mutate what came
        // back and the input must be untouched.
        result[0] = 99f
        result[1] = -99f
        assertSamplesEqual(
            frameOf(0.25f, -0.5f, 1f),
            frame,
            "writing into the returned array must not reach back into the input",
        )
    }

    @Test
    fun `it returns the same length it was given`() {
        // The interface requires exactly as many samples back as came in, for
        // every frame size the capture might hand over.
        for (size in intArrayOf(0, 1, 2, 17, 320, 800)) {
            val frame = FloatArray(size) { it.toFloat() }
            assertTrue(
                "process must return the frame's own length, but ${frame.size} " +
                    "came back as ${suppressor.process(frame).size}",
                suppressor.process(frame).size == size,
            )
        }
    }

    @Test
    fun `reset is a no-op that leaves it usable`() {
        // Nothing is learned, so reset has nothing to clear; what it must not do is
        // break the object.
        suppressor.reset()
        assertFalse(suppressor.isActive)
        assertSamplesEqual(
            frameOf(0.5f, -0.5f),
            suppressor.process(frameOf(0.5f, -0.5f)),
            "reset must leave the pass-through suppressor usable",
        )
    }

    @Test
    fun `it is the singleton the registry describes`() {
        // It is an `object`, so the same instance comes back every time. That is
        // part of what makes it safe as the default: there is exactly one.
        assertSame(PassThroughNoiseSuppressor, PassThroughNoiseSuppressor)
    }
}
