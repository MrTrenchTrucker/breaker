package dev.breaker.dictation.stt.server

import java.io.IOException
import javax.net.ssl.SSLException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * ExchangeFailure.isTls looks at the first eight exceptions of a cause chain and no further, so a cycle
 * cannot loop it. The tests put the TLS exception at the seventh, the eighth and the ninth place.
 */
class ExchangeFailureChainTest {

    /** A chain of [length] exceptions, outermost first, with the SSLException at [tlsAt] (0 is the outermost). */
    private fun chain(length: Int, tlsAt: Int): Throwable {
        var cause: Throwable? = null
        for (depth in length - 1 downTo 0) {
            cause = if (depth == tlsAt) SSLException("tls", cause) else IOException("plain", cause)
        }
        return cause ?: throw AssertionError("a chain needs at least one exception")
    }

    @Test
    fun `a TLS exception at the seventh place of a long chain is found`() {
        assertTrue("seventh place, chain of ten", ExchangeFailure.isTls(chain(10, 6)))
    }

    @Test
    fun `a TLS exception at the eighth place is still found`() {
        assertTrue("eighth place, chain of ten", ExchangeFailure.isTls(chain(10, 7)))
    }

    @Test
    fun `a TLS exception at the ninth place is not looked for`() {
        assertFalse("ninth place, chain of ten", ExchangeFailure.isTls(chain(10, 8)))
    }
}
