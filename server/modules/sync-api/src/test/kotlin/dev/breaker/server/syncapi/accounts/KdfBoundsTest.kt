package dev.breaker.server.syncapi.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class KdfBoundsTest {

    private val low = KdfBounds.MIN_MEMORY_KIB
    private val high = KdfBounds.MAX_MEMORY_KIB

    @Test
    fun `the bounds constants are the agreed ones`() {
        assertEquals("min memory", 65536, KdfBounds.MIN_MEMORY_KIB)
        assertEquals("max memory", 262144, KdfBounds.MAX_MEMORY_KIB)
        assertEquals("min iterations", 3, KdfBounds.MIN_ITERATIONS)
        assertEquals("max iterations", 10, KdfBounds.MAX_ITERATIONS)
        assertEquals("min parallelism", 1, KdfBounds.MIN_PARALLELISM)
        assertEquals("max parallelism", 4, KdfBounds.MAX_PARALLELISM)
        assertEquals("required kdf version", 1, KdfBounds.REQUIRED_KDF_VERSION)
    }

    @Test
    fun `withinBounds accepts all minimums, all maximums and the default`() {
        assertTrue("all minimums", KdfParams(low, KdfBounds.MIN_ITERATIONS, KdfBounds.MIN_PARALLELISM).withinBounds())
        assertTrue("all maximums", KdfParams(high, KdfBounds.MAX_ITERATIONS, KdfBounds.MAX_PARALLELISM).withinBounds())
        assertTrue("the default 65536/3/1", KdfParams(65536, 3, 1).withinBounds())
    }

    @Test
    fun `withinBounds refuses each field one past either end`() {
        val cases = listOf(
            "memory one below the minimum" to KdfParams(low - 1, 3, 1),
            "memory one above the maximum" to KdfParams(high + 1, 3, 1),
            "iterations one below the minimum" to KdfParams(low, KdfBounds.MIN_ITERATIONS - 1, 1),
            "iterations one above the maximum" to KdfParams(low, KdfBounds.MAX_ITERATIONS + 1, 1),
            "parallelism one below the minimum" to KdfParams(low, 3, KdfBounds.MIN_PARALLELISM - 1),
            "parallelism one above the maximum" to KdfParams(low, 3, KdfBounds.MAX_PARALLELISM + 1),
        )
        for ((name, params) in cases) {
            assertFalse("$name must be out of bounds", params.withinBounds())
        }
    }

    @Test
    fun `withinBounds refuses the extreme Int values of each field`() {
        val cases = listOf(
            "memory Int.MIN_VALUE" to KdfParams(Int.MIN_VALUE, 3, 1),
            "memory Int.MAX_VALUE" to KdfParams(Int.MAX_VALUE, 3, 1),
            "iterations Int.MIN_VALUE" to KdfParams(low, Int.MIN_VALUE, 1),
            "iterations Int.MAX_VALUE" to KdfParams(low, Int.MAX_VALUE, 1),
            "parallelism Int.MIN_VALUE" to KdfParams(low, 3, Int.MIN_VALUE),
            "parallelism Int.MAX_VALUE" to KdfParams(low, 3, Int.MAX_VALUE),
        )
        for ((name, params) in cases) {
            assertFalse("$name must be out of bounds", params.withinBounds())
        }
    }
}
