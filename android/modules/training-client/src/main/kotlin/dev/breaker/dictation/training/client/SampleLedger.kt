package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.TreeSet

/**
 * Names and checks shared by the sample store and its ledger.
 *
 * Status: written without a compiler, not built, not tried on a phone.
 */
internal object StoreRules {
    const val MAX_ID_LENGTH = 64
    const val MAX_JOB_LENGTH = 128
    const val WAV_SUFFIX = ".wav"
    const val TMP_SUFFIX = ".tmp"
    const val LEDGER_NAME = "ledger.txt"

    /** The folder name of [kind]: its name in lower case. */
    fun kindName(kind: PhraseKind): String = kind.name.lowercase()

    /** A sample id: ASCII letters, digits, '-' and '_' only, 1 to [MAX_ID_LENGTH] characters. */
    fun validId(id: String): Boolean {
        if (id.length !in 1..MAX_ID_LENGTH) return false
        return id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    }

    /** A job id: 1 to [MAX_JOB_LENGTH] characters and no line break, so it cannot forge a ledger line. */
    fun validJob(job: String): Boolean {
        if (job.length !in 1..MAX_JOB_LENGTH) return false
        return job.none { it == '\n' || it == '\r' }
    }
}

/** File helpers shared by the sample store and its ledger. */
internal object StoreFiles {
    /**
     * Asks the JDK to leave [file] readable and writable by its owner only. The JDK may refuse
     * quietly on some file systems, so the result is not checked here. Not verified on a phone.
     */
    fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    /**
     * Writes [bytes] to `<name>.tmp` beside [target] and renames it onto [target]. On any
     * [IOException] the temporary file is deleted if it can be and [StoreResult.IoFailed] is returned.
     */
    fun writeAtomic(target: File, bytes: ByteArray): StoreResult<Unit> {
        val tmp = File(target.parentFile, target.name + StoreRules.TMP_SUFFIX)
        return try {
            target.parentFile?.mkdirs()
            tmp.writeBytes(bytes)
            restrictToOwner(tmp)
            if (tmp.renameTo(target)) {
                StoreResult.Ok(Unit)
            } else {
                tmp.delete()
                StoreResult.IoFailed
            }
        } catch (e: IOException) {
            tmp.delete()
            StoreResult.IoFailed
        }
    }
}

/** What the ledger says about a server delete: no mark, the mark, or "cannot tell" because the ledger cannot be read. */
internal enum class LedgerState { CLEAR, PENDING, UNREADABLE }

private const val CONFIRMED_WORD = "confirmed"
private const val JOB_PREFIX = "job "
private const val PENDING_LINE = "server-delete-pending"

/**
 * The small text ledger beside the samples: the ids the server confirmed for each kind, the current
 * job id and the server-delete-pending mark. It never holds audio.
 *
 * Every call reads the file afresh, so two instances on one root agree. Every write puts the whole
 * ledger in `ledger.txt.tmp` and renames it onto `ledger.txt`. A line that is unknown or malformed is
 * ignored when reading, and is dropped the next time the ledger is written: a lost confirmation only
 * causes a safe re-upload. If the file cannot be read at all, a change returns
 * [StoreResult.IoFailed] and writes nothing, so an unreadable ledger is never overwritten blind.
 *
 * Status: written without a compiler, not built, not tried on a phone.
 */
internal class SampleLedger(root: File) {
    private val file = File(root, StoreRules.LEDGER_NAME)

    private class State {
        val wake = TreeSet<String>()
        val send = TreeSet<String>()
        var job: String? = null
        var pending = false

        fun ids(kind: PhraseKind): MutableCollection<String> = when (kind) {
            PhraseKind.WAKE -> wake
            PhraseKind.SEND -> send
        }
    }

    /**
     * The facts in the file; an empty state only when the file is reported missing; null when the file cannot
     * be read. The path is looked at without following links: any other error than "no such file", and any
     * path that is not a regular file (a folder, a symbolic link even to a readable file, anything else), is
     * null, so a link or an unsearchable folder is never taken for "no ledger".
     */
    private fun load(): State? {
        val state = State()
        val attributes = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            return state
        } catch (e: IOException) {
            return null
        }
        if (!attributes.isRegularFile) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return null
        }
        for (raw in text.split('\n')) {
            parseLine(state, raw.trimEnd('\r'))
        }
        return state
    }

    private fun parseLine(state: State, line: String) {
        if (line == PENDING_LINE) {
            state.pending = true
            return
        }
        if (line.startsWith(JOB_PREFIX)) {
            val job = line.substring(JOB_PREFIX.length)
            if (StoreRules.validJob(job)) state.job = job
            return
        }
        val parts = line.split(' ')
        if (parts.size != 3 || parts[0] != CONFIRMED_WORD) return
        val kind = PhraseKind.entries.firstOrNull { StoreRules.kindName(it) == parts[1] } ?: return
        if (StoreRules.validId(parts[2])) state.ids(kind).add(parts[2])
    }

    private fun write(state: State): StoreResult<Unit> {
        val out = StringBuilder()
        for (kind in PhraseKind.entries) {
            for (id in state.ids(kind).sorted()) {
                out.append(CONFIRMED_WORD).append(' ').append(StoreRules.kindName(kind)).append(' ').append(id).append('\n')
            }
        }
        val job = state.job
        if (job != null) out.append(JOB_PREFIX).append(job).append('\n')
        if (state.pending) out.append(PENDING_LINE).append('\n')
        return StoreFiles.writeAtomic(file, out.toString().toByteArray(Charsets.UTF_8))
    }

    private fun update(change: (State) -> Unit): StoreResult<Unit> {
        val state = load() ?: return StoreResult.IoFailed
        change(state)
        return write(state)
    }

    fun confirm(kind: PhraseKind, id: String): StoreResult<Unit> {
        if (!StoreRules.validId(id)) return StoreResult.InvalidId
        return update { it.ids(kind).add(id) }
    }

    fun confirmed(kind: PhraseKind): Set<String> = load()?.ids(kind)?.toSet() ?: emptySet()

    fun saveJob(job: String): StoreResult<Unit> {
        if (!StoreRules.validJob(job)) return StoreResult.InvalidId
        return update { it.job = job }
    }

    fun loadJob(): String? = load()?.job

    fun clearJob(): StoreResult<Unit> = update { it.job = null }

    fun setPending(pending: Boolean): StoreResult<Unit> = update { it.pending = pending }

    /** Where the server-delete-pending mark stands; [LedgerState.UNREADABLE] when the ledger cannot be read, never a guess. */
    fun serverDeleteState(): LedgerState {
        val state = load() ?: return LedgerState.UNREADABLE
        return if (state.pending) LedgerState.PENDING else LedgerState.CLEAR
    }

    /** Removes every confirmed id and the job id; the server-delete-pending mark stays as it was. */
    fun clearSamplesAndJob(): StoreResult<Unit> = update { state ->
        state.wake.clear()
        state.send.clear()
        state.job = null
    }
}
