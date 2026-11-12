package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SealedRecord
import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.port.RecordKeys

/** What one call of [FakeRecordKeys.open] was given. */
internal data class OpenCall(val clientId: String, val keyVersion: Int, val encrypted: String)

/** What one call of [FakeRecordKeys.reseal] was given. */
internal data class ResealCall(val clientId: String, val keyVersion: Int, val encrypted: String)

/** What one call of [FakeRecordKeys.convertSealed] was given. */
internal data class ConvertCall(val clientId: String, val boxCopy: List<Byte>)

/**
 * A scripted [RecordKeys] for tests. It writes the words `version`, `seal`, `open` and `reseal` into the
 * shared [CallLog], keeps what each call was given, and answers from fields the test sets.
 *
 * [sealedBodies] keeps the very array that seal received, so a test can check it was zeroed
 * after the call; [sealedCopies] keeps a copy made at call time, so a test can check what the
 * bytes were. The frame text is made by [frameFor], never by a real cipher.
 */
internal class FakeRecordKeys(private val log: CallLog = CallLog()) : RecordKeys {

    /** The key version the port reports. Zero means no keys. */
    var version: Int = 0

    /** False makes seal answer null whatever the version. */
    var canSeal: Boolean = true

    /** What open returns: the same array every time, or null. */
    var openResult: ByteArray? = null

    /** False makes reseal answer null whatever the version. */
    var canReseal: Boolean = true

    /** When set, reseal answers this record instead of the frame text made by [frameFor]. */
    var resealResult: SealedRecord? = null

    /** False makes convertSealed answer null whatever the version. */
    var canConvert: Boolean = true

    /** When set, convertSealed answers this record instead of the frame text made by [frameFor]. */
    var convertResult: SealedRecord? = null

    val resealCalls = ArrayList<ResealCall>()

    /** What each convertSealed call was given, the box as a copy made at call time. */
    val convertCalls = ArrayList<ConvertCall>()

    /** The very arrays convertSealed received, so a test can check they were zeroed after the call. */
    val convertedBoxes = ArrayList<ByteArray>()
    val sealedClientIds = ArrayList<String>()
    val sealedBodies = ArrayList<ByteArray>()
    val sealedCopies = ArrayList<ByteArray>()
    val openCalls = ArrayList<OpenCall>()

    /** The frame text this double makes for [clientId] at [keyVersion]. */
    fun frameFor(clientId: String, keyVersion: Int): String = "frame-v$keyVersion-$clientId"

    override fun currentKeyVersion(): Int {
        log.add("version")
        return version
    }

    override fun seal(clientId: String, body: ByteArray): SealedRecord? {
        log.add("seal")
        sealedClientIds.add(clientId)
        sealedBodies.add(body)
        sealedCopies.add(body.copyOf())
        if (!canSeal || version < 1) return null
        return SealedRecord(version, frameFor(clientId, version))
    }

    override fun open(clientId: String, keyVersion: Int, encrypted: String): ByteArray? {
        log.add("open")
        openCalls.add(OpenCall(clientId, keyVersion, encrypted))
        return openResult
    }

    override fun reseal(clientId: String, keyVersion: Int, encrypted: String): SealedRecord? {
        log.add("reseal")
        resealCalls.add(ResealCall(clientId, keyVersion, encrypted))
        if (!canReseal) return null
        return resealResult ?: if (version >= 1) SealedRecord(version, frameFor(clientId, version)) else null
    }

    override fun convertSealed(clientId: String, sealedBox: ByteArray): SealedRecord? {
        log.add("convert")
        convertCalls.add(ConvertCall(clientId, sealedBox.toList()))
        convertedBoxes.add(sealedBox)
        if (!canConvert) return null
        return convertResult ?: if (version >= 1) SealedRecord(version, frameFor(clientId, version)) else null
    }
}

/** One [QueuedSyncService] wired to a [FakeRecordKeys] and the usual doubles, sharing one [CallLog]. */
internal class PortRig(reachable: Boolean = true) {

    val log = CallLog()
    val database = InMemorySyncQueueDatabase(log)
    val uploader = ScriptedUploader(log)
    val probe = ScriptedProbe(reachable, log)
    val keys = FakeRecordKeys(log)
    val service = QueuedSyncService(
        database = database,
        uploader = uploader,
        probe = probe,
        recordKeys = keys,
    )

    /** Enqueues one transcription per id, in the order given, with the default distinctive text. */
    fun queue(vararg ids: String) {
        ids.forEach { service.enqueue(aTranscription(it)) }
    }

    /** One drain. */
    fun push(): SyncReport = service.pushPending()
}
