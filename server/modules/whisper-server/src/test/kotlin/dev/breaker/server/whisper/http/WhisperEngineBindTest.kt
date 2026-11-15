package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.MutableClock
import io.ktor.server.engine.EmbeddedServer
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The engine is started on the address the configuration names, not on a fixed loopback one. The
 * bind address is the network rule (a VPN or private address, never a public one), so a start that
 * ignored the host it was given would serve on an address nobody configured.
 */
internal class WhisperEngineBindTest {

    private val opened = ArrayList<TempDatabase>()
    private val servers = ArrayList<EmbeddedServer<*, *>>()

    @After
    fun tearDown() {
        for (server in servers) {
            try {
                server.stop(1000, 5000)
            } catch (_: Exception) {
                // already stopped
            }
        }
        for (temp in opened) {
            temp.close()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { socket -> socket.localPort }

    private fun api(): TranscriptionApi {
        val temp = TestDatabases.openTemp()
        opened.add(temp)
        val store = JobFixtures.store(temp, MutableClock(JobFixtures.START))
        return TranscriptionApi(store, {})
    }

    @Test
    fun `the engine binds the host it is given and not a fixed loopback address`(): Unit = runBlocking {
        val port = freePort()
        val server = WhisperEngine.start(api(), "127.0.0.2", port) {}
        servers.add(server)

        val configured = server.engineConfig.connectors.map { connector -> connector.host to connector.port }
        assertEquals(
            "whisper-server: the engine must be configured on the host it was given",
            listOf("127.0.0.2" to port),
            configured,
        )
        val resolved = server.engine.resolvedConnectors().map { connector -> connector.host to connector.port }
        assertEquals(
            "whisper-server: the engine must bind the host it was given",
            listOf("127.0.0.2" to port),
            resolved,
        )
    }
}
