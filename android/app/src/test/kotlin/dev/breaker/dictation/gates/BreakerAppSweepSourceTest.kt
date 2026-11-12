package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tombstone sweep wiring BreakerApp cannot be run, so it is read as text, with comments and literal text
 * removed: the walk and the sweep sit in the one coroutine behind the one decision, in that order, and the
 * sweep is the public method, called once.
 */
class BreakerAppSweepSourceTest {

    private fun code(path: String): String = AppSourceFiles.strip(AppSourceFiles.mainFile(path)).code

    private fun count(text: String, part: String): Int = Regex(Regex.escape(part)).findAll(text).count()

    @Test
    fun `the app runs the walk and the sweep in one coroutine through the one decision`() {
        val app = code("kotlin/dev/breaker/dictation/BreakerApp.kt")
        val launch = app.indexOf("purgeScope.launch")
        val decision = app.indexOf("backfillThenSweep(")
        val walk = app.indexOf("backfillAllUserTombstonesChecked(")
        val sweep = app.indexOf("history.sweepExpiredTombstones()")
        assertTrue("app: the coroutine, the decision, the walk and the sweep must all be there", launch >= 0 && decision >= 0 && walk >= 0 && sweep >= 0)
        assertTrue("app: the order must be coroutine, decision, walk, sweep", launch < decision && decision < walk && walk < sweep)
        assertEquals("app: exactly one sweep call", 1, count(app, "sweepExpiredTombstones("))
    }

    @Test
    fun `the app calls neither the unchecked walk nor the internal sweep`() {
        val app = code("kotlin/dev/breaker/dictation/BreakerApp.kt")
        assertFalse("app: the unchecked walk cannot tell a cut walk", app.contains("backfillAllUserTombstones("))
        assertFalse("app: the internal sweep name is not for the app", app.contains("purgeExpiredTombstones"))
    }

    @Test
    fun `the start-up sequence hands the guard outcome to the backfill`() {
        val sequence = code("kotlin/dev/breaker/dictation/StartUpSequence.kt")
        assertTrue("app: the backfill must be given the outcome", sequence.contains("backfill(outcome)"))
        assertFalse("app: a backfill call without the outcome", sequence.contains("backfill()"))
    }
}
