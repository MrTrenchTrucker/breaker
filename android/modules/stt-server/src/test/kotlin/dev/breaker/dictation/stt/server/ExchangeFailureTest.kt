package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * One test per row of the table in [ExchangeFailure.classify]. Every row is built from a real
 * exception instance. Each expectation names the error kind and the fixed sentence.
 */
class ExchangeFailureTest {

    private fun failureOf(e: Throwable): SttResult.Failure {
        val result = ExchangeFailure.classify(e)
        assertTrue("the classification is not a failure", result is SttResult.Failure)
        return result as SttResult.Failure
    }

    @Test
    fun `a reply over the size cap is OTHER with the bad answer sentence`() {
        val f = failureOf(ReplyTooLargeException())
        assertEquals(SttError.OTHER, f.error)
        assertEquals(FailureSentences.BAD_ANSWER, f.detail)
    }

    @Test
    fun `a size-cap error is not an IO failure`() {
        val e: Throwable = ReplyTooLargeException()
        assertFalse("the size-cap error is an IOException", e is IOException)
    }

    @Test
    fun `a connect failure without a TLS cause is SERVER_UNREACHABLE with the unreachable sentence`() {
        val f = failureOf(ConnectFailedException(null))
        assertEquals(SttError.SERVER_UNREACHABLE, f.error)
        assertEquals(FailureSentences.UNREACHABLE, f.detail)
    }

    @Test
    fun `a refused connection is a connect failure without a TLS cause`() {
        val f = failureOf(ConnectFailedException(ConnectException("connection refused")))
        assertEquals(SttError.SERVER_UNREACHABLE, f.error)
        assertEquals(FailureSentences.UNREACHABLE, f.detail)
    }

    @Test
    fun `a connect timeout is a connect failure and not a read timeout`() {
        val f = failureOf(ConnectFailedException(SocketTimeoutException("connect timed out")))
        assertEquals(SttError.SERVER_UNREACHABLE, f.error)
        assertEquals(FailureSentences.UNREACHABLE, f.detail)
    }

    @Test
    fun `a connect failure caused by a TLS error is SERVER_UNREACHABLE with the TLS sentence`() {
        val f = failureOf(ConnectFailedException(SSLHandshakeException("handshake failed")))
        assertEquals(SttError.SERVER_UNREACHABLE, f.error)
        assertEquals(FailureSentences.TLS_FAILED, f.detail)
    }

    @Test
    fun `a read timeout is TIMEOUT with the no-answer sentence`() {
        val f = failureOf(SocketTimeoutException("read timed out"))
        assertEquals(SttError.TIMEOUT, f.error)
        assertEquals(FailureSentences.NO_ANSWER_IN_TIME, f.detail)
    }

    @Test
    fun `a bare TLS exception is SERVER_UNREACHABLE with the TLS sentence`() {
        val f = failureOf(SSLException("tls failed"))
        assertEquals(SttError.SERVER_UNREACHABLE, f.error)
        assertEquals(FailureSentences.TLS_FAILED, f.detail)
    }

    @Test
    fun `any other IO failure is SERVER_UNREACHABLE with the connection lost sentence`() {
        val f = failureOf(IOException("reset by peer"))
        assertEquals(SttError.SERVER_UNREACHABLE, f.error)
        assertEquals(FailureSentences.CONNECTION_LOST, f.detail)
    }

    @Test
    fun `the connection lost detail is the fixed sentence even when the exception text names a header`() {
        val f = failureOf(IOException("header Authorization was echoed"))
        assertEquals(FailureSentences.CONNECTION_LOST, f.detail)
    }

    @Test
    fun `a failure that is not an IO failure is OTHER with the unexpected sentence`() {
        val f = failureOf(IllegalStateException("unexpected state"))
        assertEquals(SttError.OTHER, f.error)
        assertEquals(FailureSentences.UNEXPECTED, f.detail)
    }

    @Test
    fun `a copied TLS connect failure, with the original one level down, is TLS_FAILED`() {
        val f = failureOf(ConnectFailedException(ConnectFailedException(SSLHandshakeException("h"))))
        assertEquals(SttError.SERVER_UNREACHABLE, f.error)
        assertEquals(FailureSentences.TLS_FAILED, f.detail)
    }

    @Test
    fun `an IO failure with a TLS cause is SERVER_UNREACHABLE with the TLS sentence`() {
        val f = failureOf(IOException("io", SSLException("t")))
        assertEquals(SttError.SERVER_UNREACHABLE, f.error)
        assertEquals(FailureSentences.TLS_FAILED, f.detail)
    }

    @Test
    fun `an IO failure whose cause is a plain IO failure is still the connection lost sentence`() {
        val f = failureOf(IOException("io", IOException("inner")))
        assertEquals(SttError.SERVER_UNREACHABLE, f.error)
        assertEquals(FailureSentences.CONNECTION_LOST, f.detail)
    }

    @Test
    fun `isTls is false for a connect failure with no cause`() {
        assertFalse("a connect failure with no cause is not TLS", ExchangeFailure.isTls(ConnectFailedException(null)))
    }
}
