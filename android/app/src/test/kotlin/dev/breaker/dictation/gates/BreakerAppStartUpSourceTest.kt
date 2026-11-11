package dev.breaker.dictation.gates

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start-up wiring BreakerApp cannot be run: it calls the paged backfill, the bounded push, and
 * the port-taking start-up sequence. Read as text, with comments and literal text removed.
 */
class BreakerAppStartUpSourceTest {

    private val app: String = "kotlin/dev/breaker/dictation/BreakerApp.kt"

    @Test
    fun `BreakerApp calls the paged backfill, the bounded push and the start-up sequence`() {
        val code = AppSourceFiles.strip(AppSourceFiles.mainFile(app)).code
        assertTrue("app: BreakerApp must call the paged backfill", code.contains("backfillAllUserTombstones"))
        assertTrue("app: BreakerApp must call the bounded push", code.contains("boundedPush("))
        assertTrue("app: the sign-out push is bounded to 5 s", code.contains("5_000"))
        assertTrue("app: BreakerApp must call the port-taking start-up sequence", code.contains("runStartUpAfterClear"))
    }
}
