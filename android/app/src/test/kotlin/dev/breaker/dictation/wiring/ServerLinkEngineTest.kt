package dev.breaker.dictation.wiring

import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.stt.server.ServerLimits
import dev.breaker.dictation.stt.server.SttServerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The real server engine, reached through the root: its own sentences, its key and its address. */
class ServerLinkEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The engine's sentence for an address it refuses; the words are the ones the user sees. */
    private val addressSentence: String = "The server address must be an http or https address with no spaces or user name."

    /** The fixed sentences of a call that reached the network and then failed or timed out. */
    private val transportSentences: Set<String> = setOf(
        "The server did not answer in time.",
        "The connection to the server was lost.",
        "The server could not be reached.",
        "The server did not finish within the time allowed.",
    )

    // The engine used to refuse every http address before any connection. It now accepts the configured server's
    // address, so this test proves the engine dials it. The loopback listener never answers, so the engine is given
    // a small reply wait (the real limits would wait about ten seconds) and ends in a transport sentence. The proof
    // is that the listener saw a connection and that the result is not the address sentence. The number of
    // connections is not asserted: the platform connection class might retry, and that was not checked here.
    @Test
    fun `the server engine dials the configured http address on the loopback listener`() {
        val listener = ServerLinkListener()
        try {
            val mic = ScriptedMic()
            val root = BreakerCompositionRoot(
                filesDir = tmp.root,
                historyStore = RecordingHistoryStore(),
                serviceController = serverLinkArmed(),
                committer = FakeCommitter(),
                micSource = mic,
                serverEngineFactory = { url, _ -> SttServerEngine(url, { "test-key" }, ServerLimits(postReplyTimeoutMs = 200L)) },
            )
            root.settingsStore.save(AppSettings().copy(mode = SttMode.SERVER, serverUrl = "http://" + serverLinkLoopbackAddress.hostAddress + ":" + listener.port))
            val result = serverLinkOneTake(root) { awaitBounded("the capture to read twice", mic.secondReadEntered) }
            assertTrue("app: an http address should end in a failed take, got $result", result is FinishResult.Failed)
            val sentence: String = (result as FinishResult.Failed).sentence
            assertTrue("app: the configured http address must not be refused with the address sentence, got: $sentence", sentence != addressSentence)
            assertTrue("app: the failure should be a transport sentence, got: $sentence", sentence in transportSentences)
            assertTrue("app: the configured http address should have been dialled", listener.pending() >= 1)
        } finally {
            listener.close()
        }
    }

    // The address sentence now says an http or https address, because http is accepted. This test keeps that
    // exact sentence pinned for an address that is neither: another scheme is still refused and never dialled.
    @Test
    fun `an address of another scheme is refused by the server engine with its own sentence and no connection is made`() {
        val listener = ServerLinkListener()
        try {
            val mic = ScriptedMic()
            val root = BreakerCompositionRoot(
                filesDir = tmp.root,
                historyStore = RecordingHistoryStore(),
                serviceController = serverLinkArmed(),
                committer = FakeCommitter(),
                micSource = mic,
                serverEngineFactory = { url, _ -> SttServerEngine(url, { "test-key" }) },
            )
            root.settingsStore.save(AppSettings().copy(mode = SttMode.SERVER, serverUrl = "ftp://" + serverLinkLoopbackAddress.hostAddress + ":" + listener.port))
            val result = serverLinkOneTake(root) { awaitBounded("the capture to read twice", mic.secondReadEntered) }
            assertEquals(
                "app: an address of another scheme should fail with the engine's address sentence",
                FinishResult.Failed(addressSentence),
                result,
            )
            assertEquals("app: a refused address must not be dialled", 0, listener.pending())
        } finally {
            listener.close()
        }
    }

    @Test
    fun `the default root with no session answers the engine's key sentence and makes no connection`() {
        val listener = ServerLinkListener()
        try {
            val mic = ScriptedMic()
            val root = BreakerCompositionRoot(
                filesDir = tmp.root,
                historyStore = RecordingHistoryStore(),
                serviceController = serverLinkArmed(),
                committer = FakeCommitter(),
                micSource = mic,
            )
            root.settingsStore.save(AppSettings().copy(mode = SttMode.SERVER, serverUrl = "https://" + serverLinkLoopbackAddress.hostAddress + ":" + listener.port))
            val result = serverLinkOneTake(root) { awaitBounded("the capture to read twice", mic.secondReadEntered) }
            assertEquals(
                "app: with no session the default engine should answer its key sentence",
                FinishResult.Failed("The server key is not set."),
                result,
            )
            assertEquals("app: a missing key must not reach the network", 0, listener.pending())
        } finally {
            listener.close()
        }
    }

    @Test
    fun `the key is read on every call so a new session reaches the next take`() {
        var token: String? = "first-session"
        var keyEngine: ServerLinkKeyEngine? = null
        val mic = ServerLinkRepeatMic(takes = 2)
        val root = BreakerCompositionRoot(
            filesDir = tmp.root,
            historyStore = RecordingHistoryStore(),
            serviceController = serverLinkArmed(),
            committer = FakeCommitter(),
            micSource = mic,
            serverEngineFactory = { _, key ->
                val engine = ServerLinkKeyEngine(key)
                keyEngine = engine
                engine
            },
            sessionToken = { token },
        )
        root.settingsStore.save(AppSettings().copy(mode = SttMode.SERVER, serverUrl = "https://session.example.test"))
        serverLinkOneTake(root) { awaitBounded("the first capture to read twice", mic.enteredTake(0)) }
        root.dictation.runner.send()
        token = "second-session"
        serverLinkOneTake(root) { awaitBounded("the second capture to read twice", mic.enteredTake(1)) }
        root.dictation.runner.send()
        assertEquals(
            "app: the engine should read the session on each take, not keep the first one",
            listOf("first-session", "second-session"),
            checkNotNull(keyEngine).seen,
        )
    }

    @Test
    fun `the three-argument and the supplier constructors give the factory the stored address and the session seam`() {
        var threeAddress: (() -> String)? = null
        var threeKey: (() -> String?)? = null
        val three = BreakerCompositionRoot(
            filesDir = tmp.root,
            historyStore = RecordingHistoryStore(),
            committer = FakeCommitter(),
            serverEngineFactory = { url, key ->
                threeAddress = url
                threeKey = key
                ServerLinkEngine()
            },
            sessionToken = { "forwarded-session" },
        )
        three.settingsStore.save(AppSettings().copy(mode = SttMode.SERVER, serverUrl = "https://forwarded.example.test"))
        assertEquals("app: a new root should be idle", DictationState.IDLE, three.dictation.runner.sessionState)
        assertEquals(
            "app: the three-argument constructor should hand over the stored address",
            "https://forwarded.example.test",
            checkNotNull(threeAddress)(),
        )
        assertEquals(
            "app: the three-argument constructor should hand over the session seam",
            "forwarded-session",
            checkNotNull(threeKey)(),
        )

        var supplierAddress: (() -> String)? = null
        var supplierKey: (() -> String?)? = null
        val supplier = BreakerCompositionRoot(
            filesDir = tmp.newFolder("supplier"),
            history = { RecordingHistoryStore() },
            serviceController = serverLinkArmed(),
            committer = FakeCommitter(),
            serverEngineFactory = { url, key ->
                supplierAddress = url
                supplierKey = key
                ServerLinkEngine()
            },
            sessionToken = { "forwarded-session" },
        )
        supplier.settingsStore.save(AppSettings().copy(mode = SttMode.SERVER, serverUrl = "https://forwarded.example.test"))
        assertEquals("app: a new root should be idle", DictationState.IDLE, supplier.dictation.runner.sessionState)
        assertEquals(
            "app: the supplier constructor should hand over the stored address",
            "https://forwarded.example.test",
            checkNotNull(supplierAddress)(),
        )
        assertEquals(
            "app: the supplier constructor should hand over the session seam",
            "forwarded-session",
            checkNotNull(supplierKey)(),
        )
    }
}
