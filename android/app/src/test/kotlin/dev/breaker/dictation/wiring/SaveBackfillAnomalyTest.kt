package dev.breaker.dictation.wiring

import dev.breaker.dictation.sync.SaveBackfillWalk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emit-only-on-anomaly rule for the fixed start-up warning: a normal walk logs nothing. */
class SaveBackfillAnomalyTest {

    @Test
    fun `a normal walk needs no anomaly warning`() {
        assertFalse("android_wiring: a clean walk emits nothing", saveBackfillAnomaly(SaveBackfillWalk(reQueued = 0, markerFailures = 0, deferred = 0, complete = true)))
    }

    @Test
    fun `a walk with only deferred live markers needs no anomaly warning`() {
        assertFalse("android_wiring: deferred live markers are normal, not an anomaly", saveBackfillAnomaly(SaveBackfillWalk(reQueued = 0, markerFailures = 0, deferred = 3, complete = true)))
    }

    @Test
    fun `a walk that re-queued a saved take needs the anomaly warning`() {
        assertTrue("android_wiring: a re-queued take is an anomaly", saveBackfillAnomaly(SaveBackfillWalk(reQueued = 1, markerFailures = 0, deferred = 0, complete = true)))
    }

    @Test
    fun `a walk that could not re-queue a saved take needs the anomaly warning`() {
        assertTrue("android_wiring: a marker failure is an anomaly", saveBackfillAnomaly(SaveBackfillWalk(reQueued = 0, markerFailures = 1, deferred = 0, complete = true)))
    }
}
