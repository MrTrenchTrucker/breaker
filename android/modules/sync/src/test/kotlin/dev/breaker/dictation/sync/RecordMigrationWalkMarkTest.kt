package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The walk mark of the own-record migration: a walk that met a transient problem keeps the work
 * pending when it completes, and only a walk that completes with no mark clears it.
 */
class RecordMigrationWalkMarkTest {

    /**
     * A failure here means a walk that carried the mark clears pending when it completes, so the
     * record is never listed again, or a clean walk does not clear it, so the listing never stops.
     */
    @Test
    fun `a walk that completed after a failure lists again, and a clean walk then stops listing`() {
        val rig = MigrationRig()
        rig.fake.version = 2
        rig.items = listOf(migrationLive(clientId(1), 1, 0, MIGRATION_PLAIN))
        rig.uploader.script(UploadOutcome.FAILED)

        rig.drain()

        assertEquals("android_sync: drain 1 counts one failed upload", 1, rig.counts().failed)
        assertEquals("android_sync: drain 1 pushed the record", listOf(clientId(1)), rig.uploadedIds())

        rig.drain()

        assertEquals("android_sync: drain 2 completes the walk, one replaced", 1, rig.counts().replaced)
        assertEquals(
            "android_sync: drain 2 pushed the record again",
            listOf(clientId(1), clientId(1)),
            rig.uploadedIds(),
        )

        val beforeThird = rig.transport.requests.size
        rig.drain()

        assertEquals(
            "android_sync: drain 3 makes the pull's request and the listing, the marked walk kept pending",
            2,
            rig.transport.requests.size - beforeThird,
        )
        assertEquals(
            "android_sync: drain 3 re-seals the record again",
            listOf(clientId(1), clientId(1), clientId(1)),
            rig.uploadedIds(),
        )

        val beforeFourth = rig.transport.requests.size
        rig.drain()

        assertEquals(
            "android_sync: drain 4 makes the pull's request only, the clean walk cleared pending",
            1,
            rig.transport.requests.size - beforeFourth,
        )
        assertEquals(
            "android_sync: drain 4 uploads nothing more",
            listOf(clientId(1), clientId(1), clientId(1)),
            rig.uploadedIds(),
        )
    }
}
