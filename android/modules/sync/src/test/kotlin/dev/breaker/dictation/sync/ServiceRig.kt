package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * One [QueuedSyncService] wired to a fresh set of test doubles that share one [CallLog].
 *
 * The probe says reachable unless the test says otherwise. The doubles are public properties, so a
 * test scripts them before it calls the service and reads them afterwards.
 */
internal class ServiceRig(reachable: Boolean = true) {

    val log = CallLog()
    val database = InMemorySyncQueueDatabase(log)
    val uploader = ScriptedUploader(log)
    val probe = ScriptedProbe(reachable, log)
    val service = QueuedSyncService(database = database, uploader = uploader, probe = probe)

    /** Enqueues one transcription per id, in the order given, with the default distinctive text. */
    fun queue(vararg ids: String) {
        ids.forEach { service.enqueue(aTranscription(it)) }
    }

    /** One drain. */
    fun push(): SyncReport = service.pushPending()

    /** The client ids the uploader received, in the order received. */
    fun uploadedIds(): List<String> = uploader.received.map { it.clientId }

    /** The bodies the uploader received, each decoded with the codec, in the order received. */
    fun uploadedBodies(): List<SyncBody> = uploader.received.map { SyncBodyCodec.decodePlain(it.encrypted) }

    /** Fails unless nothing was uploaded or removed since the rig was built. */
    fun assertNothingUploadedOrRemoved(context: String) {
        assertTrue("android_sync: $context: the uploader must not be called, got ${uploadedIds()}", uploader.received.isEmpty())
        assertEquals("android_sync: $context: nothing may be removed from the queue", 0, database.removeCalls)
    }
}
