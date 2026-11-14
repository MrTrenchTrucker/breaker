package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * What the production connector makes of a real loopback connection.
 *
 * The probe above the connector sees only a boolean, so a refused connection and
 * an unreachable address look the same to every other test. The connector keeps
 * them apart on purpose, to keep a failure diagnosable, and these two cases pin
 * that on a real socket with no network and no wait: a listening port is
 * CONNECTED, and a port that was bound and then released is REFUSED. The
 * timed-out and no-route cases need a network that is not there, so they are
 * not covered here.
 */
class SocketTcpConnectorOutcomeTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Test
    fun `a port with a listener is connected`() {
        val listening = ServerSocket(0, 1, loopback)
        try {
            assertEquals(
                "transport: a connection to a listening loopback port must be CONNECTED",
                ProbeOutcome.CONNECTED,
                SocketTcpConnector.attempt(loopback, listening.localPort, 1000),
            )
        } finally {
            listening.close()
        }
    }

    @Test
    fun `a port that was released is refused and not read as no network`() {
        // Bind then release: the number is free a moment later, so the connect is
        // actively refused at once, with no wait and no dependence on the network.
        val released = ServerSocket(0, 1, loopback)
        val releasedPort = released.localPort
        released.close()
        assertEquals(
            "transport: a connection to a loopback port with no listener must be REFUSED",
            ProbeOutcome.REFUSED,
            SocketTcpConnector.attempt(loopback, releasedPort, 1000),
        )
    }
}
