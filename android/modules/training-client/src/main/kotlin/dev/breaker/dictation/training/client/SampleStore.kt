package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import dev.breaker.dictation.core.port.IdSource
import dev.breaker.dictation.core.port.WavEncoder
import java.io.File
import java.io.IOException

/** The outcome of a store call. An expected outcome is a value, never an exception. */
sealed interface StoreResult<out T> {
    data class Ok<T>(val value: T) : StoreResult<T>

    /** An id (or job id) that fails the rules: nothing was read, written or deleted. */
    data object InvalidId : StoreResult<Nothing>

    data object NotFound : StoreResult<Nothing>

    data object IoFailed : StoreResult<Nothing>
}

/**
 * The app-private store of recorded phrase samples, plus a small text ledger (see [SampleLedger]).
 *
 * Layout under [root]: `wake/<id>.wav` and `send/<id>.wav`, each written as `<id>.wav.tmp` and then
 * renamed, and `ledger.txt`. Ids reach the store from outside (the id source, the caller), so every id
 * is checked first (ASCII letters, digits, '-' and '_', 1 to 64 characters, before any path is built),
 * and the final path must then resolve inside [root]. Nothing is logged. A new file is asked to be
 * readable and writable by its owner only; that is NOT verified on a phone.
 *
 * Calls block and are not synchronised: the caller decides which context runs them and must not run two calls at once.
 *
 * Status: written without a compiler, not built, not tried on a phone.
 */
class SampleStore(private val root: File, private val ids: IdSource, private val wav: WavEncoder) {
    private val ledger = SampleLedger(root)

    private fun kindDir(kind: PhraseKind): File = File(root, StoreRules.kindName(kind))

    private fun isInsideRoot(file: File): Boolean {
        val base = root.canonicalPath.trimEnd(File.separatorChar) + File.separatorChar
        return file.canonicalPath.startsWith(base)
    }

    private fun dirInsideRoot(kind: PhraseKind): Boolean = try {
        isInsideRoot(kindDir(kind))
    } catch (e: IOException) {
        false
    }

    /** Validates [id] first, builds the path, checks it is inside the root, then runs [block] on it. */
    private fun <T> withSample(kind: PhraseKind, id: String, block: (File) -> StoreResult<T>): StoreResult<T> {
        if (!StoreRules.validId(id)) return StoreResult.InvalidId
        val file = File(kindDir(kind), id + StoreRules.WAV_SUFFIX)
        val inside = try {
            isInsideRoot(file)
        } catch (e: IOException) {
            return StoreResult.IoFailed
        }
        return if (inside) block(file) else StoreResult.InvalidId
    }

    /**
     * Stores [pcm] as a WAV under the id from the id source, which is checked like any other id. A bad id
     * gives [StoreResult.InvalidId] and nothing is encoded or written. An id that already has a sample is
     * not overwritten: the answer is [StoreResult.IoFailed]. Any [IOException] is [StoreResult.IoFailed].
     */
    fun put(kind: PhraseKind, pcm: FloatArray): StoreResult<String> {
        val id = ids.newId()
        return withSample<String>(kind, id) { target ->
            if (target.exists()) {
                StoreResult.IoFailed
            } else {
                val written = StoreFiles.writeAtomic(target, wav.encode(pcm))
                if (written is StoreResult.Ok) StoreResult.Ok(id) else StoreResult.IoFailed
            }
        }
    }

    /** The sorted ids of the samples present for [kind]. A temporary file is never listed; no folder is an empty list. */
    fun list(kind: PhraseKind): List<String> {
        if (!dirInsideRoot(kind)) return emptyList()
        val dir = kindDir(kind)
        val names = dir.list() ?: return emptyList()
        return names
            .filter { it.endsWith(StoreRules.WAV_SUFFIX) }
            .map { it.removeSuffix(StoreRules.WAV_SUFFIX) }
            .filter { StoreRules.validId(it) && File(dir, it + StoreRules.WAV_SUFFIX).isFile }
            .sorted()
    }

    /** The WAV bytes of one sample: [StoreResult.NotFound] when it is absent. */
    fun read(kind: PhraseKind, id: String): StoreResult<ByteArray> =
        withSample<ByteArray>(kind, id) { file ->
            if (!file.isFile) {
                StoreResult.NotFound
            } else {
                try {
                    StoreResult.Ok(file.readBytes())
                } catch (e: IOException) {
                    StoreResult.IoFailed
                }
            }
        }

    /** Deletes one sample: [StoreResult.NotFound] when it is absent. The ledger is not touched. */
    fun deleteOne(kind: PhraseKind, id: String): StoreResult<Unit> =
        withSample<Unit>(kind, id) { file ->
            if (!file.isFile) {
                StoreResult.NotFound
            } else if (file.delete()) {
                StoreResult.Ok(Unit)
            } else {
                StoreResult.IoFailed
            }
        }

    /**
     * Deletes every sample file and temporary file in both kind folders, and the confirmed ids and the job
     * id from the ledger. The server-delete-pending mark is left as it was. A kind folder that resolves
     * outside the root is not followed and counts as a failure.
     */
    fun deleteAll(): StoreResult<Unit> {
        var clean = true
        for (kind in PhraseKind.entries) {
            if (!deleteKindFiles(kind)) clean = false
        }
        val cleared = ledger.clearSamplesAndJob()
        return if (clean && cleared is StoreResult.Ok) StoreResult.Ok(Unit) else StoreResult.IoFailed
    }

    private fun deleteKindFiles(kind: PhraseKind): Boolean {
        if (!dirInsideRoot(kind)) return false
        val dir = kindDir(kind)
        val files = dir.listFiles()
        if (files == null) return !dir.exists()
        var clean = true
        for (f in files) {
            val isSample = f.name.endsWith(StoreRules.WAV_SUFFIX) ||
                f.name.endsWith(StoreRules.WAV_SUFFIX + StoreRules.TMP_SUFFIX)
            if (isSample && f.isFile && !f.delete()) clean = false
        }
        return clean
    }

    /** Records that the server confirmed [id] for [kind]. The id is checked like any sample id. */
    fun markConfirmed(kind: PhraseKind, id: String): StoreResult<Unit> = ledger.confirm(kind, id)

    /** The ids the server confirmed for [kind]; empty when the ledger is missing or unreadable. */
    fun confirmed(kind: PhraseKind): Set<String> = ledger.confirmed(kind)

    /** Saves the current job id (1 to 128 characters, no line break); anything else is [StoreResult.InvalidId]. */
    fun saveJob(job: String): StoreResult<Unit> = ledger.saveJob(job)

    fun loadJob(): String? = ledger.loadJob()

    fun clearJob(): StoreResult<Unit> = ledger.clearJob()

    fun setServerDeletePending(pending: Boolean): StoreResult<Unit> = ledger.setPending(pending)

    /**
     * True when a server delete is marked pending AND when the ledger cannot be read: "cannot tell" is
     * never answered false, so nothing is sent while a delete might be owed.
     */
    fun serverDeletePending(): Boolean = ledger.serverDeleteState() != LedgerState.CLEAR

    /** The three-way answer behind [serverDeletePending]; internal, so the module's public names do not change. */
    internal fun serverDeleteState(): LedgerState = ledger.serverDeleteState()
}
