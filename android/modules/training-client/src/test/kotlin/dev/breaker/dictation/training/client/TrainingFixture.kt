package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assume.assumeNoException

/**
 * One store on a real folder, the scripted server and the client over them, for the client tests.
 *
 * The first [wake] ids handed out are w1 and so on, then s1 and so on for [send], then x1 and so on
 * ([spare] of them) for samples a test adds later. The constructor stores [wake] wake samples and
 * [send] send samples, each 4 samples long (the encoder here is the counting fake, so a sample file is
 * 7 + 4 = 11 bytes). Not compiled or run yet.
 */
class TrainingFixture(val root: File, wake: Int = 10, send: Int = 10, spare: Int = 30) {
    private val scripted = (1..wake).map { "w$it" } + (1..send).map { "s$it" } + (1..spare).map { "x$it" }

    val ids = ScriptedIdSource(*scripted.toTypedArray())
    val encoder = CountingWavEncoder()
    val store = SampleStore(root, ids, encoder)
    val server = ScriptedTrainingServer()
    val client = TrainingClient(store, server)

    init {
        repeat(wake) { put(PhraseKind.WAKE) }
        repeat(send) { put(PhraseKind.SEND) }
    }

    /** Stores one sample of [pcmSize] samples and returns its id. */
    fun put(kind: PhraseKind, pcmSize: Int = 4): String {
        val result = store.put(kind, FloatArray(pcmSize) { 0.5f })
        return when (result) {
            is StoreResult.Ok -> result.value
            else -> error("the fixture could not store a sample: $result")
        }
    }

    /** How many samples are on disk for [kind]. */
    fun local(kind: PhraseKind): Int = store.list(kind).size

    /** Saves a job id as a start would have. */
    fun saveJob(job: String = "job-1") {
        check(store.saveJob(job) is StoreResult.Ok) { "the fixture could not save a job" }
    }

    /** A client over a NEW store on the same folder and the same server: what a restarted app has. */
    fun restarted(): TrainingClient = TrainingClient(SampleStore(root, ScriptedIdSource(), CountingWavEncoder()), server)

    /** Makes the ledger unreadable and every ledger write fail: a folder with a file in it where `ledger.txt` would be. */
    fun breakLedger() {
        val blocker = File(root, "ledger.txt")
        check(blocker.mkdirs()) { "the fixture could not make the blocking folder" }
        File(blocker, "keep.txt").writeText("keep")
    }

    /** Leaves the ledger readable but makes every ledger write fail: a folder with a file in it where `ledger.txt.tmp` would be. */
    fun blockLedgerWrites() {
        val blocker = File(root, "ledger.txt.tmp")
        check(blocker.mkdirs()) { "the fixture could not make the blocking folder" }
        File(blocker, "keep.txt").writeText("keep")
    }
}

/** Makes [link] a symbolic link to [target]; the test is skipped where the file system cannot make one. */
fun symlinkOrSkip(link: File, target: File) {
    try {
        Files.createSymbolicLink(link.toPath(), target.toPath())
    } catch (e: IOException) {
        assumeNoException(e)
    } catch (e: UnsupportedOperationException) {
        assumeNoException(e)
    }
}

/** SHA-256 of [bytes] as lower case hex, written here on its own so a test does not trust the code it checks. */
fun referenceSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }

/** A model reply that passes every check, with [bytes] as the model. */
fun goodReply(bytes: ByteArray = ByteArray(64) { it.toByte() }, samples: Int = 12): ModelReply =
    ModelReply(referenceSha256(bytes), bytes.size.toLong(), samples, bytes)
