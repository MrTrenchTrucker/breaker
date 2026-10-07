package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefusal.BAD_SALT_LENGTH
import dev.breaker.dictation.core.model.KdfRefusal.ITERATIONS_ABOVE_CEILING
import dev.breaker.dictation.core.model.KdfRefusal.ITERATIONS_BELOW_FLOOR
import dev.breaker.dictation.core.model.KdfRefusal.MEMORY_ABOVE_CEILING
import dev.breaker.dictation.core.model.KdfRefusal.MEMORY_BELOW_FLOOR
import dev.breaker.dictation.core.model.KdfRefusal.OUTPUT_LENGTH_NOT_32
import dev.breaker.dictation.core.model.KdfRefusal.PARALLELISM_ABOVE_CEILING
import dev.breaker.dictation.core.model.KdfRefusal.PARALLELISM_BELOW_FLOOR
import dev.breaker.dictation.core.model.KdfRefusal.UNSUPPORTED_VERSION
import dev.breaker.dictation.core.model.KdfRefused
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * checkKdfParams applies the limits on the stored key-derivation settings: memory
 * 65536 to 262144 KiB, iterations 3 to 10, parallelism 1 to 4, output length exactly
 * 32, salt exactly 16 bytes, version exactly 1. The limits are inclusive, and the
 * first rule broken is the one reported, in the order just listed with the version
 * last. A claimed version never relaxes a range rule.
 *
 * Every number below is a literal on purpose: a test that read the limits from the
 * code under test could not notice a wrong limit. Each case alters one thing from a
 * valid set and checks the reason, not just that something was thrown.
 */
class KdfLimitsTest {

    private val base = KdfParams(65536, 3, 1, 32, 1)
    private val claimedVersions = listOf(1, 2, 99)

    private fun salt(size: Int = 16) = ByteArray(size) { it.toByte() }

    private fun assertRefused(case: String, reason: KdfRefusal, salt: ByteArray, params: KdfParams) {
        try {
            checkKdfParams(salt, params)
        } catch (e: KdfRefused) {
            assertEquals("$case: refused for the wrong reason", reason, e.reason)
            return
        }
        fail("$case: expected the refusal $reason but the settings were accepted")
    }

    private fun assertAccepted(case: String, salt: ByteArray, params: KdfParams) {
        try {
            checkKdfParams(salt, params)
        } catch (e: KdfRefused) {
            fail("$case: settings inside the limits were refused with ${e.reason}")
        }
    }

    private fun withField(name: String, value: Int, version: Int = 1): KdfParams = when (name) {
        "memoryKib" -> base.copy(memoryKib = value, version = version)
        "iterations" -> base.copy(iterations = value, version = version)
        "parallelism" -> base.copy(parallelism = value, version = version)
        "outputLength" -> base.copy(outputLength = value, version = version)
        else -> throw IllegalArgumentException("unknown field $name")
    }

    /** Runs the same refusal with every claimed version: a range reason must not depend on the version. */
    private fun assertRefusedAtEveryVersion(name: String, values: List<Int>, reason: KdfRefusal) {
        for (version in claimedVersions) {
            for (value in values) {
                val params = withField(name, value, version)
                assertRefused("$name=$value version=$version", reason, salt(), params)
            }
        }
    }

    /** A failure means a memory cost under 65536 KiB was accepted or reported with another reason. */
    @Test
    fun `memory below the floor is refused`() {
        assertRefusedAtEveryVersion("memoryKib", listOf(65535, 0, -1, Int.MIN_VALUE), MEMORY_BELOW_FLOOR)
    }

    /** A failure means the inclusive memory limits 65536 and 262144 were refused. */
    @Test
    fun `memory at the floor and at the ceiling is accepted`() {
        for (memory in listOf(65536, 262144)) {
            assertAccepted("memoryKib=$memory", salt(), withField("memoryKib", memory))
        }
    }

    /** A failure means a memory cost over 262144 KiB was accepted or reported with another reason. */
    @Test
    fun `memory above the ceiling is refused`() {
        assertRefusedAtEveryVersion("memoryKib", listOf(262145, Int.MAX_VALUE), MEMORY_ABOVE_CEILING)
    }

    /** A failure means fewer than 3 passes were accepted or reported with another reason. */
    @Test
    fun `iterations below the floor are refused`() {
        assertRefusedAtEveryVersion("iterations", listOf(2, 0, -1), ITERATIONS_BELOW_FLOOR)
    }

    /** A failure means the inclusive limits 3 and 10 passes were refused. */
    @Test
    fun `iterations at the floor and at the ceiling are accepted`() {
        for (iterations in listOf(3, 10)) {
            assertAccepted("iterations=$iterations", salt(), withField("iterations", iterations))
        }
    }

    /** A failure means more than 10 passes were accepted or reported with another reason. */
    @Test
    fun `iterations above the ceiling are refused`() {
        assertRefusedAtEveryVersion("iterations", listOf(11, Int.MAX_VALUE), ITERATIONS_ABOVE_CEILING)
    }

    /** A failure means fewer than 1 lane was accepted or reported with another reason. */
    @Test
    fun `parallelism below the floor is refused`() {
        assertRefusedAtEveryVersion("parallelism", listOf(0, -1), PARALLELISM_BELOW_FLOOR)
    }

    /** A failure means the inclusive limits 1 and 4 lanes were refused. */
    @Test
    fun `parallelism at the floor and at the ceiling is accepted`() {
        for (parallelism in listOf(1, 4)) {
            assertAccepted("parallelism=$parallelism", salt(), withField("parallelism", parallelism))
        }
    }

    /** A failure means more than 4 lanes were accepted or reported with another reason. */
    @Test
    fun `parallelism above the ceiling is refused`() {
        assertRefusedAtEveryVersion("parallelism", listOf(5, Int.MAX_VALUE), PARALLELISM_ABOVE_CEILING)
    }

    /** A failure means an output length other than 32 was accepted or reported with another reason. */
    @Test
    fun `output length other than 32 is refused`() {
        assertRefusedAtEveryVersion("outputLength", listOf(0, 31, 33, -1, Int.MAX_VALUE), OUTPUT_LENGTH_NOT_32)
    }

    /** A failure means the one valid output length, 32, was refused. */
    @Test
    fun `output length 32 is accepted`() {
        assertAccepted("outputLength=32", salt(), base.copy(outputLength = 32))
    }

    /** A failure means a salt that is not 16 bytes long was accepted or reported with another reason. */
    @Test
    fun `salt length other than 16 is refused`() {
        for (size in listOf(0, 15, 17, 32)) {
            assertRefused("salt of $size bytes", BAD_SALT_LENGTH, salt(size), base)
        }
    }

    /** A failure means a 16-byte salt was refused. */
    @Test
    fun `salt of 16 bytes is accepted`() {
        assertAccepted("salt of 16 bytes", salt(16), base)
    }

    /** A failure means a version other than 1 was accepted or reported with another reason. */
    @Test
    fun `version other than 1 is refused`() {
        for (version in listOf(0, -1, 2, 99)) {
            assertRefused("version=$version", UNSUPPORTED_VERSION, salt(), base.copy(version = version))
        }
    }

    /** A failure means version 1 was refused. */
    @Test
    fun `version 1 is accepted`() {
        assertAccepted("version=1", salt(), base.copy(version = 1))
    }

    /** A failure means the memory rule is not checked first. */
    @Test
    fun `everything bad at once reports the memory floor`() {
        assertRefused("all bad", MEMORY_BELOW_FLOOR, salt(0), KdfParams(0, 0, 0, 0, 0))
    }

    /** A failure means iterations are checked after the salt or the version. */
    @Test
    fun `memory fine but iterations and salt and version bad reports iterations`() {
        assertRefused("iterations first", ITERATIONS_BELOW_FLOOR, salt(15), KdfParams(65536, 2, 1, 32, 7))
    }

    /** A failure means the version is checked before the salt. */
    @Test
    fun `only salt and version bad reports the salt`() {
        assertRefused("salt before version", BAD_SALT_LENGTH, salt(17), base.copy(version = 2))
    }

    /** A failure means the version rule is missing or is not applied when it is the only fault. */
    @Test
    fun `only version bad reports the version`() {
        assertRefused("version alone", UNSUPPORTED_VERSION, salt(), base.copy(version = 2))
    }

    /** A failure means a claimed version 2 turned a range refusal into a version refusal. */
    @Test
    fun `memory below the floor with version 2 still reports the memory floor`() {
        val params = base.copy(memoryKib = 65535, version = 2)
        assertRefused("memory with version 2", MEMORY_BELOW_FLOOR, salt(), params)
    }

    private class Violation(
        val name: String,
        val reason: KdfRefusal,
        val group: String,
        val change: (KdfParams) -> KdfParams,
    )

    private val violationsInCheckOrder = listOf(
        Violation("memoryKib=65535", MEMORY_BELOW_FLOOR, "memory") { it.copy(memoryKib = 65535) },
        Violation("memoryKib=262145", MEMORY_ABOVE_CEILING, "memory") { it.copy(memoryKib = 262145) },
        Violation("iterations=2", ITERATIONS_BELOW_FLOOR, "iterations") { it.copy(iterations = 2) },
        Violation("iterations=11", ITERATIONS_ABOVE_CEILING, "iterations") { it.copy(iterations = 11) },
        Violation("parallelism=0", PARALLELISM_BELOW_FLOOR, "parallelism") { it.copy(parallelism = 0) },
        Violation("parallelism=5", PARALLELISM_ABOVE_CEILING, "parallelism") { it.copy(parallelism = 5) },
        Violation("outputLength=31", OUTPUT_LENGTH_NOT_32, "output") { it.copy(outputLength = 31) },
        Violation("version=2", UNSUPPORTED_VERSION, "version") { it.copy(version = 2) },
    )

    /** A failure means two broken rules are reported in some order other than the documented one. */
    @Test
    fun `any two broken rules report the earlier one`() {
        for (i in violationsInCheckOrder.indices) {
            for (j in i + 1 until violationsInCheckOrder.size) {
                val earlier = violationsInCheckOrder[i]
                val later = violationsInCheckOrder[j]
                if (earlier.group == later.group) continue
                val params = later.change(earlier.change(base))
                assertRefused("${earlier.name} together with ${later.name}", earlier.reason, salt(), params)
            }
        }
    }

    /** A failure means a bad salt is reported before a range or output fault, or after a bad version. */
    @Test
    fun `a bad salt is reported after the output rule and before the version`() {
        for (violation in violationsInCheckOrder) {
            val params = violation.change(base)
            val expected = if (violation.reason == UNSUPPORTED_VERSION) BAD_SALT_LENGTH else violation.reason
            assertRefused("salt of 15 bytes together with ${violation.name}", expected, salt(15), params)
        }
    }
}
