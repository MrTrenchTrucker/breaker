package dev.breaker.dictation.commit

import kotlinx.coroutines.awaitCancellation

/** Keeps every posted task until the test runs it on its own thread. */
internal class ManualPoster : BlockPoster {
    val tasks: MutableList<Runnable> = ArrayList()
    var posts: Int = 0

    override fun post(task: Runnable): Boolean {
        posts++
        tasks.add(task)
        return true
    }

    /** Runs and clears every stored task, on the calling thread. */
    fun runStored() {
        val batch: List<Runnable> = ArrayList(tasks)
        tasks.clear()
        for (task in batch) {
            task.run()
        }
    }
}

/** The main thread is gone: nothing is stored and nothing will ever run. */
internal class RefusingPoster : BlockPoster {
    var posts: Int = 0

    override fun post(task: Runnable): Boolean {
        posts++
        return false
    }
}

/** Runs the task at once on the calling thread, before post returns. */
internal class InlinePoster : BlockPoster {
    var posts: Int = 0

    override fun post(task: Runnable): Boolean {
        posts++
        task.run()
        return true
    }
}

/** A deadline that has already passed when it is first awaited. */
internal class FiredDeadline : HopDeadline {
    var awaited: Int = 0

    override suspend fun elapsed() {
        awaited++
    }
}

/** A deadline that never passes; the wait that holds it is cancelled when the call ends. */
internal class NeverDeadline : HopDeadline {
    var awaited: Int = 0

    override suspend fun elapsed() {
        awaited++
        awaitCancellation()
    }
}

/** A deadline that must not be awaited at all: awaiting it fails the test. */
internal class ForbiddenDeadline : HopDeadline {
    var awaited: Int = 0

    override suspend fun elapsed() {
        awaited++
        throw AssertionError("commit: the deadline must not be awaited here")
    }
}
