package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SealedRecord
import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.port.RecordKeys
import dev.breaker.shared.api.SyncPush
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A [SyncUploader] for the migration tests. It has no call cap (the cap of [ScriptedUploader] is 50
 * calls, and one test here needs 51 uploads), records every push, and answers from a script, else
 * [UploadOutcome.CONFIRMED].
 */
internal class CountingUploader(private val log: CallLog = CallLog()) : SyncUploader {

    /** Every push received, oldest first. */
    val pushes = ArrayList<SyncPush>()
    private val steps = ArrayDeque<() -> UploadOutcome>()

    /** Queues [outcomes] as the answers of the next calls, in order. */
    fun script(vararg outcomes: UploadOutcome) {
        outcomes.forEach { outcome -> steps.addLast { outcome } }
    }

    /** Queues a call that throws [cause]. */
    fun thenThrow(cause: Exception) {
        steps.addLast { throw cause }
    }

    override fun upload(push: SyncPush): UploadOutcome {
        log.add("upload")
        pushes.add(push)
        val step = steps.removeFirstOrNull()
        return if (step == null) UploadOutcome.CONFIRMED else step()
    }

    override fun delete(clientId: String): UploadOutcome {
        log.add("delete")
        return UploadOutcome.CONFIRMED
    }
}

internal val MIGRATION_PLAIN: String = SyncBodyCodec.encodePlain(
    SyncBody("plain words", SyncSource.LOCAL, "small.en", 900, 1_700_000_000_000L),
)

/** One live record of a pull page, as the server writes it. */
internal fun migrationLive(clientId: String, change: Long, keyVersion: Int, encrypted: String): String =
    "{\"client_id\":\"$clientId\",\"change\":$change,\"deleted\":false,\"key_version\":$keyVersion," +
        "\"encrypted\":\"$encrypted\",\"stored_at\":\"2023-11-14T22:13:20.000Z\"}"

/** One delete record of a pull page. */
internal fun migrationDeleted(clientId: String, change: Long): String =
    "{\"client_id\":\"$clientId\",\"change\":$change,\"deleted\":true," +
        "\"stored_at\":\"2023-11-14T22:13:20.000Z\"}"

/** The last page of a listing, holding [items]. */
internal fun migrationPage(items: List<String>, next: String): String =
    "{\"items\":[${items.joinToString(",")}],\"next\":\"$next\",\"more\":false}"

/**
 * A [QueuedSyncService] with a pull transport, a pull sink and a port, and an empty queue.
 *
 * Requests per drain, from PullCycle and PullLoop: the first drain makes 2 pull requests (the list,
 * then the follow-up from the list's cursor) and the next drains make 1 (from the stored cursor);
 * a migration pass that lists adds 1 more. The transport answers every request with the one page
 * built from [items], whose `next` is "n" plus the request number (0 for the first request), and
 * it keeps the last answer, so the pull and the migration listing both see the same records.
 */
internal class MigrationRig(wrap: (FakeRecordKeys) -> RecordKeys = { it }) {
    val log = CallLog()
    val fake = FakeRecordKeys(log)
    val uploader = CountingUploader(log)
    val database = InMemorySyncQueueDatabase(log)
    var items: List<String> = emptyList()

    /** When set, request number 2 (the migration's listing in the first drain) gets this answer. */
    var listingFailure: (() -> PullTransportResult)? = null
    val transport = ScriptedPullTransport { n ->
        val broken = listingFailure
        if (n == 2 && broken != null) {
            broken()
        } else {
            PullTransportResult.Answered(200, migrationPage(items, "n$n"))
        }
    }
    val service = QueuedSyncService(
        database = database,
        uploader = uploader,
        probe = ScriptedProbe(true, log),
        pullTransport = transport,
        pullSink = RecordingPullSink(),
        recordKeys = wrap(fake),
    )

    /** One drain. */
    fun drain(): SyncReport = service.pushPending()

    /** The migration numbers; fails the test when there is no migration. */
    fun counts(): MigrationCounts = service.migrationCounts()
        ?: throw AssertionError("android_sync: the service built no migration")

    /** The client ids the port was asked to re-seal, in order. */
    fun resealedIds(): List<String> = fake.resealCalls.map { it.clientId }

    /** The client ids of the pushes the uploader received, in order. */
    fun uploadedIds(): List<String> = uploader.pushes.map { it.clientId }
}

/** The own-record migration through the service: what is re-sealed, what is left alone, the bound. */
class RecordMigrationTest {

    /** A port that refuses to re-seal one record and answers the double's frame for every other. */
    private class RefusingKeys(private val inner: FakeRecordKeys, private val refusedId: String) :
        RecordKeys by inner {
        override fun reseal(clientId: String, keyVersion: Int, encrypted: String): SealedRecord? {
            val answer = inner.reseal(clientId, keyVersion, encrypted)
            return if (clientId == refusedId) null else answer
        }
    }

    /** A failure here means a record below the version is not re-sealed, or not uploaded at the new one. */
    @Test
    fun `records at version 0 and 1 are re-sealed and uploaded at the current version 2`() {
        val rig = MigrationRig()
        rig.fake.version = 2
        rig.items = listOf(
            migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN),
            migrationLive(clientId(2), 2, 1, "frame-old"),
        )

        rig.drain()

        assertEquals(
            "android_sync: the port got the id, the stored version and the stored text of each record",
            listOf(ResealCall(clientId(1), 0, MIGRATION_PLAIN), ResealCall(clientId(2), 1, "frame-old")),
            rig.fake.resealCalls,
        )
        assertEquals(
            "android_sync: both records are pushed at version 2 with the port's text",
            listOf(
                SyncPush(clientId(1), 2, rig.fake.frameFor(clientId(1), 2), null),
                SyncPush(clientId(2), 2, rig.fake.frameFor(clientId(2), 2), null),
            ),
            rig.uploader.pushes,
        )
        val counts = rig.counts()
        assertEquals("android_sync: two replaced", 2, counts.replaced)
        assertEquals("android_sync: none unconvertible", 0, counts.unconvertible)
        assertEquals("android_sync: none failed", 0, counts.failed)
        assertNull("android_sync: no retry-after", counts.retryAfterSeconds)
    }

    /** A failure here means the pass touches a current, newer, deleted or queued record (class alone). */
    @Test
    fun `a current, a newer, a deleted and a queued record are left alone`() {
        val keys = FakeRecordKeys()
        keys.version = 2
        val queue = RecordingQueueProbe()
        queue.queued.add(clientId(5))
        val uploader = CountingUploader()
        val transport = ScriptedPullTransport(listOf(PullTransportResult.Answered(200, migrationPage(listOf(
            migrationLive(clientId(1), 1, 2, "frame-now"),
            migrationLive(clientId(2), 2, 3, "frame-newer"),
            migrationDeleted(clientId(3), 3),
            migrationLive(clientId(5), 4, 0, MIGRATION_PLAIN),
            migrationLive(clientId(6), 5, 1, "frame-old"),
        ), "n1"))))

        RecordMigration(transport, queue, uploader, keys, RefusedKeyVersion()).pass()

        assertEquals(
            "android_sync: only the record below the version that is not queued is re-sealed",
            listOf(clientId(6)),
            keys.resealCalls.map { it.clientId },
        )
        assertEquals(
            "android_sync: only that record is uploaded",
            listOf(clientId(6)),
            uploader.pushes.map { it.clientId },
        )
    }

    /** A failure here means an unconvertible record stops the pass, is dropped, or ends the walk clean. */
    @Test
    fun `a record the port cannot convert is counted and the next record still goes`() {
        val rig = MigrationRig { RefusingKeys(it, clientId(1)) }
        rig.fake.version = 2
        rig.items = listOf(
            migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN),
            migrationLive(clientId(2), 2, 0, MIGRATION_PLAIN),
        )

        rig.drain()

        val counts = rig.counts()
        assertEquals("android_sync: one unconvertible", 1, counts.unconvertible)
        assertEquals("android_sync: one replaced", 1, counts.replaced)
        assertEquals(
            "android_sync: the port was asked for both records",
            listOf(clientId(1), clientId(2)),
            rig.resealedIds(),
        )
        assertEquals(
            "android_sync: nothing is uploaded for the refused record",
            listOf(clientId(2)),
            rig.uploadedIds(),
        )
        val before = rig.transport.requests.size
        rig.drain()
        // Ruling: unconvertible and permanent problems do not keep the walk open.
        assertEquals(
            "android_sync: the walk ended, so pending is cleared and the next drain lists nothing",
            1,
            rig.transport.requests.size - before,
        )
    }

    /** A failure here means an unconvertible record keeps the walk open, or is uploaded after all. */
    @Test
    fun `after an unconvertible only walk the next two drains list nothing and upload nothing`() {
        val rig = unconvertibleRig()
        rig.drain()

        val first = rig.transport.requests.size
        rig.drain()
        assertEquals("android_sync: the second drain makes the pull's request only", 1,
            rig.transport.requests.size - first)
        val second = rig.transport.requests.size
        rig.drain()
        assertEquals("android_sync: the third drain makes the pull's request only", 1,
            rig.transport.requests.size - second)
        assertEquals("android_sync: nothing is uploaded", emptyList<SyncPush>(), rig.uploader.pushes)
        assertEquals("android_sync: the port was asked once", listOf(clientId(1)), rig.resealedIds())
    }

    /** A failure here means a new key version does not start a new walk after an unconvertible only walk. */
    @Test
    fun `a new key version after an unconvertible only walk lists again and asks the port again`() {
        val rig = unconvertibleRig()
        rig.drain()
        val before = rig.transport.requests.size
        rig.drain()
        assertEquals("android_sync: pending is cleared first", 1, rig.transport.requests.size - before)

        rig.fake.version = 3
        val relist = rig.transport.requests.size
        rig.drain()

        assertEquals(
            "android_sync: the pull's request and the listing",
            2,
            rig.transport.requests.size - relist,
        )
        assertEquals(
            "android_sync: the unconvertible record is asked of the port again",
            listOf(clientId(1), clientId(1)),
            rig.resealedIds(),
        )
    }

    /** A failure here means a permanent refusal or an unconvertible record keeps the walk open. */
    @Test
    fun `an unconvertible record and a 400 in one walk leave nothing pending`() {
        val rig = MigrationRig { RefusingKeys(it, clientId(1)) }
        rig.fake.version = 2
        rig.items = listOf(
            migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN),
            migrationLive(clientId(2), 2, 0, MIGRATION_PLAIN),
        )
        rig.uploader.thenThrow(PermanentRefusalException(400, "bad body"))

        rig.drain()

        assertEquals("android_sync: one unconvertible", 1, rig.counts().unconvertible)
        assertEquals("android_sync: one failed", 1, rig.counts().failed)
        assertEquals("android_sync: one push, the second record", listOf(clientId(2)), rig.uploadedIds())
        val before = rig.transport.requests.size
        rig.drain()
        assertEquals(
            "android_sync: the next drain makes the pull's request only",
            1,
            rig.transport.requests.size - before,
        )
        assertEquals("android_sync: nothing is uploaded again", 1, rig.uploader.pushes.size)
    }

    /** One record at version 0 that the port refuses to convert, the port at version 2. */
    private fun unconvertibleRig(): MigrationRig {
        val rig = MigrationRig { RefusingKeys(it, clientId(1)) }
        rig.fake.version = 2
        rig.items = listOf(migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN))
        return rig
    }

    /** A failure here means the cap is not 50, the cursor is unused, or pending is not cleared at the end. */
    @Test
    fun `51 records take two drains, the second does not repeat the first 50, then no more listing`() {
        val rig = MigrationRig()
        rig.fake.version = 1
        rig.items = (1..51).map { migrationLive(clientId(it), it.toLong(), 0, MIGRATION_PLAIN) }
        val firstFifty = (1..50).map { clientId(it) }

        rig.drain()

        assertEquals("android_sync: the first drain uploads exactly 50", firstFifty, rig.uploadedIds())
        rig.drain()
        assertEquals(
            "android_sync: the second drain uploads the 51st only",
            firstFifty + clientId(51),
            rig.uploadedIds(),
        )
        assertEquals(
            "android_sync: the port was asked for each record once, so the cursor skipped the first 50",
            (1..51).map { clientId(it) },
            rig.resealedIds(),
        )
        assertEquals("android_sync: 51 replaced", 51, rig.counts().replaced)
        val before = rig.transport.requests.size
        rig.drain()
        assertEquals(
            "android_sync: pending is cleared, so the third drain makes the pull's own request only",
            1,
            rig.transport.requests.size - before,
        )
        assertEquals("android_sync: nothing more is uploaded", 51, rig.uploader.pushes.size)
    }

    /** A failure here means the migration saves or moves the stored pull cursor. */
    @Test
    fun `the migration listing does not move the stored pull cursor`() {
        val rig = MigrationRig()
        rig.fake.version = 2
        rig.items = listOf(migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN))

        rig.drain()

        assertEquals(
            "android_sync: three requests: the list, the follow-up and the migration's listing",
            3,
            rig.transport.requests.size,
        )
        assertEquals(
            "android_sync: the stored cursor is the pull follow-up's (request 1), not the listing's",
            "n1",
            rig.database.pullCursor(),
        )
        assertEquals("android_sync: only the pull's two cycle saves happened", 2, rig.log.count("saveCursor"))
        assertEquals("android_sync: the record was replaced all the same", 1, rig.counts().replaced)
    }

    /** A failure here means a port or a pull transport is missing and the migration is still built or run. */
    @Test
    fun `with no port or no pull transport there is no migration`() {
        val page = listOf(migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN))
        val transport = ScriptedPullTransport { n ->
            PullTransportResult.Answered(200, migrationPage(page, "n$n"))
        }
        val noPort = QueuedSyncService(
            database = InMemorySyncQueueDatabase(),
            uploader = CountingUploader(),
            probe = ScriptedProbe(true),
            pullTransport = transport,
            pullSink = RecordingPullSink(),
        )
        noPort.pushPending()
        assertNull("android_sync: no port, no migration", noPort.migrationCounts())
        assertEquals("android_sync: only the pull's own two requests", 2, transport.requests.size)

        val keys = FakeRecordKeys()
        keys.version = 2
        val uploader = CountingUploader()
        val noTransport = QueuedSyncService(
            database = InMemorySyncQueueDatabase(),
            uploader = uploader,
            probe = ScriptedProbe(true),
            recordKeys = keys,
        )
        noTransport.pushPending()
        assertNull("android_sync: no pull transport, no migration", noTransport.migrationCounts())
        assertEquals("android_sync: nothing was re-sealed", emptyList<ResealCall>(), keys.resealCalls)
        assertEquals("android_sync: nothing was uploaded", emptyList<SyncPush>(), uploader.pushes)
    }

    /** A failure here means a port that holds no keys still makes the migration list or call the port. */
    @Test
    fun `a port with no keys makes no listing and no re-seal call`() {
        val rig = MigrationRig()
        rig.items = listOf(migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN))

        rig.drain()

        assertEquals("android_sync: only the pull's own two requests", 2, rig.transport.requests.size)
        assertEquals("android_sync: no re-seal call", emptyList<ResealCall>(), rig.fake.resealCalls)
        assertEquals("android_sync: nothing uploaded", emptyList<SyncPush>(), rig.uploader.pushes)
    }

    /** A failure here means a new key version does not start a new walk over the records. */
    @Test
    fun `a new key version from the port lists again and re-seals the version 2 records`() {
        val rig = MigrationRig()
        rig.fake.version = 2
        rig.items = listOf(
            migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN),
            migrationLive(clientId(2), 2, 2, "frame-v2"),
        )
        rig.drain()
        assertEquals(
            "android_sync: at version 2 only the version 0 record goes",
            listOf(clientId(1)),
            rig.uploadedIds(),
        )
        val before = rig.transport.requests.size
        rig.drain()
        assertEquals("android_sync: pending is cleared at version 2", 1, rig.transport.requests.size - before)

        rig.fake.version = 3
        rig.drain()

        assertEquals(
            "android_sync: at version 3 both records are re-sealed, the version 2 one from its own version",
            listOf(
                ResealCall(clientId(1), 0, MIGRATION_PLAIN),
                ResealCall(clientId(1), 0, MIGRATION_PLAIN),
                ResealCall(clientId(2), 2, "frame-v2"),
            ),
            rig.fake.resealCalls,
        )
        assertEquals(
            "android_sync: the new pushes are at version 3",
            listOf(2, 3, 3),
            rig.uploader.pushes.map { it.keyVersion },
        )
    }

    /** A failure here means the counts class holds a text, so an id or a body could leak through it. */
    @Test
    fun `the counts hold numbers only, whatever the refusal said`() {
        assertEquals(
            "android_sync: the counts class has three Int fields and one Int? field, no text",
            listOf("int", "int", "int", "java.lang.Integer"),
            MigrationCounts::class.java.declaredFields.map { it.type.name }.sorted(),
        )
        val rig = MigrationRig()
        rig.fake.version = 2
        rig.items = listOf(migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN))
        rig.uploader.thenThrow(PermanentRefusalException(400, "bad body for ${clientId(1)}"))

        rig.drain()

        assertEquals("android_sync: the refusal is a number", 1, rig.counts().failed)
    }
}
