package dev.breaker.dictation.stt.server

import java.io.IOException
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generous safety net only: no passing test depends on this value, it just stops a broken watchdog from hanging the run. */
private const val SAFETY_MS = 5_000L

/** A deadline that never fires, so the work alone decides the outcome. */
private object CallWatchdogTestNeverFires : DeadlineTimer {
    override suspend fun await(ms: Long) {
        CompletableDeferred<Unit>().await()
    }
}

/** A deadline the test fires by completing [fire]. */
private class CallWatchdogTestGatedTimer(private val fire: CompletableDeferred<Unit>) : DeadlineTimer {
    override suspend fun await(ms: Long) {
        fire.await()
    }
}

class CallWatchdogTest {

    @Test
    fun `work that finishes first returns its value as finished`() {
        val outcome = runBlocking {
            withTimeout(SAFETY_MS) {
                CallWatchdog(CallWatchdogTestNeverFires).run(1_000L, AbortHandle()) { 7 }
            }
        }
        assertEquals(7, (outcome as WatchdogOutcome.Finished<*>).value)
    }

    @Test
    fun `work that throws returns its error as failed`() {
        val outcome = runBlocking {
            withTimeout(SAFETY_MS) {
                CallWatchdog(CallWatchdogTestNeverFires).run<Int>(1_000L, AbortHandle()) { throw IOException("boom") }
            }
        }
        // The coroutine machinery may hand back a copy of the exception, so the type and message are checked, not identity.
        val error = (outcome as WatchdogOutcome.Failed).error
        assertTrue("the error keeps its type", error is IOException)
        assertEquals("boom", error.message)
    }

    @Test
    fun `timer that fires first aborts the exchange and returns expired`() {
        val fire = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val abort = AbortHandle()
        abort.onAbort { release.complete(Unit) }
        fire.complete(Unit)
        try {
            val outcome = runBlocking {
                withTimeout(SAFETY_MS) {
                    CallWatchdog(CallWatchdogTestGatedTimer(fire)).run(1_000L, abort) {
                        runBlocking { release.await() }
                        42
                    }
                }
            }
            assertSame(WatchdogOutcome.Expired, outcome)
            assertTrue("the deadline must abort the exchange", abort.isAborted)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `timer that fires first returns expired even when the work never returns`() {
        val fire = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        fire.complete(Unit)
        try {
            val outcome = runBlocking {
                withTimeout(SAFETY_MS) {
                    CallWatchdog(CallWatchdogTestGatedTimer(fire)).run(1_000L, AbortHandle()) {
                        runBlocking { neverReleased.await() }
                        0
                    }
                }
            }
            assertSame(WatchdogOutcome.Expired, outcome)
        } finally {
            neverReleased.complete(Unit)
        }
    }

    @Test
    fun `a late value after expired is dropped and expired is still returned`() {
        val fire = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lateDone = CompletableDeferred<Unit>()
        val abort = AbortHandle()
        abort.onAbort { release.complete(Unit) }
        fire.complete(Unit)
        try {
            val outcome = runBlocking {
                withTimeout(SAFETY_MS) {
                    CallWatchdog(CallWatchdogTestGatedTimer(fire)).run(1_000L, abort) {
                        runBlocking { release.await() }
                        lateDone.complete(Unit)
                        42
                    }
                }
            }
            // The abandoned work produces its value only after the outcome is already returned.
            runBlocking { withTimeout(SAFETY_MS) { lateDone.await() } }
            assertSame(WatchdogOutcome.Expired, outcome)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `zero or negative budget expires at once and never starts the work`() {
        for (budget in listOf(0L, -1L)) {
            val abort = AbortHandle()
            val started = AtomicBoolean(false)
            val outcome = runBlocking {
                CallWatchdog(CallWatchdogTestNeverFires).run(budget, abort) {
                    started.set(true)
                    1
                }
            }
            assertSame(WatchdogOutcome.Expired, outcome)
            assertTrue("an empty budget must abort the exchange", abort.isAborted)
            assertFalse("an empty budget must not start the work", started.get())
        }
    }

    @Test
    fun `cancelling the caller aborts the exchange and rethrows`() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val abort = AbortHandle()
        abort.onAbort { release.complete(Unit) }
        var returned = false
        try {
            runBlocking {
                val caller = launch {
                    CallWatchdog(CallWatchdogTestNeverFires, Dispatchers.IO).run(60_000L, abort) {
                        entered.complete(Unit)
                        runBlocking { release.await() }
                        1
                    }
                    returned = true
                }
                entered.await()
                caller.cancelAndJoin()
            }
            assertFalse("the caller must not get a normal return", returned)
            assertTrue("cancelling the caller must abort the exchange", abort.isAborted)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `cancellation thrown by the work is rethrown to the caller`() {
        var thrown: Throwable? = null
        runBlocking {
            try {
                CallWatchdog(CallWatchdogTestNeverFires).run<Int>(1_000L, AbortHandle()) {
                    throw CancellationException("work cancelled")
                }
            } catch (e: CancellationException) {
                thrown = e
            }
        }
        assertTrue("a cancellation from the work must reach the caller", thrown is CancellationException)
    }

    @Test
    fun `watchdog holds no mutable field`() {
        val fields = CallWatchdog::class.java.declaredFields
        assertTrue("the watchdog declares its ports as fields", fields.isNotEmpty())
        for (field in fields) {
            assertTrue("every field of the watchdog must be final", Modifier.isFinal(field.modifiers))
        }
    }
}
