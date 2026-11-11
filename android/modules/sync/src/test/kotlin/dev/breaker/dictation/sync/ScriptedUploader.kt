package dev.breaker.dictation.sync

import dev.breaker.shared.api.SyncPush

/**
 * A [SyncUploader] for tests: it answers from a script, keeps every push it was given, and can
 * run a callback inside the call.
 *
 * Each call takes the next scripted step: an outcome to return, or an exception to throw. With
 * nothing scripted it answers [UploadOutcome.CONFIRMED]. The step is taken before the callback
 * runs, so a call made from inside the callback takes the step after it. The callback sees the
 * push being uploaded; a test uses it to look at the queue at call time, to call the service
 * again, or to enqueue. The double never reads the push contents: a test decodes them with the codec.
 *
 * The 51st call throws an [AssertionError] ("the drain is not making progress"). It is an
 * [Error], so the service's `catch (e: Exception)` does not swallow it: a drain that never ends
 * fails the test instead of hanging.
 */
internal class ScriptedUploader(private val log: CallLog = CallLog()) : SyncUploader {

    /** Every push received, oldest first. */
    val received = ArrayList<SyncPush>()

    /** Every client id deleted, oldest first. */
    val deleted = ArrayList<String>()

    /** Runs inside [upload] after the push is stored and before the answer is returned. */
    var onUpload: ((SyncPush) -> Unit)? = null

    /** Runs inside [delete] after the client id is recorded and before the answer is returned. */
    var onDelete: ((clientId: String) -> Unit)? = null

    /** How many times [upload] or [delete] was called. */
    var calls: Int = 0
        private set

    private val steps = ArrayDeque<() -> UploadOutcome>()
    private val deleteSteps = ArrayDeque<() -> UploadOutcome>()

    /** Queues [outcomes] as the answers of the next [upload] calls, in order. */
    fun script(vararg outcomes: UploadOutcome) {
        outcomes.forEach { outcome -> steps.addLast { outcome } }
    }

    /** Queues [outcomes] as the answers of the next [delete] calls, in order. */
    fun deleteScript(vararg outcomes: UploadOutcome) {
        outcomes.forEach { outcome -> deleteSteps.addLast { outcome } }
    }

    /** Queues a call that throws [cause]. */
    fun thenThrow(cause: Exception) {
        steps.addLast { throw cause }
    }

    /** Queues a call that throws [cause] on the next [delete] call. */
    fun thenThrowDelete(cause: Exception) {
        deleteSteps.addLast { throw cause }
    }

    override fun upload(push: SyncPush): UploadOutcome {
        calls++
        if (calls > MAX_CALLS) {
            throw AssertionError(
                "android_sync: the drain is not making progress: the uploader was called more than $MAX_CALLS times",
            )
        }
        log.add("upload")
        received.add(push)
        val step = steps.removeFirstOrNull()
        onUpload?.invoke(push)
        return if (step == null) UploadOutcome.CONFIRMED else step()
    }

    override fun delete(clientId: String): UploadOutcome {
        calls++
        if (calls > MAX_CALLS) {
            throw AssertionError(
                "android_sync: the drain is not making progress: the uploader was called more than $MAX_CALLS times",
            )
        }
        log.add("delete")
        deleted.add(clientId)
        onDelete?.invoke(clientId)
        val step = deleteSteps.removeFirstOrNull()
        return if (step == null) UploadOutcome.CONFIRMED else step()
    }

    companion object {
        /** The most calls a test may make before the guard throws. */
        const val MAX_CALLS = 50
    }
}
