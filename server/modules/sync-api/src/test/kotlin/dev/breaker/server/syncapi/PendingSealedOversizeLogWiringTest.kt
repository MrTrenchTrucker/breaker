package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.db.MAX_SEALED_BYTES
import dev.breaker.server.syncapi.db.PendingSealedStore
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.Base64
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// The oversize line of the pending-sealed intake is written through the internal module, not only by the route: a
// sealed copy over MAX_SEALED_BYTES posted to internalModule with a real store leaves exactly one log record that
// names the account id and the size and holds no part of the content. Without the callback wired in
// SyncApiInternal.kt the answer is still 413 and no record is written, so only this test sees the gap.
internal class PendingSealedOversizeLogWiringTest : TempDatabaseTest() {

    private class Collector : Handler() {
        val records = ArrayList<LogRecord>()
        override fun publish(record: LogRecord) {
            records.add(record)
        }
        override fun flush() {}
        override fun close() {}
    }

    @Test
    fun `an oversize sealed copy through the internal module writes one log line with the account id and the size only`() {
        val temp = openTemp()
        val tokens = TokenFixtures.tokenStore(temp, MutableClock(TokenFixtures.START))
        val account = TokenFixtures.register(temp, "alice", 1).accountId
        val sealed = Base64.getEncoder().encodeToString(ByteArray(MAX_SEALED_BYTES + 1) { (it % 251).toByte() })
        val body = """{"account_id":$account,"job_id":"00000000-0000-0000-0000-000000000001","sealed_b64":"$sealed"}"""
        val logger = Logger.getLogger("dev.breaker.server.syncapi.pendingsealed")
        val collector = Collector()
        logger.addHandler(collector)
        var status: HttpStatusCode? = null
        var answer = ""
        try {
            runBlocking<Unit> {
                testApplication {
                    application { internalModule(tokens, "test-key", pendingSealedStore = PendingSealedStore(temp.db)) }
                    val response = client.post("/internal/v1/pending-sealed") {
                        header("X-Service-Key", "test-key")
                        setBody(body)
                    }
                    status = response.status
                    answer = response.bodyAsText()
                }
            }
        } finally {
            logger.removeHandler(collector)
        }
        assertEquals("sync-api: status", HttpStatusCode.PayloadTooLarge, status)
        assertEquals("sync-api: one log record", 1, collector.records.size)
        assertEquals(
            "sync-api: the log line",
            "sync-api: pending-sealed intake refused a sealed copy over the limit: account $account, ${MAX_SEALED_BYTES + 1} bytes",
            collector.records.single().message,
        )
        assertFalse("sync-api: no content in the log line", collector.records.single().message.contains(sealed.take(40)))
        assertFalse("sync-api: no content in the answer", answer.contains(sealed.take(40)))
    }
}
