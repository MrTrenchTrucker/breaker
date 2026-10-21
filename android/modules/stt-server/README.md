# Stt Server - README

Server path of speech-to-text. It sends one recording to Breaker's own job
queue (`whisper-server`) and returns the text. It never talks to the Whisper X
container directly.

## Use

    val engine = SttServerEngine(
        serverUrl = { serverAddress },
        apiKey = { apiKey },
    )
    val result = engine.transcribe(request)

The app passes two providers, read once per call: the base address (https only)
and the API key. The module reads no settings and no storage. The app does not
use this engine yet; wiring it is a separate change.

## What a call does

1. Checks the address (https, a host, no user information, no spaces), the key
   and the form fields. A failed check sends nothing.
2. Posts the WAV to `/v1/audio/transcriptions` once and expects a job id.
3. Polls `/v1/jobs/{job_id}` with growing pauses until the job is done, has
   failed, or the time runs out.
4. Returns `SttResult.Success` with the text, segments and language, or
   `SttResult.Failure` with an error and one fixed sentence.

## Failures

| Error | Examples |
|---|---|
| `OTHER` | key refused, recording too large, unreadable answer, job failed, bad address or key |
| `SERVER_UNREACHABLE` | server not reached, TLS failure, server not taking work, no transcription endpoint |
| `TIMEOUT` | the time budget ran out, or a read timed out |

The sentences are in `FailureSentences.kt`. Apart from the cleaned failure text
of a failed job, a failure sentence contains no key, no URL, no server response
body and no dictated text.

## Limits

`ServerLimits` holds the time and size limits. The budget is 60 s plus 3 times
the clip length, at most 300 s, measured from the start of the call. Each call
has a connect timeout of 1.5 s. These defaults are starting values; they were
not measured on a device or against a real server.

Full module card: `AGENTS.md` in this folder.
