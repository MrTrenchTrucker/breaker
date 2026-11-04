package dev.breaker.server.syncapi.handoff

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a failing sweep is logged at WARN with the exception class name only - never the
 * message, because a store failure can carry a path or a key. This is the real RED test: it
 * asserts the class name is present AND the exception message is absent. On the old code
 * (nothing logged) there is no record at all, so the test fails; on the new code exactly one
 * WARN record carries "IllegalStateException" and not "the database is closed".
 */
internal class HandoffSweepLogTest : TempDatabaseTest() {

    private val clock = MutableClock(TokenFixtures.START)

    private val loggerName = "dev.breaker.server.syncapi.handoff"

    private fun world(): Pair<HandoffStore, TempDatabase> {
        val temp = openTemp()
        val tokenStore = TokenStore(temp.db, clock)
        val store = HandoffStore(temp.db, tokenStore, clock, java.security.SecureRandom())
        return store to temp
    }

    @Test
    fun `a failing sweep logs the exception class name but not its message`() {
        val (store, temp) = world()
        // Close the store: deleteExpired goes through db.transaction, which throws
        // IllegalStateException("sync-api: the database is closed") on the next call.
        temp.closeKeepingFiles()

        val records = ArrayList<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records.add(record)
            }
            override fun flush() {}
            override fun close() {}
            override fun isLoggable(record: LogRecord): Boolean = true
        }
        val logger = Logger.getLogger(loggerName)
        val oldLevel = logger.level
        logger.level = Level.ALL
        logger.addHandler(handler)
        try {
            runBlocking(SupervisorJob()) {
                val child = launch {
                    runHandoffSweep(store, pause = { throw CancellationException("test") })
                }
                try {
                    child.join()
                } catch (_: CancellationException) {
                    // expected: the loop ends
                }
            }
        } finally {
            logger.removeHandler(handler)
            logger.level = oldLevel
        }

        val warns = records.filter { it.level == Level.WARNING }
        assertEquals("sync-api: exactly one WARN record is logged for the failing sweep", 1, warns.size)
        val message = warns[0].message
        assertTrue(
            "sync-api: the log must carry the exception class name, was: $message",
            message.contains("IllegalStateException"),
        )
        assertFalse(
            "sync-api: the log must not carry the exception message (it can hold a path or key), was: $message",
            message.contains("the database is closed"),
        )
    }
}
