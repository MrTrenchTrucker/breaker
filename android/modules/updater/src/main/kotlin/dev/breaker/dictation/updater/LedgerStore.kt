package dev.breaker.dictation.updater

import dev.breaker.dictation.core.port.Clock
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The ledger state that the first-start decision reads and writes. Each call reads the file again.
 */
internal interface LedgerSlot {
    /** The whole state. A missing or damaged file, or a file that cannot be read, gives [LedgerValues.EMPTY]. */
    fun read(): LedgerValues

    /**
     * Stores [status] on the recorded session in one write and changes nothing else. Returns false, and writes nothing,
     * when no session is recorded. A failed write throws IOException.
     */
    fun storeFinalStatus(status: String): Boolean

    /**
     * Applies one decision in one write: removes the record when [removeRecord] is true, then applies [lastSeenCode] and
     * [lastOutcome]. The three times are not changed. A failed read or write throws IOException.
     */
    fun applyChange(removeRecord: Boolean, lastSeenCode: FieldEdit<Int>, lastOutcome: FieldEdit<String>)
}

/** The text between a ledger file name and the sequence number of its temporary file name. */
internal const val LEDGER_TEMP_MARKER: String = ".tmp"

/** The name of the temporary file for the write with [sequence], in the same folder as the file named [ledgerName]. */
internal fun ledgerTempName(ledgerName: String, sequence: Int): String = ledgerName + LEDGER_TEMP_MARKER + sequence.toString()

/** True when [candidate] is a name that [ledgerTempName] makes for [ledgerName]: the name, the marker, then one or more digits. */
internal fun isLedgerTempName(ledgerName: String, candidate: String): Boolean {
    if (!candidate.startsWith(ledgerName + LEDGER_TEMP_MARKER)) return false
    val digits = candidate.substring(ledgerName.length + LEDGER_TEMP_MARKER.length)
    return digits.isNotEmpty() && digits.all { it in '0'..'9' }
}

/**
 * Removes the temporary files that an interrupted ledger write left in the folder of [ledgerFile]. Only a regular file
 * whose name [isLedgerTempName] accepts is removed. A missing folder does nothing, and a file that cannot be removed is
 * left in place, so the sweep never throws.
 */
internal fun sweepStaleLedgerTemps(ledgerFile: File) {
    val folder = ledgerFile.absoluteFile.parentFile ?: return
    val names = folder.list() ?: return
    for (name in names) {
        if (!isLedgerTempName(ledgerFile.name, name)) continue
        val candidate = File(folder, name)
        if (!candidate.isFile) continue
        try {
            Files.deleteIfExists(candidate.toPath())
        } catch (error: Exception) {
            Unit
        }
    }
}

/**
 * The ledger file: the three times, the install record, the last seen code and the last outcome in one text file.
 * The store keeps no copy of the file. Each write replaces the whole file through a temporary file in the same folder.
 * The store is used from one thread and holds no lock.
 */
internal class LedgerStore(private val file: File, private val clock: Clock) : UpdateLedger, InstallRecord, LedgerSlot {
    /** The sequence number of the last temporary file name this store used. It is incremented before every write. */
    private var tempSequence: Int = 0

    override fun lastDefinitiveCheckMillis(): Long? = read().definitiveCheckMs

    override fun lastUpdateMillis(): Long? = read().updateMs

    override fun lastAttemptMillis(): Long? = read().attemptMs

    override fun recordDefinitiveCheck(atMillis: Long) {
        changeQuietly { it.copy(definitiveCheckMs = atMillis) }
    }

    override fun recordUpdate(atMillis: Long) {
        changeQuietly { it.copy(updateMs = atMillis) }
    }

    override fun recordAttempt(atMillis: Long) {
        changeQuietly { it.copy(attemptMs = atMillis) }
    }

    override fun sessionOpened(sessionId: Int) {
        val writtenAt = clock.nowEpochMillis()
        val values = loadOrThrow()
        writeValues(values.copy(record = LedgerRecord(sessionId, writtenAt, null)))
    }

    override fun recordedSessionId(): Int? = read().record?.sessionId

    override fun clearRecord() {
        val values = loadOrThrow()
        if (values.record == null) return
        writeValues(values.copy(record = null))
    }

    override fun read(): LedgerValues = try { loadOrThrow() } catch (error: IOException) { LedgerValues.EMPTY }

    override fun storeFinalStatus(status: String): Boolean {
        val values = loadOrThrow()
        val record = values.record ?: return false
        writeValues(values.copy(record = record.copy(finalStatus = status)))
        return true
    }

    override fun applyChange(removeRecord: Boolean, lastSeenCode: FieldEdit<Int>, lastOutcome: FieldEdit<String>) {
        val values = loadOrThrow()
        writeValues(
            values.copy(
                record = if (removeRecord) null else values.record,
                lastSeenCode = lastSeenCode.applyTo(values.lastSeenCode),
                lastOutcome = lastOutcome.applyTo(values.lastOutcome),
            ),
        )
    }

    /** Reads the state, applies [change] and writes it; an IOException is not thrown to the caller. */
    private fun changeQuietly(change: (LedgerValues) -> LedgerValues): Unit = try { writeValues(change(loadOrThrow())) } catch (error: IOException) { Unit }

    /** Reads the state the file holds now. A missing or damaged file gives EMPTY; a file that cannot be read throws IOException. */
    private fun loadOrThrow(): LedgerValues {
        if (!Files.exists(file.toPath())) return LedgerValues.EMPTY
        val text = decodeStrictUtf8(Files.readAllBytes(file.toPath())) ?: return LedgerValues.EMPTY
        return LedgerCodec.decode(text)
    }

    /** The one write point: writes [values] to a temporary file in the same folder and moves it over the ledger file. */
    private fun writeValues(values: LedgerValues) {
        val bytes = LedgerCodec.encode(values).toByteArray(Charsets.UTF_8)
        tempSequence += 1
        val temp = File(file.absoluteFile.parentFile, ledgerTempName(file.name, tempSequence))
        var moved = false
        try {
            Files.write(temp.toPath(), bytes)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            moved = true
        } finally {
            if (!moved) deleteQuietly(temp)
        }
    }

    /** Removes [temp] if it is there. A failure is ignored: the caller already reports the failed write. */
    private fun deleteQuietly(temp: File) {
        try {
            Files.deleteIfExists(temp.toPath())
        } catch (error: IOException) {
            Unit
        }
    }
}
