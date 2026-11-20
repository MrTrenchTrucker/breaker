package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SaveBackfillWiring wires a REAL pending-save port into the history decorator, not the no-op default (C-F1 would be dead otherwise). */
class MarkerPortWiringTest {

    private val app: String = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/SaveBackfillWiring.kt")

    @Test
    fun `the history decorator is wired with a real pending-save port not the no-op`() {
        assertTrue("wiring: the construction passes a real marker port", app.contains("marker = port"))
        assertTrue("wiring: the port marks through the queue service", app.contains("syncService.markPendingSave(clientId, runToken)"))
        assertTrue("wiring: the port clears through the queue service", app.contains("syncService.clearPendingSave(clientId)"))
        assertFalse("wiring: the construction must not use the no-op marker port", app.contains("marker = NoOpPendingSavePort"))
    }
}