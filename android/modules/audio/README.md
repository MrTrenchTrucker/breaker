# Audio — README

Capture microphone audio and prepare it for speech-to-text. Produces 16 kHz
mono audio, in the form the downstream consumers need.

## What this module does

- **Microphone capture.** `MicCapture` opens a `MicSource` and reads frames off
  the device on its own schedule, on a capture thread of its own. `start`
  returns immediately and never calls the listener on the caller's thread;
  `stop` is safe at any point in a capture's life — and where there is a session
  to tear down it tears it down: it closes the device, marks the indicator dark,
  and joins both threads before it returns. A stop that lands while the device is
  still opening has no session thread to join yet, so it returns having dropped
  the session flag, and the `start` it raced closes what it opened and marks the
  indicator dark once that open comes back. `MicSource` is the port a real
  microphone adapts to, and
  `MicSourceException` is what a source that cannot be opened throws.
- **Choosing the microphone.** `AndroidMicSource.create(audioManager)` gives a
  `MicSource` for a real phone. It uses a Bluetooth microphone if one is
  connected, otherwise a wired or USB one, otherwise the phone's own. It never
  asks and never complains when it falls back to a lesser one, at the start of a
  take or in the middle of one: if the headset is unplugged while recording, the
  next read carries on from the next best microphone and the take does not end.
  The app holds the permissions: `RECORD_AUDIO` to record at all, and
  `BLUETOOTH_CONNECT` for a Bluetooth microphone (without it the phone sees no
  Bluetooth microphone and the wired or phone one is used). The choice itself is
  `MicRoutePolicy`; only `AudioRecordMicPort.kt` talks to the Android framework.
- **Knowing when a take ended.** `MicCapture` can be given an `onTakeEnded`
  callback. It is called once per take, after the last frame, whether the caller
  stopped the take or it ended by itself (the microphone failed, went quiet, or
  the frame listener threw), with the failure or null. A take that ended by
  itself still has the microphone open and the indicator lit until the app calls
  `stop`, and the callback runs on the thread `stop` waits for, so the app must
  hand the `stop` call to another thread. Leaving the callback out changes nothing.
- **Resampling.** `AudioResampler` converts the device's sample rate to the
  16 kHz mono the rest of the pipeline is specified in.
- **Voice activity detection.** `EnergyVad` trims leading and trailing silence
  so what reaches the engine is speech.
- **Noise suppression.** `NoiseSuppressor` is the port, with
  `PassThroughNoiseSuppressor` for no processing and `AdaptiveGateSuppressor`
  for a level-driven gate.
- **The ring buffer.** `PcmRingBuffer` sits between the capture thread and the
  consumer. Neither blocks: when the consumer falls behind the oldest audio is
  dropped and counted, rather than the capture thread stalling. A take that
  lost audio says so in `droppedSamples` rather than returning a shorter
  recording that sounds complete.
- **Encoding and output.** `Pcm16WavEncoder` writes the take as a 16 kHz mono
  16-bit PCM WAV file, for upload or playback.
- **The mic indicator.** `RecordingIndicator` carries the fact that the
  microphone is open, so a screen bound to it never shows "not recording" while
  the mic is live and never shows "recording" once it is shut.

## What this module does not do

Transcription lives in the `stt-*` modules; phrase matching lives in
`android/modules/phrases`. On-device speech-to-text runs on **sherpa-onnx** and
is documented in `android/modules/stt-ondevice` — start there for the engine
reference, model lifecycle and checksums. WAV encoding is the one piece that
stays here rather than moving downstream: `Pcm16WavEncoder` is this module's
implementation of core's `WavEncoder` port, and the container format is
written in exactly one place.

Full module card: `AGENTS.md` in this folder.
