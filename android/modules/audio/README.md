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
