package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.db.SqliteDatabase
import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStatus
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.MutableClock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** One scripted answer of the fake forwarder. */
internal typealias ForwardStep = suspend (ForwardRequest) -> ForwardOutcome

internal class RecordedCall(val jobId: Long, val attempt: Int, val audio: ByteArray)

/**
 * Answers call number i with script[i], and every later call with [fallback]. All plain
 * fields are touched only from coroutines of one runBlocking, which share one thread.
 */
internal class ScriptedForwarder(
    private val script: List<ForwardStep> = emptyList(),
    private val fallback: ForwardStep = WorkerFixtures.succeedWithOwnResult(),
) : TranscriptionForwarder {
    private val recorded = ArrayList<RecordedCall>()
    private var inFlight: Int = 0

    val calls: List<RecordedCall> get() = recorded

    /** The largest number of calls that were open at the same moment. */
    var maxInFlight: Int = 0
        private set

    fun jobIds(): List<Long> = recorded.map { call -> call.jobId }

    fun pairs(): List<Pair<Long, Int>> = recorded.map { call -> Pair(call.jobId, call.attempt) }

    override suspend fun forward(request: ForwardRequest): ForwardOutcome {
        val index = recorded.size
        // A copy, so a later change of the array by the code under test cannot rewrite history.
        recorded.add(RecordedCall(request.jobId, request.attempt, request.audio.copyOf()))
        inFlight += 1
        if (inFlight > maxInFlight) {
            maxInFlight = inFlight
        }
        try {
            val step: ForwardStep = if (index < script.size) script[index] else fallback
            return step(request)
        } finally {
            inFlight -= 1
        }
    }
}

/**
 * Never sleeps. It records every duration, how many forward calls had happened by then,
 * and the status of the watched jobs at that moment.
 */
internal class RecordingPause(
    private val store: JobStore? = null,
    private val watched: List<Long> = emptyList(),
    private val forwarder: ScriptedForwarder? = null,
) : Pause {
    private val recordedDurations = ArrayList<Duration>()
    private val recordedCallCounts = ArrayList<Int>()
    private val recordedStatuses = ArrayList<List<JobStatus>>()

    val durations: List<Duration> get() = recordedDurations
    val callsAtPause: List<Int> get() = recordedCallCounts
    val statusesAtPause: List<List<JobStatus>> get() = recordedStatuses

    override suspend fun pause(duration: Duration) {
        // Recorded first: a pause that is entered must show up even if a later line throws.
        recordedDurations.add(duration)
        val probe = forwarder
        if (probe != null) {
            recordedCallCounts.add(probe.calls.size)
        }
        val readable = store
        if (readable != null && watched.isNotEmpty()) {
            val statuses = ArrayList<JobStatus>()
            for (id in watched) {
                statuses.add(WorkerFixtures.statusOf(readable, id))
            }
            recordedStatuses.add(statuses)
        }
    }
}

/**
 * Counts how often the store reads the time. Every store call reads it once before its
 * transaction, so a test can tell which store call a worker has just made.
 */
internal class ReadSignalClock(private val inner: Clock) : Clock() {
    private var reads: Int = 0
    private val waiting = ArrayList<Pair<Int, CompletableDeferred<Unit>>>()

    fun readCount(): Int = reads

    /** Completes when the clock has been read [count] times in total. */
    fun readReached(count: Int): CompletableDeferred<Unit> {
        val signal = CompletableDeferred<Unit>()
        if (reads >= count) {
            signal.complete(Unit)
        } else {
            waiting.add(Pair(count, signal))
        }
        return signal
    }

    override fun instant(): Instant {
        reads += 1
        val due = waiting.filter { entry -> entry.first <= reads }
        for (entry in due) {
            entry.second.complete(Unit)
            waiting.remove(entry)
        }
        return inner.instant()
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

/** The columns of one job row, read with plain SQL. */
internal class RawJob(
    val status: String,
    val attempts: Long,
    val error: String?,
    val result: String?,
    val hasAudio: Boolean,
)

internal class WorkerRig(
    val temp: TempDatabase,
    val store: JobStore,
    val forwarder: ScriptedForwarder,
    val pause: RecordingPause,
    val worker: QueueWorker,
)

internal object WorkerFixtures {
    const val TIMEOUT_MS: Long = 30_000L

    /** Runs [block] in one single-threaded runBlocking; a wait that never ends fails after 30 s. */
    fun runBounded(block: suspend CoroutineScope.() -> Unit) {
        runBlocking {
            withTimeout(TIMEOUT_MS) { block(this) }
        }
    }

    fun rig(
        temp: TempDatabase,
        clock: Clock = MutableClock(JobFixtures.START),
        script: List<ForwardStep> = emptyList(),
        fallback: ForwardStep = succeedWithOwnResult(),
        watched: List<Long> = emptyList(),
    ): WorkerRig {
        val store = JobFixtures.store(temp, clock)
        val forwarder = ScriptedForwarder(script, fallback)
        val pause = RecordingPause(store, watched, forwarder)
        return WorkerRig(temp, store, forwarder, pause, QueueWorker(store, forwarder, pause))
    }

    fun resultFor(jobId: Long): String = "result-for-$jobId"

    /** Enqueues [count] jobs with different audio, owners 1 and 2 alternating; returns the ids. */
    suspend fun enqueueJobs(store: JobStore, count: Int): List<Long> {
        val ids = ArrayList<Long>()
        for (index in 1..count) {
            val owner: Long = if (index % 2 == 1) 1L else 2L
            ids.add(store.enqueue(owner, JobFixtures.audio(index)))
        }
        return ids
    }

    suspend fun statusOf(store: JobStore, id: Long): JobStatus {
        val job = checkNotNull(store.get(id)) { "whisper-server test helper: no job with id $id" }
        return job.status
    }

    /**
     * Returns once every database call made before it has finished: the store runs its
     * calls on one lane in the order they were made.
     */
    suspend fun laneBarrier(store: JobStore) {
        store.get(Long.MAX_VALUE)
    }

    suspend fun rawJob(db: SqliteDatabase, id: Long): RawJob = db.transaction { connection ->
        connection.prepareStatement(
            "SELECT status, attempts, error, result, audio IS NOT NULL FROM jobs WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "whisper-server test helper: no job row with id $id" }
                RawJob(rows.getString(1), rows.getLong(2), rows.getString(3), rows.getString(4), rows.getInt(5) == 1)
            }
        }
    }

    fun success(result: String): ForwardStep = { _ -> ForwardOutcome.Success(result) }

    fun failure(reason: String): ForwardStep = { _ -> ForwardOutcome.Failure(reason) }

    fun succeedWithOwnResult(): ForwardStep = { request -> ForwardOutcome.Success(resultFor(request.jobId)) }

    /** Gives other coroutines two chances to run while the call is open. */
    fun yieldingSuccess(): ForwardStep = { request ->
        yield()
        yield()
        ForwardOutcome.Success(resultFor(request.jobId))
    }

    fun throwing(make: () -> Throwable): ForwardStep = { _ -> throw make() }

    /** Tells the test the call is open, then holds it until the test lets it go. */
    fun blockedOn(entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>, result: String): ForwardStep =
        { _ ->
            entered.complete(Unit)
            release.await()
            ForwardOutcome.Success(result)
        }

    /** Tells the test the call is open and never answers; only a cancel ends it. */
    fun suspendForever(entered: CompletableDeferred<Unit>): ForwardStep = { _ ->
        entered.complete(Unit)
        CompletableDeferred<Unit>().await()
        ForwardOutcome.Failure("whisper-server test helper: this call is never answered")
    }

    /** Reports the id of the job it was called for, then succeeds. */
    fun reporting(seen: CompletableDeferred<Long>): ForwardStep = { request ->
        seen.complete(request.jobId)
        ForwardOutcome.Success(resultFor(request.jobId))
    }
}
