package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AuthHttpRequest
import dev.breaker.dictation.http.HttpRefusal
import java.io.IOException
import java.net.ServerSocket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The login transport tells the saved address apart from every other plain http address, with no
 * network: a loopback port that was opened and closed again refuses the connect on this machine.
 */
class AuthServiceTransportTest {
    private fun closedPort(): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    /** Sends one POST to [url] through a transport whose saved address is [saved]; gives what it threw. */
    private fun thrownBy(saved: String, url: String): IOException {
        val http = defaultAuthHttp { saved }
        try {
            val headers = listOf("Content-Type" to "application/json")
            http.send(AuthHttpRequest("POST", url, headers, ByteArray(0)))
        } catch (failure: IOException) {
            return failure
        }
        throw AssertionError("expected an IOException, the closed loopback port cannot answer")
    }

    @Test
    fun `a plain http request to the saved address is tried and not refused`() {
        val port = closedPort()
        val saved = "http://127.0.0.1:$port"

        val thrown = thrownBy(saved, "$saved/v1/auth/salt")

        assertFalse("the saved address must not be refused as plain http", thrown is HttpRefusal)
    }

    @Test
    fun `a plain http request to another host is refused before any connect`() {
        val port = closedPort()
        val saved = "http://127.0.0.1:$port"

        val thrown = thrownBy(saved, "http://other.invalid:$port/v1/auth/salt")

        assertTrue("another plain http host must be refused", thrown is HttpRefusal)
    }

    @Test
    fun `a plain http request to another port of the saved host is refused before any connect`() {
        val port = closedPort()
        val otherPort = if (port > 1) port - 1 else port + 1
        val saved = "http://127.0.0.1:$port"

        val thrown = thrownBy(saved, "http://127.0.0.1:$otherPort/v1/auth/salt")

        assertTrue("another port of the saved host must be refused", thrown is HttpRefusal)
    }

    @Test
    fun `an https request is tried and not refused as a scheme`() {
        val port = closedPort()
        val saved = "http://127.0.0.1:$port"

        val thrown = thrownBy(saved, "https://127.0.0.1:$port/v1/auth/salt")

        assertFalse("an https address must not be refused for its scheme", thrown is HttpRefusal)
    }

    @Test
    fun `the saved address is read on every request and not kept`() {
        val port = closedPort()
        var saved = "http://other.invalid:$port"
        val http = defaultAuthHttp { saved }
        val request = AuthHttpRequest(
            "POST",
            "http://127.0.0.1:$port/v1/auth/salt",
            listOf("Content-Type" to "application/json"),
            ByteArray(0),
        )

        val first = try {
            http.send(request)
            null
        } catch (failure: IOException) {
            failure
        }
        saved = "http://127.0.0.1:$port"
        val second = try {
            http.send(request)
            null
        } catch (failure: IOException) {
            failure
        }

        assertTrue("before the change the address is another host so it is refused", first is HttpRefusal)
        assertFalse("after the change the address is the saved one so it is tried", second is HttpRefusal)
        assertTrue("the second request must end in a connect failure", second != null)
    }
}
