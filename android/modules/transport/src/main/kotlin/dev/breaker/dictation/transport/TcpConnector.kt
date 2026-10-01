package dev.breaker.dictation.transport

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The result of one attempt to open a TCP connection to a resolved address.
 *
 * Only [CONNECTED] ever counts as reachable. The other three cases are all
 * failures of the same kind from a caller's point of view — the server did not
 * accept a connection in time — so they exist to keep a failure diagnosable
 * without widening the answer into a third state the domain would then have to
 * handle.
 */
internal enum class ProbeOutcome {
    /** The remote end accepted the connection. */
    CONNECTED,

    /** The remote end answered, but nothing is listening on that port. */
    REFUSED,

    /** The connection was not established before the timeout elapsed. */
    TIMED_OUT,

    /**
     * The address could not be reached at all: the name does not resolve, there
     * is no route to it, or the socket failed for some other I/O reason.
     */
    NO_NETWORK,
}

/**
 * Opens one TCP connection and reports whether it was accepted.
 *
 * This is the seam that keeps the platform's network stack out of the probe's
 * logic: everything above this interface can be exercised with a stub, and
 * [SocketTcpConnector] is the only production code in this module that touches
 * a socket.
 *
 * Implementations must not let an exception escape — "did not connect" is the
 * answer for every failure, so a throwing implementation would break the
 * caller's guarantee.
 */
internal interface TcpConnector {
    /**
     * Attempts a connection to [address] on [port], giving up after
     * [timeoutMillis].
     *
     * Returns true only when the connection was established within the
     * timeout; every other result is false. The timeout covers the connect
     * call itself — name resolution happens before this method is called and is
     * therefore not inside [timeoutMillis].
     */
    fun connect(address: InetAddress, port: Int, timeoutMillis: Int): Boolean
}

/**
 * Turns a host name into the addresses it currently points at.
 *
 * A host that is already an address literal never reaches this seam, so a
 * literal probe never consults a resolver at all.
 */
internal fun interface HostResolver {
    /**
     * Returns every address [host] resolves to, in the resolver's preference
     * order, or an empty list when the name does not resolve.
     */
    fun resolve(host: String): List<InetAddress>
}

/**
 * The production connector: one short-lived TCP connection over a real socket.
 *
 * Every failure is folded into a single [ProbeOutcome] and the socket is always
 * closed, including on the success path — an unreleased socket is a leaked file
 * descriptor, and a reachability probe is exactly the kind of code that runs
 * often enough for that to matter.
 */
internal object SocketTcpConnector : TcpConnector {

    override fun connect(address: InetAddress, port: Int, timeoutMillis: Int): Boolean =
        attempt(address, port, timeoutMillis.coerceAtLeast(1)) == ProbeOutcome.CONNECTED

    /**
     * Classifies a single connection attempt. Exhaustive rather than folded into
     * a bare catch-all so that an unexpected I/O failure is deliberately a
     * [ProbeOutcome.NO_NETWORK] and can never be mistaken for a success.
     */
    private fun attempt(address: InetAddress, port: Int, timeoutMillis: Int): ProbeOutcome {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            ProbeOutcome.CONNECTED
        } catch (_: SocketTimeoutException) {
            ProbeOutcome.TIMED_OUT
        } catch (_: ConnectException) {
            ProbeOutcome.REFUSED
        } catch (_: UnknownHostException) {
            ProbeOutcome.NO_NETWORK
        } catch (_: NoRouteToHostException) {
            ProbeOutcome.NO_NETWORK
        } catch (_: SocketException) {
            ProbeOutcome.NO_NETWORK
        } catch (_: IOException) {
            ProbeOutcome.NO_NETWORK
        } finally {
            runCatching { socket.close() }
        }
    }
}

/**
 * The production resolver: the platform's own name lookup.
 *
 * A name that does not resolve yields an empty list rather than an exception,
 * so a missing entry in a local network is an ordinary "not reachable" answer
 * rather than an error the caller has to guard against.
 */
internal object SystemHostResolver : HostResolver {

    override fun resolve(host: String): List<InetAddress> = try {
        InetAddress.getAllByName(host).toList()
    } catch (_: UnknownHostException) {
        emptyList()
    }
}
