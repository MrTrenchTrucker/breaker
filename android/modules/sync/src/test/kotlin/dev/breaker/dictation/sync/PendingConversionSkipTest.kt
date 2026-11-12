package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The row-level skip: a row that fails its own checks is skipped, left alone, counted once and shown
 * as a position, and a permanently refused row is not sent again until the key version changes.
 * A bad row is remembered by a fingerprint of its text and never by its id, so it cannot hide a good row.
 */
class PendingConversionSkipTest {

    private fun good(n: Int): String = pendingRow(clientId(n), boxText(n))

    /** A row with a usable id whose sealed box is not strict base64 (no padding). */
    private fun badBox(n: Int): String = pendingRow(clientId(n), "AQI")

    /** A rig at key version 2 whose server answers [text] on every fetch. */
    private fun rigOver(text: String): PendingRig {
        val rig = PendingRig()
        rig.fake.version = 2
        rig.answerOverride = { PullTransportResult.Answered(200, text) }
        return rig
    }

    private fun skips(rig: PendingRig): PendingSkips =
        rig.service.pendingSkips() ?: throw AssertionError("android_sync: no pending conversion is built")

    /** S1: failing here means a junk row stops the conversion of the rows before or after it. */
    @Test
    fun `valid rows before and after one junk row all convert`() {
        val rig = rigOver(pendingList(listOf(good(1), "123", good(3))))

        rig.drain()

        val both = listOf(clientId(1), clientId(3))
        assertEquals("android_sync: both good rows convert", both, rig.convertedIds())
        assertEquals("android_sync: both are uploaded", both, rig.uploadedIds())
        assertEquals("android_sync: two converted", 2, rig.counts().converted)
        assertEquals("android_sync: no failure", 0, rig.counts().failed)
    }

    /** S2: failing here means a junk row reaches the port or the uploader, or the server is touched. */
    @Test
    fun `a junk row causes no port call and no upload and the transport only fetches`() {
        val rig = rigOver(pendingList(listOf(badBox(1), good(2), "[]")))

        rig.drain()

        assertEquals("android_sync: the port gets the good row only", listOf(clientId(2)), rig.convertedIds())
        assertEquals("android_sync: only the good row is uploaded", listOf(clientId(2)), rig.uploadedIds())
        assertEquals("android_sync: one port call in all", 1, rig.log.count("convert"))
        assertEquals("android_sync: one upload in all", 1, rig.log.count("upload"))
        assertEquals("android_sync: the transport was asked to fetch once", 1, rig.pending.calls)
    }

    /** S3: failing here means the skipped count or the position report is wrong. */
    @Test
    fun `skipped is one and the skip report holds the position`() {
        val rig = rigOver(pendingList(listOf(good(1), badBox(2), good(3))))

        rig.drain()

        assertEquals("android_sync: one skipped", 1, rig.counts().skipped)
        assertEquals("android_sync: the position", listOf(1), skips(rig).indices)
        assertEquals("android_sync: the count", 1, skips(rig).count)
    }

    /** S4: failing here means a junk list is counted again on every drain, or reaches the port. */
    @Test
    fun `an all junk list converts nothing and its rows are counted once across drains`() {
        val rows = (1..5).map { badBox(it) } + listOf("1", "\"x\"", "{}", "[1]", "null")
        val rig = rigOver(pendingList(rows))

        for (drain in 1..3) {
            rig.drain()

            assertEquals("android_sync: ten skipped after drain $drain", 10, rig.counts().skipped)
            assertEquals("android_sync: the report keeps ten", 10, skips(rig).count)
            assertEquals("android_sync: the positions", (0 until 10).toList(), skips(rig).indices)
        }
        assertEquals("android_sync: nothing converted", 0, rig.counts().converted)
        assertEquals("android_sync: the port is never called", 0, rig.log.count("convert"))
        assertEquals("android_sync: nothing uploaded", 0, rig.log.count("upload"))
        assertEquals("android_sync: the list was fetched three times", 3, rig.pending.calls)
    }

    /** S5: failing here means a structural fault converts a row, is not one failure, or breaks the stack. */
    @Test
    fun `structural faults count one failure and convert nothing`() {
        val member15 = "{\"id\":" + "[".repeat(15) + "]".repeat(15) + "}"
        val bodies = listOf(
            "this is not json",
            "[" + good(1) + "] trailing",
            "x".repeat(PendingSealedDecoder.MAX_BODY_CHARS + 1),
            "{\"items\":[]}",
            "[" + good(1) + "," + member15 + "]",
            "[" + good(1) + ",{\"id\":" + "[".repeat(100000) + "]".repeat(100000) + "}]",
            "[" + good(1) + "," + "[".repeat(100000),
        )
        for (body in bodies) {
            val rig = rigOver(body)

            val report = rig.drain()

            assertEquals("android_sync: the drain report is intact", SyncReport(0, 0, 0), report)
            assertEquals("android_sync: one failure", 1, rig.counts().failed)
            assertEquals("android_sync: nothing converted", 0, rig.counts().converted)
            assertEquals("android_sync: nothing skipped", 0, rig.counts().skipped)
            assertEquals("android_sync: the port is not called", 0, rig.log.count("convert"))
            assertEquals("android_sync: nothing uploaded", 0, rig.log.count("upload"))
        }
    }

    /** S5: failing here means the depth limit refuses 16 levels, which the contract allows. */
    @Test
    fun `a nesting of exactly 16 levels is accepted and the row is skipped`() {
        val member14 = "{\"id\":" + "[".repeat(14) + "]".repeat(14) + "}"
        val rig = rigOver("[" + good(1) + "," + member14 + "]")

        rig.drain()

        assertEquals("android_sync: no failure", 0, rig.counts().failed)
        assertEquals("android_sync: the good row converts", 1, rig.counts().converted)
        assertEquals("android_sync: the deep row is skipped", listOf(1), skips(rig).indices)
    }

    /** S6: failing here means a permanently refused row is retried, or never retried after a key change. */
    @Test
    fun `a 400 row is not retried next drain and is retried after a key version change`() {
        val rig = PendingRig()
        rig.fake.version = 2
        rig.ids = listOf(1)
        rig.uploader.thenThrow(PermanentRefusalException(400, "bad body"))

        rig.drain()
        rig.drain()

        assertEquals("android_sync: one upload only", listOf(clientId(1)), rig.uploadedIds())
        assertEquals("android_sync: one failure counted", 1, rig.counts().failed)
        assertEquals("android_sync: the port was asked once", 1, rig.convertedIds().size)

        rig.fake.version = 3
        rig.drain()

        val twice = listOf(clientId(1), clientId(1))
        assertEquals("android_sync: retried after the key change", twice, rig.uploadedIds())
        assertEquals("android_sync: the retry converted it", 1, rig.counts().converted)
    }

    /** S6: failing here means a transient outcome marks a row, so it is never tried again. */
    @Test
    fun `a 429, a 500, FAILED and UNREACHABLE mark nothing and the row is retried next drain`() {
        val steps = listOf<(PendingRig) -> Unit>(
            { it.uploader.thenThrow(PermanentRefusalException(429, "rate", retryAfterSeconds = 3)) },
            { it.uploader.thenThrow(PermanentRefusalException(500, "server")) },
            { it.uploader.script(UploadOutcome.FAILED) },
            { it.uploader.script(UploadOutcome.UNREACHABLE) },
            { it.uploader.thenThrow(IllegalStateException("boom")) },
        )
        for (step in steps) {
            val rig = PendingRig()
            rig.fake.version = 2
            rig.ids = listOf(1)
            step(rig)

            rig.drain()
            rig.drain()

            assertEquals("android_sync: tried again", listOf(clientId(1), clientId(1)), rig.uploadedIds())
            assertEquals("android_sync: converted the second time", 1, rig.counts().converted)
        }
    }

    /** S6: failing here means a 413 does not mark the row like a 400. */
    @Test
    fun `a 413 row is not retried next drain`() {
        val rig = PendingRig()
        rig.fake.version = 2
        rig.ids = listOf(1)
        rig.uploader.thenThrow(PermanentRefusalException(413, "too large"))

        rig.drain()
        rig.drain()

        assertEquals("android_sync: one upload only", listOf(clientId(1)), rig.uploadedIds())
    }

    /** S7: failing here means a junk row without a usable id, or with one, is counted on every drain. */
    @Test
    fun `junk rows with and without a usable id are counted once across three drains`() {
        val rig = rigOver(pendingList(listOf("\"junk\"", "{\"x\":\"y\"}", badBox(7), good(1))))

        for (drain in 1..3) {
            rig.drain()

            assertEquals("android_sync: three skipped after drain $drain", 3, rig.counts().skipped)
            assertEquals("android_sync: the report holds three", listOf(0, 1, 2), skips(rig).indices)
        }
        assertEquals("android_sync: the good row is tried each drain", 3, rig.convertedIds().size)
        val asked = rig.convertedIds().toSet()
        assertEquals("android_sync: the junk ids never reach the port", setOf(clientId(1)), asked)
    }

    /** S7b: failing here means a junk row hides a good row that has the same id. */
    @Test
    fun `a junk row never hides a good row with the same id, in either order`() {
        val junkFirst = pendingList(listOf(pendingRow(clientId(5), "AQI"), good(5)))
        val goodFirst = pendingList(listOf(good(5), pendingRow(clientId(5), "AQI")))
        for (body in listOf(junkFirst, goodFirst)) {
            val rig = rigOver(body)

            rig.drain()
            rig.drain()

            val twice = listOf(clientId(5), clientId(5))
            assertEquals("android_sync: the good row converts", twice, rig.convertedIds())
            assertEquals("android_sync: converted each drain", 2, rig.counts().converted)
            assertEquals("android_sync: the junk row counts once", 1, rig.counts().skipped)
        }
    }

    /** S8: failing here means the fingerprint set is unbounded or the cap is not 10000 entries. */
    @Test
    fun `the fingerprint set holds 10000 and the 10001st junk row is counted again each drain`() {
        val withIds = pendingList((1..10001).map { badBox(it) })
        val withoutIds = pendingList((1..10001).map { "\"junk $it\"" })
        for (body in listOf(withIds, withoutIds)) {
            val rig = rigOver(body)

            rig.drain()
            assertEquals("android_sync: all counted in the first pass", 10001, rig.counts().skipped)
            rig.drain()
            assertEquals("android_sync: only the unremembered row grows", 10002, rig.counts().skipped)
            rig.drain()
            assertEquals("android_sync: again exactly one", 10003, rig.counts().skipped)

            assertEquals("android_sync: the report holds every row", 10001, skips(rig).count)
            assertEquals("android_sync: the port is never called", 0, rig.log.count("convert"))
        }
    }

    /** S8: failing here means the bad id set is unbounded or the cap is not 10000 entries. */
    @Test
    fun `the bad id set holds 10000 and the 10001st unconvertible row is asked again`() {
        val rig = rigOver(pendingList((1..10001).map { good(it) }))
        rig.fake.canConvert = false

        repeat(200) { rig.drain() }
        assertEquals("android_sync: 10000 unconvertible in 200 passes", 10000, rig.counts().unconvertible)
        rig.drain()
        assertEquals("android_sync: the 10001st is counted", 10001, rig.counts().unconvertible)
        rig.drain()
        assertEquals("android_sync: it is asked again, only it", 10002, rig.counts().unconvertible)
        assertEquals("android_sync: it is the only port call", clientId(10001), rig.convertedIds().last())
        assertEquals("android_sync: one port call per row and one more", 10002, rig.convertedIds().size)
    }

    /** S9: failing here means a key version change does not clear the remembered junk rows. */
    @Test
    fun `a key version change makes the junk rows count again`() {
        val rig = rigOver(pendingList(listOf(badBox(1), "7", good(2))))

        rig.drain()
        rig.drain()
        assertEquals("android_sync: two skipped, once", 2, rig.counts().skipped)

        rig.fake.version = 3
        rig.drain()

        assertEquals("android_sync: counted again at the new version", 4, rig.counts().skipped)
        rig.drain()
        assertEquals("android_sync: and once more only", 4, rig.counts().skipped)
    }

    /** S10: failing here means the counts or the skip report can carry a text or an id. */
    @Test
    fun `the skip report and the counts carry no text`() {
        assertEquals(
            "android_sync: the skip report is one Int and one List",
            listOf("int", "java.util.List"),
            PendingSkips::class.java.declaredFields.map { it.type.name }.sorted(),
        )
        assertEquals(
            "android_sync: the counts are four Ints and one Int?",
            listOf("int", "int", "int", "int", "java.lang.Integer"),
            PendingCounts::class.java.declaredFields.map { it.type.name }.sorted(),
        )
        val rig = rigOver(pendingList(listOf("\"SECRETMARKER\"", badBox(1))))
        rig.drain()
        assertEquals("android_sync: positions only", listOf(0, 1), skips(rig).indices)
        assertNotSame("android_sync: a fresh copy on every read", skips(rig), skips(rig))
        val noPort = QueuedSyncService(
            database = InMemorySyncQueueDatabase(),
            uploader = CountingUploader(),
            probe = ScriptedProbe(true),
        )
        assertNull("android_sync: no conversion, no report", noPort.pendingSkips())
    }
}
