# AGENTS.md — android/modules/audio/

## Purpose

Mic capture, VAD, noise suppression, WAV encode (16 kHz mono PCM). Capture microphone audio and prepare it for STT. Produces 16 kHz mono
PCM (float32 for whisper.cpp JNI; WAV for server upload).

## Owns
Mic capture, VAD, noise suppression, WAV encode (16 kHz mono PCM).

## Public Interface `core.AudioSource`.

**In:** mic permission granted by user.
**Out:** PCM frames to a ring buffer; optional WAV file for server mode.

**Components:**
- `MicCapture` — AudioRecorder/MediaRecorder session, high-priority capture thread
- `RingBuffer` — bounded buffer between capture and consumer
- `Vad` — silero VAD (whisper.cpp built-in) or lightweight energy VAD; trims silence
- `NoiseSuppressor` — optional RNNoise (only if AAR built with it; else skip)
- `WavEncoder` — PCM → WAV (for server upload)

**Threading:** capture on dedicated thread; consumers on executor. Never block UI.

## Invariants
- Record → VAD trims leading/trailing silence → clean PCM/WAV.
- Continuous 60 s recording without glitches on target device.
- Mic indicator surfaced in UI while recording (privacy, T5).

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Transcription (stt-*)
- Phrase matching (phrases)

## Test Locations
- Unit: `tests/unit/android/audio/`
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
