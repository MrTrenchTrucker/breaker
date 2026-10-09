# AGENTS.md — android/modules/audio/

## Purpose

Mic capture, VAD, noise suppression, WAV encode (16 kHz mono PCM). Capture microphone audio and prepare it for STT. Produces 16 kHz mono
PCM (float32 for the sherpa-onnx on-device engine; WAV for server upload).
The on-device engine is sherpa-onnx, not whisper.cpp: see
`android/modules/stt-ondevice/AGENTS.md`, which owns the sherpa-onnx local
engine and the float32 PCM it consumes.

**Build phase:** Phase 2 (built, on `main`). Needs first: `core`.

## Owns
Mic capture, VAD, noise suppression, WAV encode (16 kHz mono PCM).

## Public Interface `core.AudioSource`.

**In:** mic permission granted by user.
**Out:** PCM frames to a ring buffer; optional WAV file for server mode.

**Public types** — this list is the module's registry line, kept in the same
order and spelling:

- `MicCapture`
- `Pcm16WavEncoder`
- `MicSource`
- `MicSourceException`
- `NoiseSuppressor`
- `PassThroughNoiseSuppressor`
- `AdaptiveGateSuppressor`
- `RecordingIndicator`
- `AndroidMicSource`

**Components** — public first, then implementation-only (`internal`: not part of
the registry line, and not reachable from outside the module):

- `MicCapture` — public; AudioRecorder/MediaRecorder session; drives a
  default-priority capture thread
- `MicSource` — public; the capture source seam the capture session is driven through
- `MicSourceException` — public; raised when a source fails or stops producing audio
- `NoiseSuppressor` — public; an optional neural suppressor (none chosen yet);
  skip if the AAR is not built with one
- `PassThroughNoiseSuppressor` — public; the no-op `NoiseSuppressor` used when no neural
  suppressor is present
- `AdaptiveGateSuppressor` — public; gate-based suppression
- `RecordingIndicator` — public; mic indicator surfaced in UI while recording
- `Pcm16WavEncoder` — public; PCM → WAV (for server upload)
- `AndroidMicSource` - public; the one factory for a `MicSource` over the phone's recording
  inputs: it prefers a Bluetooth microphone, then a wired or USB one, then the phone's own,
  and falls back silently, also in the middle of a take
- `PcmRingBuffer` — internal; bounded buffer between capture and consumer
- `AudioResampler` — internal; resamples to the 16 kHz mono contract at the boundary
- `ChannelDownmixer` — internal; folds input channels to mono
- `Vad` — internal; decides speech per frame and trims the silence around a take
- `EnergyVad` — internal; the lightweight energy VAD behind `Vad`
- `TrimResult` — internal; the PCM a `Vad` kept plus the span it kept it from
- `CaptureLoop` — internal; capture-thread loop collaborator
- `DispatchLoop` — internal; dispatch-thread loop collaborator
- `CapturePcmPipeline` — internal; capture-thread wiring collaborator
- `CaptureSessionLifecycle` — internal; coordinates one capture session against
  overlapping calls: owns the device gate (one `MicSource.open` at a time), the
  stop epoch (a stop recorded as a COUNT, so a start still inside the device's
  open can see the stop that raced it), the teardown latch that makes two
  racing stops both mean "the microphone is shut" by the time both return, the
  capture and dispatch thread references, and `openAndPublish`, `stop`,
  `joinWithin` and `closeQuietly`. `MicCapture` keeps what a take IS.
- `IndicatorLiveOpen` - internal (the class and its one operation are both `internal`); the first step of bringing a take up (mark the indicator
  live, then open the device, as one guarded step that undoes itself on a failure). It was
  moved out of `CaptureSessionLifecycle` unchanged in logic so that file stays under its size cap.
- The microphone selection seam, three types and one policy:
  - `MicDevice`, `MicDeviceKind`, `MicDeviceSupplier` - internal; a recording input (its kind and
    platform id), the three kinds (`BLUETOOTH`, `WIRED`, `BUILT_IN`), and the list of inputs that
    can record right now
  - `MicRoutePolicy` - internal; the choice itself, a pure function: first Bluetooth, else first
    wired, else first built-in, else none
  - `RoutedMicSource` - internal; the `MicSource` that applies the policy at open and again when a
    device goes away, over a `MicInputPort`. `MicRoutePolicy` and `RoutedMicSource` together are
    the selection seam: everything that decides which microphone is used is here, in plain
    Kotlin that a JVM test drives
  - `MicInputPort` - internal; the seam below `RoutedMicSource`: one recording device at a time
  - `AudioRecordMicPort` - internal; the `MicInputPort` over the platform's `AudioRecord`, in the
    file that also holds `AndroidMicSource`. It is the only file in the module that names the
    platform framework

**Threading:** capture runs on one dedicated `Thread` at default priority —
nothing raises it — and consumers are fed by a second, separate plain `Thread`.
There is no executor in the module. Never block UI.

**Why these primitives and not coroutines.** The threading above is a fact about
this module's code, and it is the worked example of the narrow exception class
named in the root order (root `AGENTS.md` section 6). The order holds for every
concurrent thing here that is not one of these four cases, and each case is
decided by a site rather than by preference:
- **No dispatcher supplies a dedicated thread at default priority.** The shared
  default pool reuses its workers for unrelated work, so the capture and
  dispatch threads are created and named by hand in the session lifecycle.
- **`Mutex` is non-reentrant, has no timed acquire and no `wait`, and
  `withLock` takes a suspend lambda.** It therefore cannot carry the
  re-entrant `deviceGate` lock or its bounded `tryLock`, the `teardownInFlight`
  latch and its bounded await, the critical sections that must not split around
  the paired thread publication, `joinWithin` and the stuck-thread record, the
  drain in the capture loop's exit path, the ordered listener fan-out, or the
  ring buffer's `wait` and `notifyAll` pairs.
- **`withTimeout` and `select` throw, where these sites record and return.** A
  bounded give-up here is reported through `failureRef`, where a caller reads
  it, rather than raised at a caller that did nothing wrong.
- **`CancellationException` is a `Throwable`,** so at the interrupt sites it
  would fall into the failure-recording arm and record an ordinary cancellation
  as a capture failure; the interrupt is instead classified as teardown and
  re-armed, on the capture loop, the dispatch loop and the ring buffer alike.

Simple atomic visibility flags are not required to migrate where there is no
real gain to be had: the recording flag the indicator publishes is a plain
`Boolean` that no suspending primitive would improve on. The `captureThread` and
`dispatchThread` references are a different case and stay for the first reason
above, not for this one.

## Choosing the microphone

`AndroidMicSource.create(audioManager)` returns a `MicSource`. It is the only public type that
touches the platform, and it asks the platform for 16 kHz mono 16-bit PCM; nothing is resampled in
the driver, and a platform that refuses that format raises `MicSourceException`.

- **Order.** A Bluetooth microphone first, then a wired one (wired headset, USB headset, USB
  device), then the phone's own microphone. Two of a kind: the one listed first wins. This is
  `MicRoutePolicy`, applied by `RoutedMicSource`.
- **Silent fallback at open.** If opening the chosen device fails and it was not the phone's own
  microphone, the source tries the phone's own microphone once (the system default input if none is
  listed). There is no prompt, no error, no callback and no log on a fallback. Only when that also
  fails does `open()` throw `MicSourceException`.
- **Silent fallback in the middle of a take.** A device that goes away is absorbed inside `read()`:
  the source lists the inputs again, picks again, closes the old input, opens the new one and reads
  from it in the same call. The take does not end and no negative or empty read comes out of a switch.
  A negative read is treated as the same loss when the device in use is no longer listed (or the port
  says the route was lost), once per call. Any other negative read, a second failure in the same call,
  or a failure on the system default input ends the take with `MicSourceException`.
- **The switch on a vanished device.** A read that fails with a negative driver code while the chosen
  device is gone from the device list (or the route-lost flag is set) is absorbed: the source closes,
  re-picks and reopens once and reads again in the same call. A second failure, a failure with the
  device still listed, or a failure on the system default input is a real failure
  (`MicSourceException`).
- **Permissions are the caller's.** `RECORD_AUDIO` is needed to record at all. A Bluetooth microphone
  also needs `BLUETOOTH_CONNECT`; without it the platform lists no Bluetooth input, so the wired or
  phone microphone is used, silently. Asking the user for either permission is onboarding's job, not
  this module's.
- **Bluetooth routing.** On API 31 and above the chosen input is set as the communication device and
  cleared at close; on API 30 the Bluetooth link is started with `startBluetoothSco` and stopped at
  close.

## End of a take

`MicCapture` takes an optional trailing constructor parameter `onTakeEnded: ((Throwable?) -> Unit)?`,
default null (nobody is told, and behaviour is exactly as before). It adds no public type and is on
`MicCapture` only: code that holds a plain `AudioSource` does not see it.

- **When.** Once per take that got as far as running threads, on the dispatch thread, after the
  take's last frame has been delivered. It fires both when the caller called `stop()` and when the
  take ended by itself (the device failed, stopped producing audio, or the frame listener threw).
- **What it carries.** The failure recorded so far, or null. It is a snapshot: a failure that
  `stop()` records afterwards is only in `MicCapture.failure`.
- **Not called** for a start that threw, for a `stop()` that landed inside the device open, or for a
  take that a later `start()` has replaced.
- **A take that ends by itself does not release anything.** The device stays open and the indicator
  stays lit until the caller calls `stop()`. The callback only tells the caller that the stop is owed.
- **Threads.** The callback runs on the thread that `stop()` joins, so it must hop to another thread
  before it calls `stop()`; calling `stop()` inline makes `stop()` wait for itself until the join
  time-out and then record a false failure. Keep the callback short, for the same reason. It must not
  call `start()` while a self-ended take is still owed its `stop()`.
- **A throwing callback** is recorded in `failure` when nothing is recorded yet, and is never raised.
  The thread ends normally and `stop()` is unaffected.

## Invariants
- Record → clean PCM/WAV. VAD trim is built and tested inside this module
  but is NOT applied to a take today: `Vad` and `TrimResult` are `internal`,
  and nothing outside the module calls them yet (the core port that will let
  `DictateUseCase` call them does not exist yet). See the VAD trim gotcha.
- A 60 second capture delivers every sample with no drop and no gap, proven
  on the JVM against a fake `MicSource` and NOT on a target device
  (`MicCaptureSustainedTest`): 960000 samples delivered in total,
  `droppedSamples == 0`, every sample's value equal to its own index (which is
  what rules out gaps, duplicates and reordering), and `capture.failure == null`.
  A real microphone, a real resampler and real device timing are not covered by
  this test; do not read it as on-device verification.
- Mic indicator surfaced in UI while recording (privacy, T5).
- The microphone yields to every other app: when any other app or a phone call
  wants it, the capture releases it at once and reports why, with no prompt and
  no retry while it is in use (F37, ADR-022) (not built yet: device check
  owed for an incoming call, an outgoing call and another recording app).
- No `android.*` class is named anywhere in the module except `AudioRecordMicPort.kt`; this is
  checked by `AudioConfinementGateTest` on the source text, on the test sources, and on the
  compiled classes.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Transcription (stt-*)
- Phrase matching (phrases)

## Test Locations
- Unit: `android/modules/audio/src/test/kotlin/dev/breaker/dictation/audio/`
  — plain JUnit 4, run with `./gradlew :android:modules:audio:test`
  Added for the microphone choice, the end-of-take callback and the stop-after-read rule: `MicRoutePolicyTest`, `RoutedMicSourceSelectionTest`,
  `RoutedMicSourceFallbackTest`, `RoutedMicSourceVanishTest`, `RoutedMicSourceSwitchLimitTest`,
  `RoutedMicSourceFailureTest` (helpers: `RoutedMicTestSupport`), `AudioConfinementGateTest` (rules:
  `AudioConfinementRules`), `MicCaptureTakeEndTest`, `MicCaptureTakeEndContainmentTest`,
  `MicCaptureTakeEndReplacedTest` (helpers: `MicCaptureTakeEndSupport`), `MicCaptureNoCallbackThreadTest`,
  `MicCaptureStopDuringReadTest`, `MicCaptureStopDuringReadErrorTest` (fake: `ThrowingAfterCloseMicSource`),
  `RoutedMicSourceIdentityTest`, `CaptureLoopInterruptTest`.
  `RoutedMicSourceIdentityTest` checks that the source follows the device it really opened, and that a replaced device with a new id counts as gone.
  `CaptureLoopInterruptTest` checks that an interrupt raised inside a read is handed back to the capture thread, also when a stop is closing the read.
- Contract: `tests/contract/test_audio_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_audio_contract.py`
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement
Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## How This Module Is Built
One engineer owns this module and delivers it as one pull request, working on
one module at a time. The owner does not write the module's code. The owner:
- splits the work into sub-modules and has sub-agents write each one, code and
  tests;
- coordinates and orchestrates those sub-agents, checks every piece of their
  work, and sends back anything that is wrong until it is right;
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

## Known Gotchas
- 16 kHz mono PCM is the contract — resample at the boundary, not downstream.
- A `stop()` closes the device while the capture thread may be inside `read()`, and a real device
  then fails that read, by throwing or by returning a negative code. `CaptureLoop` treats a read
  that fails after a stop was requested as the clean end of the take, and records nothing. With no
  stop requested the same failure is recorded in `failure` as before. The rule is on the read call
  and its return code only: the failure for 500 consecutive empty reads is not hidden by a stop.
  `stop()` sets the stop flag before it closes the device, so a failure caused by the close always
  sees the flag. Proven on the JVM with a fake that fails its read when the device is closed.

- A take ends short by half a filter kernel unless the resampler is drained.
  The windowed sinc reaches half its width ahead of the read point as well as
  behind it, so a read point whose forward taps have not arrived cannot be
  interpolated yet and is held back into the next call. Mid-capture those
  samples are one more read away; at the end of a take they never arrive, and
  every take would come up short by the samples the device did produce — 15 at
  a rate of one and 5 at 48 kHz -> 16 kHz with the default 32 taps, about
  0.3 ms of output at 16 kHz. The end of a take drains those read points over a
  signal that is zero past the last sample, which adds no duration and pads no
  frame: the total stays exactly ceil(N * toSampleRateHz / fromSampleRateHz)
  output samples for a take of N input samples.
- The drain has to run on the capture thread, on the exit path the capture
  takes when it concludes by itself (the device failed or stopped producing
  audio) as well as when the caller stops it. Draining in stop() alone covers
  only the endings a caller asked for, and draining on another thread reaches
  into state the capture thread is still using.
- VAD trim reaches dictation only through a future core port, and that port does
  not exist yet. `DictateUseCase` is the one that will call it; this module is
  what will implement it, with `EnergyVad`. Until that port lands, `Vad` and
  `TrimResult` are `internal` here on purpose: the behaviour is built and
  tested inside the module, but nothing outside the module can reach it, so a
  recorded take is NOT trimmed on its way to transcription today. This is an
  absence of wiring, not a defect in the VAD — the VAD's own tests pass, and a
  recorded take that is not trimmed is expected until the port lands. The port
  is a change to a core interface and is deliberately NOT part of this change.
- A listener that throws surfaces in ALL THREE places, and which one a caller
  sees depends on which way the throw came. From the way UP, `start()`
  rethrows it to its own caller. It is also recorded in `MicCapture.failure` by
  `compareAndSet(null, e)` under a `session.get() == current` guard, because
  capture runs on threads of its own and an exception raised on one of them has
  nowhere else to go — a caller that saw only a null would believe the
  microphone opened cleanly — and because that guard stops a straggler from a
  take that has already been replaced from stamping its own failure on the next
  take, which would read a clean failure as its own history.
  When a start fails, its own `markRecordingStopped()` is guarded separately; a
  throw there is attached as suppressed to the original cause. From the way DOWN,
  `stop()` rethrows it to its caller, but only AFTER `closeQuietly()`, the joins
  and the stuck-thread record have completed: the throw is held rather than
  raised where it was caught, because a listener throwing at that point used to
  skip the record entirely, making the one failure that says the microphone may
  still be open the one failure a throwing listener could suppress.

- Indicator listeners run on the thread that called start()/stop(); the audio
  listener runs on the dispatch thread, never on the caller's. From a 'stopped'
  listener, start() begins a new take and the outer stop() returns with that
  take running. From a 'started' listener, start() throws 'already running'
  and the outer start is unwound: the device is never opened, isCapturing is
  false and the exception is surfaced. stop() from either listener is safe.
  From a 'stopped' listener it returns at once, because the teardown records
  which thread owns it; without that, a re-entrant stop() would wait on a
  latch only its own caller can count down and then record a false failure.
  From a 'started' listener it is a stop that raced the start: the start still
  opens the device, then sees the stop, closes it again and starts nothing. A
  listener should post work elsewhere rather than call start()/stop() inline.

- The `NoiseSuppressor` is injected, so every take of one `MicCapture` shares
  ONE suppressor instance and the per-take pipeline does not close that: a
  straggler capture thread still inside `suppress()` is running the next take's
  suppressor state. Per-take pipelines isolate the resampler only.
- **Found, not fixed.** The core `AudioSource` port carries no drop count and
  no failure. `MicCapture` reports both (`droppedSamples`, `failure`), but only
  on its own type, so code that holds a plain `AudioSource` sees no drops and
  no failure even when samples were lost. The fix (drops and failure on the
  port, or a small companion port) is a core interface change and comes with
  the next core change.
- `EnergyVad`'s speech threshold is the sum of its noise floor and an 8 dB margin, capped at
  the floor's `-25 dBFS` ceiling. The cap is what keeps a sustained level that is genuinely
  below the margin still reading as speech: without it the pinned floor plus the margin set a
  threshold of −17 dBFS, and steady speech below that was silently discarded after the floor
  pinned. Below the ceiling the threshold still tracks the floor, which climbs at a fixed
  25 dB/s whatever the sample rate — so in a quiet room the floor reaches its ceiling
  quickly and anything quieter than −25 dBFS never reads as speech however long it sustains.
  The rate is held per second rather than per frame, because the 320-sample window is 20 ms
  at 16 kHz and 6.67 ms at 48 kHz: a rise or a hangover stated per frame is only the stated
  figure at the rate it was written for, and off that rate the floor learns a passing truck
  as the room at 48 kHz while the hangover is twice as long at 8 kHz and a third as long at 48 kHz.
  Population today is zero (`Vad` is `internal` and unwired), so nothing is broken now; it
  bites the day the core VAD port lands. The ceiling itself is still the open half: raising
  the floor only on non-speech frames, or letting a level that has sustained below the margin
  through, are both untried. Not fixed in this round.
- `AudioResampler`'s exact output count, and samples identical chunked vs
  whole, hold bit-for-bit only for rates whose ratio is exact in a double —
  48k → 16k among them. At 44.1k → 16k, chunked processing of a whole 3 s
  take in 40 ms chunks yields 48001 samples against 48000 for the same take
  processed whole: ONE sample over the take, not per chunk. The output is
  still the right length to within one sample, and the difference is
  accumulated rounding at a rate that is not exactly representable, not a
  leak.

## Not verified on a device

Everything above about microphone choice and end of take is proven on the JVM against fakes. The
adapter (`AudioRecordMicPort.kt`) cannot run there, so these are by reading only and need a phone:

- Bluetooth start on API 30: `startBluetoothSco` is asynchronous, so the first reads may still come
  from the phone's microphone until the link is up.
- `setCommunicationDevice` (API 31 and above) accepting the chosen input; the code looks up the
  matching device in the platform's communication list first and falls back to the input itself, and
  ignores a refusal.
- Wired detection: which platform input types the phone reports for a real headset, against the types
  that are mapped (wired headset, USB headset, USB device).
- The Android 12 and above communication-device path as a whole.
- The fallback on a real headset unplug: whether the device callback or a negative read comes first,
  and that either way the take continues on the next device.
- Whether a caller `stop()` on a real device ends the take cleanly (the stop-after-read rule above).
- `AudioDeviceCallback` calls arriving on the main looper, and a callback queued before `close()` arriving
  after the next `open()`.
- The 16 kHz request on real hardware, and what a refusal looks like.
- The wording of a permission failure: a missing `RECORD_AUDIO` shows up as the platform refusing the
  format, so the message may mislead.
