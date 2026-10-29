package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.KeyboardProbeReadings
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ShakeWakeMode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The keyboard wiring with a wake policy: each reading sets the policy's keyboard flag before the keyboard
 * entry runs, a lost signal clears the flag without calling the entry, and the baseline seeds the flag.
 * Every test starts from a non-null baseline, so the collector's first emission is the baseline itself and
 * posts nothing; the test then publishes the reading it checks.
 */
class KeyboardWakeWiringTest {

    /** Keeps each posted block in order and completes one signal per post index, from 1 to 4. */
    private class WakeMainQueue : MainPost {
        private val signals: List<CompletableDeferred<() -> Unit>> = List(4) { CompletableDeferred<() -> Unit>() }
        private val count = AtomicInteger()

        val posts: Int
            get() = count.get()

        fun nth(index: Int): CompletableDeferred<() -> Unit> = signals[index - 1]

        override fun post(block: () -> Unit) {
            val index: Int = count.incrementAndGet()
            if (index <= signals.size) signals[index - 1].complete(block)
        }
    }

    @Before
    fun clearHolder() {
        KeyboardProbeReadings.setEnabled(false)
    }

    @After
    fun leaveOff() {
        KeyboardProbeReadings.setEnabled(false)
    }

    @Test
    fun `a reading with the keyboard window up sets the policy flag`() {
        KeyboardProbeReadings.setEnabled(true)
        KeyboardProbeReadings.publishLatest(fieldFocus = false, imeWindow = false, lastEvent = "baseline", time = 1000L)
        val policy = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.KEYBOARD_ONLY) }
        val queue = WakeMainQueue()
        val entries = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val wiring = KeyboardTileWiring({ ime, focus, time -> entries.add(Triple(ime, focus, time)) }, queue, policy)
        try {
            wiring.start()
            KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = true, lastEvent = "typed", time = 2000L)
            val block: () -> Unit = awaitBounded("the reading posted after the start", queue.nth(1))
            block()
            assertTrue("app: a reading with the keyboard window up must set the policy flag", policy.shakeMayShow())
            assertEquals("app: the keyboard entry must get the reading once", listOf(Triple(true, true, 2000L)), entries)
        } finally {
            wiring.stop()
        }
    }

    @Test
    fun `a reading without the keyboard window clears the policy flag`() {
        KeyboardProbeReadings.setEnabled(true)
        KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = true, lastEvent = "baseline", time = 1000L)
        val policy = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.KEYBOARD_ONLY) }
        val queue = WakeMainQueue()
        val wiring = KeyboardTileWiring({ _, _, _ -> }, queue, policy)
        try {
            wiring.start()
            KeyboardProbeReadings.publishLatest(fieldFocus = false, imeWindow = false, lastEvent = "gone", time = 2000L)
            val block: () -> Unit = awaitBounded("the reading posted after the start", queue.nth(1))
            block()
            assertFalse("app: a reading without the keyboard window must clear the policy flag", policy.shakeMayShow())
        } finally {
            wiring.stop()
        }
    }

    @Test
    fun `a null reading clears the policy flag and does not call the keyboard entry`() {
        KeyboardProbeReadings.setEnabled(true)
        KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = true, lastEvent = "baseline", time = 1000L)
        val policy = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.KEYBOARD_ONLY) }
        val queue = WakeMainQueue()
        val entries = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val wiring = KeyboardTileWiring({ ime, focus, time -> entries.add(Triple(ime, focus, time)) }, queue, policy)
        try {
            wiring.start()
            KeyboardProbeReadings.setEnabled(false)
            val block: () -> Unit = awaitBounded("the signal lost block", queue.nth(1))
            block()
            assertFalse("app: a null reading must clear the policy flag", policy.shakeMayShow())
            assertEquals("app: a null reading must not call the keyboard entry", emptyList<Triple<Boolean, Boolean, Long>>(), entries)
        } finally {
            wiring.stop()
        }
    }

    @Test
    fun `the baseline seeds the policy flag before any new reading`() {
        KeyboardProbeReadings.setEnabled(true)
        KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = true, lastEvent = "baseline", time = 1000L)
        val policy = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.KEYBOARD_ONLY) }
        val wiring = KeyboardTileWiring({ _, _, _ -> }, WakeMainQueue(), policy)
        try {
            wiring.start()
            assertTrue("app: a keyboard open at the start must seed the policy flag", policy.shakeMayShow())
        } finally {
            wiring.stop()
        }
    }

    @Test
    fun `every non-null reading posts exactly one block with a policy`() {
        KeyboardProbeReadings.setEnabled(true)
        KeyboardProbeReadings.publishLatest(fieldFocus = false, imeWindow = false, lastEvent = "baseline", time = 1000L)
        val policy = WakePolicy { AppSettings() }
        val queue = WakeMainQueue()
        val entries = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val wiring = KeyboardTileWiring({ ime, focus, time -> entries.add(Triple(ime, focus, time)) }, queue, policy)
        try {
            wiring.start()
            KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = true, lastEvent = "a", time = 2000L)
            awaitBounded("the first reading", queue.nth(1))()
            KeyboardProbeReadings.publishLatest(fieldFocus = false, imeWindow = false, lastEvent = "b", time = 3000L)
            awaitBounded("the second reading", queue.nth(2))()
            KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = false, lastEvent = "c", time = 4000L)
            awaitBounded("the third reading", queue.nth(3))()
            assertEquals(
                "app: three readings must post exactly three blocks, one each",
                listOf(Triple(true, true, 2000L), Triple(false, false, 3000L), Triple(false, true, 4000L)),
                entries,
            )
            assertEquals("app: three readings must make three posts", 3, queue.posts)
        } finally {
            wiring.stop()
        }
    }

    @Test
    fun `the policy flag is set before the keyboard entry is called`() {
        KeyboardProbeReadings.setEnabled(true)
        KeyboardProbeReadings.publishLatest(fieldFocus = false, imeWindow = false, lastEvent = "baseline", time = 1000L)
        val policy = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.KEYBOARD_ONLY) }
        val queue = WakeMainQueue()
        val seen = mutableListOf<Boolean>()
        val wiring = KeyboardTileWiring({ _, _, _ -> seen.add(policy.shakeMayShow()) }, queue, policy)
        try {
            wiring.start()
            KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = true, lastEvent = "typed", time = 2000L)
            val block: () -> Unit = awaitBounded("the reading posted after the start", queue.nth(1))
            block()
            assertEquals("app: the entry must see the flag already set by its own reading", listOf(true), seen)
        } finally {
            wiring.stop()
        }
    }

    @Test
    fun `without a policy a null reading posts nothing`() {
        KeyboardProbeReadings.setEnabled(true)
        KeyboardProbeReadings.publishLatest(fieldFocus = false, imeWindow = false, lastEvent = "baseline", time = 1000L)
        val queue = WakeMainQueue()
        val entries = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val wiring = KeyboardTileWiring({ ime, focus, time -> entries.add(Triple(ime, focus, time)) }, queue)
        try {
            wiring.start()
            KeyboardProbeReadings.setEnabled(false)
            KeyboardProbeReadings.setEnabled(true)
            KeyboardProbeReadings.publishLatest(fieldFocus = true, imeWindow = true, lastEvent = "typed", time = 3000L)
            val block: () -> Unit = awaitBounded("the reading after the null", queue.nth(1))
            block()
            assertEquals("app: without a policy the first block must be the reading itself", listOf(Triple(true, true, 3000L)), entries)
        } finally {
            wiring.stop()
        }
    }
}
