package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A microphone capture session: one device, one capture thread, one consumer
 * thread, and a bounded buffer between them.
 *
 * ### The threads
 *
 * The capture thread does nothing but read the device and get the audio into
 * 16 kHz mono float — the resample, the downmix and the suppression all happen
 * there, so the listener never receives a frame the pipeline has not already
 * finished with. A dispatcher thread drains the ring buffer into fixed-size
 * frames and calls the listener.
 *
 * They are separate because the listener is somebody else's code: an engine
 * that takes 200 ms on a frame must not stall the driver, because a stalled
 * driver overruns and the platform drops audio with no record of it. Here the
 * overflow lands in [PcmRingBuffer] instead, which counts it.
 *
 * ### The contract it honours
 *
 * [start] returns immediately and never calls the listener on the caller's
 * thread. [stop] is safe at any point in a capture's life — before [start],
 * while it is running, inside the device's open, and after a capture that ended
 * by itself — and where there IS a session to tear down, it tears it down: it
 * closes the device, marks the indicator dark, and joins both threads before it
 * returns. With no session to tear down it closes no device and marks no
 * indicator, because there is nothing of its own to release; a stop that lands
 * inside a start has no threads to join yet, so it is recorded instead, and
 * the start it raced acts on the record instead of coming up: it closes what
 * it opened, marks the indicator dark, and returns quietly. That close and that
 * mark happen on the START thread, once the device's open has come back — so
 * in that one window the indicator stays lit briefly after stop() has returned,
 * which is the safe direction and is not something stop() can change: it
 * returned before there was a device it was allowed to touch. A caller that
 * stops and then reads the take
 * is therefore not racing the capture that filled it, and its last frame is
 * the take's last frame rather than one still in flight. The take is whole:
 * the last fraction of a millisecond the resampler is still holding back at
 * the end is recovered before the final frame is delivered, so the samples the
 * device produced are the samples the listener gets. The last frame of a
 * capture is short rather than padded with silence — a short frame is how the
 * listener learns that capture ended, and padding it out would invent audio
 * the user never spoke.
 *
 * A capture can also end without the caller doing anything: the device can
 * fail, or stop producing audio, and the capture thread concludes on its own.
 * That ends the audio and nothing else. The device is still open, the
 * indicator is still live, and the take's last frame is still in the
 * dispatcher's hands until its next read returns. None of that is released by
 * the capture ending, so [stop] is still owed, and [isCapturing] going false
 * says only that the device is no longer being read.
 *
 * A capture is reusable: start, stop, start again gives a second full take,
 * because each take is given its own conversion pipeline as well as its own
 * ring buffer — a pipeline holds a resampler's read point and history, and
 * sharing one would let a thread from the previous take, still inside a
 * conversion, reset and rewrite the state this take is filtering through. Each
 * take's dispatcher is handed its own listener when its thread starts and
 * holds nothing but that, so a take can never be delivered to the listener of
 * the take before it.
 *
 * Each take also gets its own ring buffer. A listener that blocks past
 * [joinTimeoutMs] leaves a dispatcher running past [stop], and the only thing
 * that can wake its read is audio — which, in a shared buffer, is the next
 * take's. The straggler would take a read of it and the new take would come up
 * short by exactly that much. A buffer per take means the straggler is reading
 * a buffer nobody writes to again, so a take is short by nothing but what its
 * own device never produced.
 *
 * ### The mic indicator
 *
 * The indicator is marked live before the first frame is read and marked dead
 * on every path out of the session, including a failure and a stop that timed
 * out. A screen that binds to it therefore never shows "not recording" while
 * the microphone is open, which is the whole point of showing it.
 *
 * ### What this class owns
 *
 * This class owns what a take IS: which take is current, the ring buffer it
 * drains, the pipeline that converts its samples, and the flags that describe
 * it.
 *
 * ### Where the work lives
 *
 * The two threads' loops are [CaptureLoop] and [DispatchLoop], the per-frame
 * conversion is [CapturePcmPipeline], and the half of a session that is about
 * the ORDERING of two overlapping calls rather than about audio — the device
 * gate, the record of a stop that landed inside a start's open, the teardown
 * latch two stops share, and the open/publish and close/join windows — is
 * [CaptureSessionLifecycle]. Every [start] and every [stop] on this class is
 * that other half deciding when this one's take touches the device; the
 * contract each of them honours is written on the method here, because that is
 * where a caller reads it.
 */
class MicCapture(
    private val source: MicSource,
    /**
     * The suppressor every take of this capture uses.
     *
     * It is the caller's instance, so the same one every take gets, and its own
     * state therefore carries across takes: a per-take [CapturePcmPipeline]
     * isolates the resampler, which is where a straggler corrupts audio, and
     * leaves this shared. See the Known Gotcha in the module card.
     */
    private val suppressor: NoiseSuppressor = PassThroughNoiseSuppressor,
    private val indicator: RecordingIndicator = RecordingIndicator(),
    /** Frame size handed to the listener, in samples. 20 ms at 16 kHz by default. */
    frameSamples: Int = DEFAULT_FRAME_SAMPLES,
    /** Ring buffer depth, in samples. 2 s at 16 kHz by default. */
    private val bufferSamples: Int = DEFAULT_BUFFER_SAMPLES,
    /** How long [stop] waits for the threads before giving up and reporting it. */
    private val joinTimeoutMs: Long = DEFAULT_JOIN_TIMEOUT_MS,
) : AudioSource {

    init {
        require(frameSamples > 0) { "frameSamples must be positive: $frameSamples" }
        require(bufferSamples >= frameSamples) {
            "bufferSamples ($bufferSamples) must hold at least one frame ($frameSamples)"
        }
    }

    /** Frame size handed to the listener, in samples. */
    val frameSize: Int = frameSamples

    private val running = AtomicBoolean(false)

    /**
     * Set by [stop] to ask the capture thread to end, and read by that thread's
     * loop.
     *
     * It is separate from [running] on purpose. [running] is the signal the
     * dispatcher reads to decide the take is finished, and a take is not
     * finished until the capture thread has recovered the tail the resampler is
     * still holding back — the capture thread does that on its own way out and
     * drops [running] there. A stop that dropped [running] itself would let the
     * dispatcher finish on an empty ring before that tail arrived, and the take
     * would come up short by exactly the tail: 15 samples of the 6 400 a 400 ms
     * take at 16 kHz is promised. Which thread wins that race is a scheduling
     * coin flip, so the symptom is a flake rather than a certainty. So the stop
     * asks ([stopRequested]) and the capture thread answers (drops [running]),
     * and the ordering that makes the tail whole is a fact rather than a coin
     * flip.
     */
    private val stopRequested = AtomicBoolean(false)

    private val failureRef = AtomicReference<Throwable?>(null)

    /**
     * The buffer belonging to the take that is current.
     *
     * Each take gets a buffer of its own rather than clearing a shared one.
     * [stop] cannot promise the old session threads are gone — a listener
     * that blocks past [joinTimeoutMs] leaves the dispatcher running — and a
     * dispatcher that outlives its take is blocked inside a read of the
     * buffer it was started with. Sharing one buffer means the next take's
     * writes wake that straggler, it takes a read's worth of the new take's
     * audio, and the samples are gone from the take they belong to: the new
     * take comes up short by exactly what the straggler swallowed. A
     * straggler holding a buffer nobody writes to again cannot take anything
     * from the take that replaced it, so the audio stays whole whether or not
     * the old thread ever retires.
     */
    private val ringRef = AtomicReference(PcmRingBuffer(bufferSamples))

    /**
     * Which take is current. Every session thread carries the number it was
     * started with and retires once it is no longer current.
     *
     * [stop] cannot promise the old threads are gone — a listener that blocks
     * past [joinTimeoutMs] leaves the dispatcher running — and the next
     * [start] reuses the same ring buffer, resampler and `running` flag. A
     * thread that only watched `running` would see it go true again and carry
     * on inside the new take. A session number cannot be reused, so a straggler
     * retires instead of feeding the take that replaced it.
     */
    private val session = AtomicLong(0)

    /**
     * True while the session is still reading audio from the device.
     *
     * This is the session's own progress, not the state of the hardware. It
     * goes false as soon as the capture thread stops reading — which includes
     * a capture that ended by itself, because the device died, failed or
     * stopped producing audio — and that is well before the take has been
     * delivered in full. A false value here is therefore not a statement that
     * the microphone is closed or that the take is complete: the caller must
     * still call [stop], which is what releases the device, marks the
     * indicator dark, and returns only once the take's last frame has been
     * delivered.
     */
    val isCapturing: Boolean
        get() = running.get()

    /**
     * The other half of a session: the device gate, the record of a stop that
     * landed inside a start's open, the teardown latch two stops share, and the
     * open/publish and close/join windows themselves.
     *
     * It holds the same three atomics this class does rather than copies, so
     * both halves of a session are describing ONE take. See
     * [CaptureSessionLifecycle] for why the ordering of two overlapping calls is
     * a separate responsibility from what a take is made of.
     */
    private val lifecycle = CaptureSessionLifecycle(
        source = source,
        indicator = indicator,
        frameSize = frameSize,
        joinTimeoutMs = joinTimeoutMs,
        running = running,
        stopRequested = stopRequested,
        session = session,
        failureRef = failureRef,
    )

    /**
     * The failure that ended the last capture, or null.
     *
     * Capture runs on its own thread, so a driver that dies mid-session has
     * nowhere to throw. It is recorded here instead of being swallowed, and the
     * take is then short by exactly the audio that was never captured.
     */
    val failure: Throwable?
        get() = failureRef.get()

    /** Samples dropped because the listener could not keep up. */
    val droppedSamples: Long
        get() = ringRef.get().droppedSamples

    /**
     * Open the microphone and begin delivering frames to [listener].
     *
     * Returns without waiting for any audio. The listener is called on the
     * dispatcher thread, never on this one.
     *
     * ### A stop that arrives first
     *
     * [stop] is safe at any point in a capture's life, and that includes the
     * window inside this call while the device is still opening: there is no
     * capture thread and no dispatcher yet, so there is nothing for a stop to
     * join. A stop that lands in that window is not lost — it is recorded, and
     * this call acts on it the moment the device comes back: the device is
     * closed, the indicator is marked dark, no thread is spawned and no frame
     * is delivered. The capture is stopped, and [isCapturing] is false when
     * this returns.
     *
     * It RETURNS QUIETLY in that case rather than throwing. The caller issued
     * two legal calls and did nothing wrong, so a racing stop is the
     * implementation's problem to absorb rather than the caller's to be told
     * about. A caller that checks [isCapturing], or simply waits for frames
     * that will not come, sees the same outcome either way.
     *
     * When [stop] returns, [isCapturing] is false — including the stop that
     * landed inside this window while the device was still opening. That stop
     * had no session thread to take, so it had nothing to join and returned
     * early; it drops the session flag anyway, because a stop that has returned
     * has told the caller the capture is stopped and [isCapturing] is where the
     * caller reads that. It does NOT wait for the device gate, and it closes no
     * device: [MicCapture.stop] never touches the gate, so it can return while
     * this call is still inside [MicSource.open]. The close and the mark dark
     * are this call's to make once the open comes back, and it makes them
     * before it returns. A caller that needs the microphone provably shut when
     * stop() returns therefore has no way to get it in this one window; what it
     * has is [isCapturing] false, which is what a restart needs.
     *
     * A start issued after such a stop comes up as a full take of its own. It
     * does not wait for this call to unwind — it queues on the device gate, and
     * this call releases it as soon as it has either published a session or
     * closed what it opened — so the only thing the two share is the device,
     * and only one of them ever has it open.
     */
    override fun start(listener: AudioListener) {
        // Read before anything else, so a stop that has already been answered
        // is not mistaken for one that lands during this call. A stop the
        // caller made before this start is not a stop OF this start, and the
        // capture must still come up.
        val epochAtEntry = lifecycle.stopEpochSnapshot()
        if (!running.compareAndSet(false, true)) {
            throw IllegalStateException(
                "capture is already running; stop() it before starting again",
            )
        }
        // Any thread left over from a previous take retires here, before this
        // take touches the state it shares with them. The number is claimed
        // first so a straggler is already retired by the time its own loop
        // condition is next evaluated.
        val current = session.incrementAndGet()
        failureRef.set(null)
        // A request left over from a take that has ended must not end this one
        // before its first read. Cleared before the threads that read it exist.
        stopRequested.set(false)
        val mine = PcmRingBuffer(bufferSamples)
        ringRef.set(mine)
        // A pipeline per take, for the same reason the ring is per take. Both
        // hold state a thread from the previous take may still be standing
        // inside: [stop] cannot promise the old capture thread is gone, and one
        // still inside convert() — a resample of a big read, say — is mutating
        // the resampler while this take's first read resets it. Sharing the
        // pipeline means that reset happens underneath a live call and one
        // take's samples come out of the other's filter.
        val pipeline = CapturePcmPipeline(
            channelCount = source.channelCount,
            sampleRateHz = source.sampleRateHz,
            suppressor = suppressor,
        )
        // The device gate, the stop record and the teardown latch are the
        // lifecycle's, not this class's: they are about the ordering of two
        // overlapping calls rather than about what a take is made of. See
        // CaptureSessionLifecycle.
        lifecycle.openAndPublish(listener, epochAtEntry, current, mine, pipeline)
    }

    /**
     * Close the microphone and wait for the session to finish.
     *
     * This is where a session is torn down, and it is the only place a device that
     * a session owned is closed and the indicator that session raised is marked
     * dark. The question it asks
     * is whether there is a session to tear down — a session thread that has
     * not yet been joined away — and not whether the capture is still reading,
     * because a capture that ended by itself (the device failed, or stopped
     * producing audio) has already dropped `running` while the microphone is
     * still open, the indicator is still live, and the dispatcher is still
     * holding the take's last frame. Tearing down on `running` alone skipped
     * all three of those: the device stayed open and the indicator said
     * "recording" forever, and the tail was delivered to whoever happened to
     * be listening half a poll later rather than to the caller that stopped.
     *
     * So when a capture has ended by itself, this closes the device, joins
     * both threads — the tail is delivered before this returns — and marks the
     * indicator dark, exactly as it does for a capture that is still running.
     *
     * With no session to tear down — never started, already stopped, or a stop
     * racing a start that is still inside the device's open — it closes no
     * device and marks no indicator: no second close and no second mark, so
     * teardown resources are released once and once only. It is NOT a no-op
     * otherwise. A stop that lands inside a start is still RECORDED: it counts,
     * and the start it raced acts on that count rather than spinning the
     * session up behind the caller's back. And it still ends the session — when
     * this returns, [isCapturing] is false whatever it found, because a caller
     * that has been told "stopped" and then reads `true` is holding a capture
     * it cannot restart and has no handle to. The early return is for the
     * device and the indicator, never for the session flag.
     *
     * Two stops at once are legal, and exactly one of them does the teardown.
     * The other waits for it: it does not return early and tell its caller the
     * microphone is shut while the close and the joins that are actually
     * shutting it are still running. That wait is bounded by [joinTimeoutMs]
     * and reported in [failure] if it expires, so a stop cannot hang forever
     * behind a teardown that has itself hung.
     *
     * Two calls from a recording listener are exceptions. A stop() called from
     * a listener on the thread that is already running the teardown returns at
     * once, because that thread is the one that will finish the teardown and a
     * wait there could never succeed. A start() called from the "stopped"
     * listener begins a new take, so this stop() returns with that take running
     * and [isCapturing] true. See the module card's listener re-entrancy gotcha.
     *
     * Returns once both threads have stopped, or once [joinTimeoutMs] has
     * passed — in which case the stuck thread is recorded in [failure] and the
     * indicator is still marked dead, because the microphone really is being
     * closed either way.
     */
    override fun stop() = lifecycle.stop()

    companion object {
        /** 20 ms at 16 kHz. */
        const val DEFAULT_FRAME_SAMPLES: Int = 320

        /** 2 s at 16 kHz: enough to ride out a slow consumer. */
        const val DEFAULT_BUFFER_SAMPLES: Int = 32_000

        /** How long [stop] waits for the session threads. */
        const val DEFAULT_JOIN_TIMEOUT_MS: Long = 2_000L

        /** Name of the thread that reads the device. */
        const val CAPTURE_THREAD_NAME: String = "breaker-mic-capture"

        /** Name of the thread that frames audio for the listener. */
        const val DISPATCH_THREAD_NAME: String = "breaker-mic-dispatch"
    }
}
