# AGENTS.md — android/modules/audio/

## Purpose

Mic capture, VAD, noise suppression, WAV encode (16 kHz mono PCM). Capture microphone audio and prepare it for STT. Produces 16 kHz mono
PCM (float32 for the sherpa-onnx on-device engine; WAV for server upload).
The on-device engine is sherpa-onnx, not whisper.cpp: see
`android/modules/stt-ondevice/AGENTS.md`, which owns the sherpa-onnx local
engine and the float32 PCM it consumes.

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

**Threading:** capture runs on one dedicated `Thread` at default priority —
nothing raises it — and consumers are fed by a second, separate plain `Thread`.
There is no executor in the module. Never block UI.

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

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Transcription (stt-*)
- Phrase matching (phrases)

## Test Locations
- Unit: `android/modules/audio/src/test/kotlin/dev/breaker/dictation/audio/`
  — plain JUnit 4, run with `./gradlew :android:modules:audio:test`
- Contract: `tests/contract/test_audio_contract.py`

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

- Listeners run on the thread that called start()/stop(). From a 'stopped'
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
- `EnergyVad`'s floor rises 0.5 dB per 20 ms frame — about 25 dB/s —
  against an 8 dB speech margin. Steady speech quieter than roughly
  −17 dBFS therefore stops reading as speech after about half a second and
  does not recover. Population today is zero (`Vad` is `internal` and
  unwired), so nothing is broken now; it bites the day the core VAD port
  lands. Fix when it does: raise the floor only on non-speech frames, or
  at a far slower rate. Not fixed in this round.
- `AudioResampler`'s exact output count, and samples identical chunked vs
  whole, hold bit-for-bit only for rates whose ratio is exact in a double —
  48k → 16k among them. At 44.1k → 16k, chunked processing of a whole 3 s
  take in 40 ms chunks yields 48001 samples against 48000 for the same take
  processed whole: ONE sample over the take, not per chunk. The output is
  still the right length to within one sample, and the difference is
  accumulated rounding at a rate that is not exactly representable, not a
  leak.
