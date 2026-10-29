package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A background that holds each submitted block until the test runs it, so a build can be seen while it is in flight. */
private class HeldBuilds : Background {
    val pending = ArrayList<() -> Unit>()

    override fun submit(block: () -> Unit) {
        pending.add(block)
    }

    /** Runs the held blocks in the order they were submitted, on the test thread. */
    fun runAll() {
        while (pending.isNotEmpty()) pending.removeAt(0)()
    }
}

/** The provider over a model id the test changes, a build that records each id it is asked for, and streams it made. */
internal class PhraseStreamProviderTest {
    private var modelId: String = "small"
    private val builds = ArrayList<String>()
    private val streams = ArrayList<PhraseTestStream>()
    private val background = HeldBuilds()
    private var failNext: Boolean = false

    private fun provider(): PhraseStreamProvider =
        PhraseStreamProvider(
            background = background,
            modelId = { modelId },
            build = { id ->
                builds.add(id)
                if (failNext) {
                    failNext = false
                    null
                } else {
                    PhraseTestStream().also { streams.add(it) }
                }
            },
        )

    @Test
    fun `prepare hands the build to the background and builds once`() {
        val provider = provider()
        provider.prepare()
        assertEquals("app: prepare must submit one build to the background", 1, background.pending.size)
        assertEquals("app: prepare must not run the build on the caller", 0, builds.size)
        background.runAll()
        assertEquals("app: the one submitted build ran for the model id", listOf("small"), builds)
    }

    @Test
    fun `a second prepare while a build is in flight builds nothing`() {
        val provider = provider()
        provider.prepare()
        provider.prepare()
        assertEquals("app: a second prepare must not submit another build", 1, background.pending.size)
        background.runAll()
        assertEquals("app: only the first build ran", listOf("small"), builds)
    }

    @Test
    fun `a prepare with the stream for the current model id already held submits nothing`() {
        val provider = provider()
        provider.prepare()
        background.runAll()
        provider.prepare()
        assertEquals("app: a prepare with the stream held must submit no build", 0, background.pending.size)
        assertEquals("app: the held stream must not be built again", listOf("small"), builds)
    }

    @Test
    fun `ready is null before the build ran and the stream after`() {
        val provider = provider()
        assertNull("app: ready must be null before any prepare", provider.ready())
        provider.prepare()
        assertNull("app: ready must be null while the build is still queued", provider.ready())
        background.runAll()
        assertSame("app: ready must answer the stream the build made", streams.single(), provider.ready())
    }

    @Test
    fun `a failed build leaves ready null and the next prepare retries`() {
        val provider = provider()
        failNext = true
        provider.prepare()
        background.runAll()
        assertNull("app: a failed build must leave ready null", provider.ready())
        provider.prepare()
        assertEquals("app: the next prepare must submit a new build", 1, background.pending.size)
        background.runAll()
        assertSame("app: the retried build must make the stream", streams.single(), provider.ready())
        assertEquals("app: the failed build and the retry both asked for the model id", listOf("small", "small"), builds)
    }

    @Test
    fun `a changed model id drops the old stream and builds the new one`() {
        val provider = provider()
        provider.prepare()
        background.runAll()
        val first = provider.ready()
        assertSame("app: the first build must answer ready", streams.single(), first)
        modelId = "base"
        assertNull("app: after the model id changes, ready must not answer the old stream", provider.ready())
        provider.prepare()
        background.runAll()
        assertSame("app: the new model id must answer its own stream", streams[1], provider.ready())
        assertEquals("app: the new model id was built", listOf("small", "base"), builds)
    }

    @Test
    fun `ready runs no build and answers while a build is queued`() {
        val provider = provider()
        provider.prepare()
        assertNull("app: ready must answer null and not wait while a build is queued", provider.ready())
        assertEquals("app: ready must not submit or run a build", 0, builds.size)
        assertEquals("app: ready must not submit a build of its own", 1, background.pending.size)
    }
}
