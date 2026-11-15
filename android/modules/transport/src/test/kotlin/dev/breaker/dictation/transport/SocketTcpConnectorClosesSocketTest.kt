package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The production connector closes the socket it opened, also on the success path.
 *
 * The probe sees only a boolean, so a connector that returned true and left its
 * socket open would pass every other test and leak one descriptor per probe. On a
 * real loopback connection the server side can tell: the accepted socket reads
 * end of stream (-1) only once the client has closed its end. The read is bounded
 * by a short timeout, so a connector that never closes fails by timeout instead of
 * hanging.
 */
class SocketTcpConnectorClosesSocketTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Test
    fun `a connection that was accepted is closed by the connector before it returns`() {
        val listening = ServerSocket(0, 1, loopback)
        try {
            val connected = SocketTcpConnector.connect(loopback, listening.localPort, 1000)
            assertTrue("transport: a connection to a listening loopback port must be accepted", connected)
            listening.accept().use { accepted ->
                accepted.soTimeout = 2000
                assertEquals(
                    "transport: the connector must close its socket, so the server side reads end of stream",
                    -1,
                    accepted.getInputStream().read(),
                )
            }
        } finally {
            listening.close()
        }
    }
}
