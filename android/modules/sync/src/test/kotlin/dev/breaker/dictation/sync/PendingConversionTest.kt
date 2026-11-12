package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SealedRecord
import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.port.RecordKeys
import dev.breaker.shared.api.SyncPush
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A [PendingSealedTransport] that counts its calls and answers from a script. */
internal class ScriptedPending(private val answer: () -> PullTransportResult) : PendingSealedTransport {

    /** How many times [fetch] was called. */
    var calls = 0
        private set

    override fun fetch(): PullTransportResult {
        calls++
        return answer()
    }
}

/** The three box bytes of row [n]: n, n + 1, n + 2, all non-zero, so a wipe is visible. */
internal fun boxBytes(n: Int): ByteArray = ByteArray(3) { (n + it).toByte() }

/** The base64 text of [boxBytes]. */
internal fun boxText(n: Int): String = Base64.getEncoder().encodeToString(boxBytes(n))

/**
 * A [QueuedSyncService] with a port and a pending transport over an empty queue. The server holds the
 * rows numbered in [ids] (client id = clientId(n), box = boxBytes(n)) except those in [gone], which a
 * test fills to say the server dropped them after a confirmed upload. [answerOverride] replaces the
 * whole answer.
 */
internal class PendingRig(wrap: (FakeRecordKeys) -> RecordKeys = { it }) {
    val log = CallLog()
    val fake = FakeRecordKeys(log)
    val uploader = CountingUploader(log)
    val database = InMemorySyncQueueDatabase(log)
    var ids: List<Int> = emptyList()
    val gone = HashSet<String>()
    var answerOverride: (() -> PullTransportResult)? = null
    val pending = ScriptedPending { answerOverride?.invoke() ?: PullTransportResult.Answered(200, listing()) }
    val service = QueuedSyncService(
        database = database,
        uploader = uploader,
        probe = ScriptedProbe(true, log),
        recordKeys = wrap(fake),
        pendingTransport = pending,
    )

    private fun listing(): String =
        pendingList(ids.filter { clientId(it) !in gone }.map { pendingRow(clientId(it), boxText(it)) })

    /** One drain. */
    fun drain(): SyncReport = service.pushPending()

    /** The pending numbers; fails the test when no conversion is built. */
    fun counts(): PendingCounts = service.pendingCounts()
        ?: throw AssertionError("android_sync: the service built no pending conversion")

    /** The client ids the port was asked to convert, in order. */
    fun convertedIds(): List<String> = fake.convertCalls.map { it.clientId }

    /** The client ids of the pushes the uploader received, in order. */
    fun uploadedIds(): List<String> = uploader.pushes.map { it.clientId }
}

/** The pending conversion through the service: what is converted, skipped, bounded and refused. */
class PendingConversionTest {

    /** A port that refuses to convert the listed ids and answers the double's frame for every other. */
    private class SelectiveKeys(private val inner: FakeRecordKeys, private val bad: Set<String>) :
        RecordKeys by inner {
        override fun convertSealed(clientId: String, sealedBox: ByteArray): SealedRecord? {
            val answer = inner.convertSealed(clientId, sealedBox)
            return if (clientId in bad) null else answer
        }
    }

    /** A failure here means a row is not converted at the current version or not pushed as a new record. */
    @Test
    fun `two rows are converted at the current version 2 and pushed as new records`() {
        val rig = PendingRig()
        rig.fake.version = 2
        rig.ids = listOf(1, 2)

        val report = rig.drain()

        assertEquals("android_sync: the drain report is unchanged", SyncReport(0, 0, 0), report)
        assertEquals(
            "android_sync: the port got the id and the decoded box bytes of each row",
            listOf(
                ConvertCall(clientId(1), boxBytes(1).toList()),
                ConvertCall(clientId(2), boxBytes(2).toList()),
            ),
            rig.fake.convertCalls,
        )
        assertEquals(
            "android_sync: both are pushed as new records at version 2 with the port's text",
            listOf(
                SyncPush(clientId(1), 2, rig.fake.frameFor(clientId(1), 2), null),
                SyncPush(clientId(2), 2, rig.fake.frameFor(clientId(2), 2), null),
            ),
            rig.uploader.pushes,
        )
        val counts = rig.counts()
        assertEquals("android_sync: two converted", 2, counts.converted)
        assertEquals("android_sync: none unconvertible", 0, counts.unconvertible)
        assertEquals("android_sync: none failed", 0, counts.failed)
        assertNull("android_sync: no retry-after", counts.retryAfterSeconds)
    }

    /** A failure here means a row whose id waits in the queue is converted (a local edit must win). */
    @Test
    fun `a queued id is skipped and the others are converted`() {
        val keys = FakeRecordKeys()
        keys.version = 2
        val queue = RecordingQueueProbe()
        queue.queued.add(clientId(2))
        val uploader = CountingUploader()
        val body = pendingList((1..3).map { pendingRow(clientId(it), boxText(it)) })
        val transport = ScriptedPending { PullTransportResult.Answered(200, body) }

        PendingConversion(transport, queue, uploader, keys, RefusedKeyVersion()).pass()

        assertEquals(
            "android_sync: the port is not asked about the queued id",
            listOf(clientId(1), clientId(3)),
            keys.convertCalls.map { it.clientId },
        )
        assertEquals(
            "android_sync: only those two are uploaded",
            listOf(clientId(1), clientId(3)),
            uploader.pushes.map { it.clientId },
        )
    }

    /** A failure here means the cap is not 50 attempts per pass, or the next drain does not do the rest. */
    @Test
    fun `51 rows take two drains, 50 then the last one`() {
        val rig = PendingRig()
        rig.fake.version = 1
        rig.ids = (1..51).toList()
        val firstFifty = (1..50).map { clientId(it) }

        rig.drain()

        assertEquals("android_sync: the first drain uploads exactly 50", firstFifty, rig.uploadedIds())
        assertEquals("android_sync: 50 converted", 50, rig.counts().converted)
        rig.gone.addAll(firstFifty)
        rig.drain()
        assertEquals(
            "android_sync: the second drain uploads the 51st only",
            firstFifty + clientId(51),
            rig.uploadedIds(),
        )
        assertEquals("android_sync: 51 converted", 51, rig.counts().converted)
        assertEquals("android_sync: the port was asked once per row", 51, rig.fake.convertCalls.size)
    }

    /** A failure here means 50 unconvertible rows at the head starve the row behind them. */
    @Test
    fun `50 unconvertible rows do not starve the good row behind them`() {
        val bad = (1..50).map { clientId(it) }.toSet()
        val rig = PendingRig { SelectiveKeys(it, bad) }
        rig.fake.version = 2
        rig.ids = (1..51).toList()

        rig.drain()

        assertEquals("android_sync: 50 unconvertible, counted once", 50, rig.counts().unconvertible)
        assertEquals("android_sync: nothing converted yet", 0, rig.counts().converted)
        assertEquals("android_sync: the port was asked about the first 50", bad.toList(), rig.convertedIds())
        assertEquals("android_sync: nothing uploaded", emptyList<SyncPush>(), rig.uploader.pushes)

        rig.drain()

        assertEquals("android_sync: the good row is converted in the second drain", 1, rig.counts().converted)
        assertEquals("android_sync: still 50 unconvertible, none again", 50, rig.counts().unconvertible)
        assertEquals("android_sync: only the 51st is uploaded", listOf(clientId(51)), rig.uploadedIds())
        assertEquals(
            "android_sync: the port was asked once per row, the 50 not asked again",
            (1..51).map { clientId(it) },
            rig.convertedIds(),
        )
    }

    /** A failure here means a new key version does not make the port look at the unconvertible rows again. */
    @Test
    fun `after a key version change the unconvertible rows are asked of the port again`() {
        val bad = setOf(clientId(1), clientId(2))
        val rig = PendingRig { SelectiveKeys(it, bad) }
        rig.fake.version = 2
        rig.ids = listOf(1, 2)
        rig.drain()
        rig.drain()
        val once = listOf(clientId(1), clientId(2))
        assertEquals("android_sync: asked once each so far", once, rig.convertedIds())

        rig.fake.version = 3
        rig.drain()

        assertEquals(
            "android_sync: asked again at version 3",
            listOf(clientId(1), clientId(2), clientId(1), clientId(2)),
            rig.convertedIds(),
        )
        assertEquals("android_sync: counted again", 4, rig.counts().unconvertible)
    }

    /** A failure here means the array handed to the port is not wiped after the call. */
    @Test
    fun `the box array handed to the port is zeroed after the call`() {
        val rig = PendingRig()
        rig.fake.version = 2
        rig.ids = listOf(1, 2)

        rig.drain()

        assertEquals("android_sync: the port got two arrays", 2, rig.fake.convertedBoxes.size)
        for ((index, box) in rig.fake.convertedBoxes.withIndex()) {
            val seen = rig.fake.convertCalls[index].boxCopy
            assertTrue("android_sync: control, the port saw non-zero bytes", seen.any { it != 0.toByte() })
            assertEquals("android_sync: the array is all zero", listOf<Byte>(0, 0, 0), box.toList())
        }
    }

    // Permitted re-point (row-level skip ruling): only structural bodies stay here; the two bodies with
    // a bad row moved to PendingConversionSkipTest, where the bad row is skipped and the good ones convert.
    /** A failure here means a bad list converts a row, calls the port, uploads, or is not counted once. */
    @Test
    fun `a bad list counts one failure and converts nothing, even with good rows in it`() {
        val good = pendingRow(clientId(1), boxText(1))
        val bodies = listOf(
            "this is not json",
            "{\"items\":[]}",
            "[" + good,
            "[" + good + "," + "[".repeat(100000),
            "[]" + " ".repeat(PendingSealedDecoder.MAX_BODY_CHARS - 1),
        )
        for (body in bodies) {
            val rig = PendingRig()
            rig.fake.version = 2
            rig.answerOverride = { PullTransportResult.Answered(200, body) }

            val report = rig.drain()

            assertEquals("android_sync: the drain report is intact", SyncReport(0, 0, 0), report)
            assertEquals("android_sync: one failure", 1, rig.counts().failed)
            assertEquals("android_sync: nothing converted", 0, rig.counts().converted)
            assertEquals("android_sync: the port is not called", 0, rig.log.count("convert"))
            assertEquals("android_sync: nothing is uploaded", 0, rig.log.count("upload"))
        }
        val control = PendingRig()
        control.fake.version = 2
        control.ids = listOf(1)
        control.drain()
        assertEquals("android_sync: control, a good list converts", 1, control.counts().converted)
    }

    /** A failure here means two rows with one id are not both tried (the server dedupes the pair). */
    @Test
    fun `a duplicate id in the list is tried twice`() {
        val rig = PendingRig()
        rig.fake.version = 2
        val row = pendingRow(clientId(1), boxText(1))
        rig.answerOverride = { PullTransportResult.Answered(200, pendingList(listOf(row, row))) }

        rig.drain()

        assertEquals("android_sync: both rows asked", listOf(clientId(1), clientId(1)), rig.convertedIds())
        assertEquals("android_sync: both uploaded", listOf(clientId(1), clientId(1)), rig.uploadedIds())
        assertEquals("android_sync: two converted", 2, rig.counts().converted)
    }

    /** A failure here means the counts class holds a text, so an id or a refusal reason could leak. */
    @Test
    fun `the counts hold numbers only, whatever the refusal said`() {
        // Permitted re-point (row-level skip ruling): the counts gain the number skipped.
        assertEquals(
            "android_sync: the counts class has four Int fields and one Int? field, no text",
            listOf("int", "int", "int", "int", "java.lang.Integer"),
            PendingCounts::class.java.declaredFields.map { it.type.name }.sorted(),
        )
        val rig = PendingRig()
        rig.fake.version = 2
        rig.ids = listOf(1)
        rig.uploader.thenThrow(PermanentRefusalException(400, "bad body for ${clientId(1)}"))

        rig.drain()

        assertEquals("android_sync: the refusal is a number", 1, rig.counts().failed)
    }
}
