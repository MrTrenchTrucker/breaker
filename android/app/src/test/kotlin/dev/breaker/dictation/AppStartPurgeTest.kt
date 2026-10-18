package dev.breaker.dictation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartPurgeTest {

    @Test
    fun `purge runs exactly once on start`() {
        var calls = 0
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scheduleAppStartPurge(scope, purge = { calls++ }, onFailure = {})
        assertEquals("the purge must run exactly once on start", 1, calls)
    }

    @Test
    fun `purge runs off the calling thread`() {
        val caller = Thread.currentThread()
        val ranOn = CompletableDeferred<Thread>()
        val scope = CoroutineScope(Dispatchers.Default)
        scheduleAppStartPurge(scope, purge = { ranOn.complete(Thread.currentThread()) }, onFailure = {})
        val t = runBlocking { withTimeout(5_000) { ranOn.await() } }
        scope.cancel()
        assertNotSame("the purge must run off the calling thread", caller, t)
    }

    @Test
    fun `a failing purge is reported once and does not escape the scope`() {
        val failures = mutableListOf<Throwable>()
        val boom = RuntimeException("damaged database")
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scheduleAppStartPurge(scope, purge = { throw boom }, onFailure = { failures.add(it) })
        assertEquals("a failing purge must be reported exactly once", 1, failures.size)
        assertTrue("the reported failure must be the purge's exception", failures[0] === boom)
        assertTrue(
            "a handled failure must leave the scope's job completed, not cancelled",
            job.isCompleted && !job.isCancelled,
        )
    }

    @Test
    fun `a cancellation is not reported as a failure`() {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scheduleAppStartPurge(
            scope,
            purge = { throw CancellationException("scope cancelled") },
            onFailure = { failures.add(it) },
        )
        assertTrue("a cancellation must complete the job as cancelled", job.isCancelled)
        assertEquals("a cancellation must not be reported as a failure", 0, failures.size)
    }
}
