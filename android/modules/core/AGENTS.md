# AGENTS.md — android/modules/core/

## Purpose

The pure-Kotlin domain core: the models, the ports (interfaces) that feature
modules implement, and the use cases that run a dictation.
**No Android imports, no I/O** — testable with plain JUnit.

**Build phase:** Phase 1 (built, on `main`). Needs first: nothing; every feature module depends on it.

**Ports (interfaces) feature modules implement** (`port/`):
- `SttEngine` — `transcribe(SttRequest): SttResult` (blocking; callers handle threading)
- `AudioSource` — `start(AudioListener)`, `stop()` — streams 16 kHz mono float PCM;
  the `AudioListener` receives each frame through `onFrame`
- `WavEncoder` — `encode(FloatArray): ByteArray` (the WAV bytes an upload needs)
- `Formatter` — `format(rawText): String` (turns a raw transcript into structured text)
- `TextCommitter` — `commit(CommitRequest): CommitOutcomeResult` (accessibility insert or clipboard)
- `CommitOutcomeResult` — the value `TextCommitter` returns: the outcome plus a short reason
- `HistoryStore` — `save(Transcription)`, `list(limit)`, `delete(id)`
- `SettingsStore` — `load()`, `save(AppSettings)`
- `ConnectivityProbe` — `isServerReachable(): Boolean` (TTL-cached)
- `PhraseTrigger` — `start(onPhrase)`, `stop()`, `isListening`; the callback gets a
  `PhraseEvent`. `PhraseEvent.Wake` carries nothing: the capture starts after the wake
  phrase, so there is nothing of it to trim. `PhraseEvent.Send` carries `trimBeforeMs`, the
  milliseconds from the start of the capture to where the phrase began (audio before it is
  kept, the phrase onward is dropped; null keeps the whole capture). There is no cancel
  phrase; cancelling is `DictateUseCase.cancel()`, and a cancel phrase would be a new
  `PhraseEvent`. Core pins its half of this: given an offset the capture is cut there, and
  no offset keeps it whole.
- `PhraseTraining` — `recordSample`, `upload`, `downloadModel`
- `AuthService` — `register`, `login`, `logout`, `currentSession`
- `CryptoService` — `unwrapDek`, `encrypt`, `decrypt`, `toEncryptedText`
- `KeyDerivation` — `deriveKeys(password, salt, kdfParams)`: derives the
  key-encryption key and the auth verifier in one call and returns both as
  `DerivedKeys`; the crypto module implements it and holds the parameter limits
  (core carries the values and judges nothing). `CryptoService` is unchanged.
- `SyncService` — `pushPending()`, `enqueue(Transcription)`
- `UpdateChecker` — `check(force)`, `install(release)`, `rollback()`
- `Clock` — `nowEpochMillis()`, and `IdSource` — `newId()`, so the domain never reads
  a clock or invents an identifier itself

**Models** (`model/`):
- `Transcription(id, text, source: LOCAL|SERVER, model, durationMs, createdAt)` and
  `TranscriptionSource`; `text` may be empty, since a quiet recording transcribes to nothing
- `DictationSession(state, lastError, lastTranscription)` with `DictationState`
  (IDLE|ARMED|RECORDING|TRANSCRIBING|SENDING|ERROR), and `DictationResult` (Success|Failure)
- `AppSettings(mode: AUTO|LOCAL|SERVER, modelSize, serverUrl, apiKeyRef,
  wakeGestureEnabled, tilePosition, language, preloadModel, formattingEnabled,
  themeMode: SYSTEM|LIGHT|DARK)`, `SttMode`, `ThemeMode`, `TilePosition`
- `SttRequest`, `SttResult`, `SttSegment`, `AudioFormat` (16 kHz, one channel), and
  `SttError(LOCAL_MODEL_MISSING, SERVER_UNREACHABLE, TIMEOUT, OTHER)`
- `CommitRequest` (never empty) and `CommitOutcome(COMMITTED|COPIED|FAILED)`
- `CipherText` (ciphertext may be empty; nonce and tag may not), `DataEncryptionKey`,
  `WrappedDek`, `EncryptedText` (its ciphertext string may be empty; whitespace-only is
  refused)
- `KdfParams(memoryKib, iterations, parallelism, outputLength, version)` — the
  stored key-derivation settings; a plain carrier that accepts any Int and
  judges nothing, because the limits live in the crypto module, not in core
- `KeyEncryptionKey` and `AuthVerifier` — each exactly 32 bytes, copied when
  built and copied when read, with `wipe()` and a `toString` that never prints
  the bytes; `DerivedKeys` is the pair, and its `wipe()` wipes both
- `KdfRefusal` — why a derivation was refused (MEMORY_BELOW_FLOOR,
  MEMORY_ABOVE_CEILING, ITERATIONS_BELOW_FLOOR, ITERATIONS_ABOVE_CEILING,
  PARALLELISM_BELOW_FLOOR, PARALLELISM_ABOVE_CEILING, OUTPUT_LENGTH_NOT_32,
  BAD_SALT_LENGTH, UNSUPPORTED_VERSION); `KdfRefused` is the
  IllegalArgumentException that carries one
- `PhraseKind`, `PhraseEvent(Wake|Send(trimBeforeMs))`, `PhraseModel`, `PhraseSample`,
  `TrainedPhraseModel`
- `AuthSession`, `UserRole`, `TokenScope`
- `SyncState`, `SyncReport`
- `ReleaseInfo`, `UpdateCheckResult`

**Use cases** (`usecase/`):
- `DictateUseCase` — orchestrates audio → routing → STT → formatting → result (state machine).
  **Local mode with no model → `SttError.LOCAL_MODEL_MISSING` surfaced clearly.**
  Never falls through to a server or cloud path. Formatting follows the route the
  dictation actually took: `serverFormatter`, which may be a cloud service, is used only
  on the server route, an on-device transcript goes to `localFormatter`, and
  `formattingEnabled = false` means the text is committed as dictated on either route.
  Constructor: `DictateUseCase(settings, probe, localEngine, serverEngine, serverFormatter,
  wavEncoder, clock, ids, localFormatter)`. `serverFormatter` was `formatter`; `localFormatter`
  is new and required, with no default, so a caller has to decide what runs on the phone.
  A move the state machine
  refuses is a wiring bug and throws; anything an adapter throws after that comes back
  as a `DictationResult.Failure` (`SttError.OTHER`, the detail is the exception's class
  name, never its message; an `Error` still propagates). `stopCapture` refuses a negative `trimBeforeMs` with an
  `IllegalArgumentException` before it stops the source or discards any audio; an offset at
  or past the end of the capture keeps the whole capture, however large. `cancel` stops the
  source, drops the buffered audio and returns an IDLE session.
- `SendUseCase` — text → `TextCommitter` → history save (and `SendResult`).
  History is written on every outcome, including a failed commit, a committer that
  throws (checked exceptions included; an `Error` propagates), and a dictation whose
  text is empty (which is never handed to the committer). A throwing HistoryStore is
  contained the same way: send() never throws after the commit; the outcome is the
  committer's, the detail says the dictation was not saved to history, and the
  session follows the commit.
- `LocalModeEgress` — the pure rule that keeps a phone transcript away from cloud
  formatting, used by `DictateUseCase`.

**Dependencies:** no other module and nothing from Android (pure JVM Kotlin), plus the one library below.
Feature modules depend on `core`, never the reverse.
- One library: `kotlinx-coroutines-core`, for the capture channel. It is a library,
  not a module or Android dependency, so the module stays pure-JVM and plain-JUnit
  testable.

**Threading (the capture channel and the interrupt re-arms).** The capture buffer
is a `kotlinx.coroutines` `Channel`, unbounded: the capture thread appends with
`trySend` and the draining thread takes the lot out with a `tryReceive` loop, so an
append never blocks the capture thread and never fails while the channel is open,
and the snapshot-and-clear is one shared loop that stops at the first empty read —
the cut. That is the only concurrent thing in the module. The three interrupt
re-arms are the narrow exception class named by the threading rule in the root
`AGENTS.md`, decided by a site rather than by preference. A `CancellationException`
cannot carry them: it is a `Throwable` a suspending fold would raise at a caller,
where these sites record the failure and return, and it does not set the calling
thread's interrupt flag, which is what the re-arm restores:
- `DictateUseCase`, the adapter fold — an `InterruptedException` from the settings
  store, the probe, the WAV encoder, an engine, a formatter, the id source or the
  clock is folded into `DictationResult.Failure(OTHER, <class>)`; the flag is
  re-armed because the interruption belongs to whatever runs next on the caller's
  thread, not to the dictation. Pinned by `DictateUseCaseAdapterFailureTest` (a
  checked exception leaves the flag clear; an `InterruptedException` restores it).
- `SendUseCase`, the history save — an `InterruptedException` from
  `HistoryStore.save` is folded into the "not saved to history" detail; the flag is
  re-armed for the caller's thread. Pinned by `SendUseCaseHistoryStoreFailureTest`
  (an interrupted save keeps the commit and restores the flag).
- `SendUseCase`, the commit — an `InterruptedException` from `TextCommitter.commit`
  is folded into the `FAILED` outcome; the flag is re-armed for the caller's
  thread. Pinned by `SendUseCaseCommitterFailureTest` (an interrupted commit is a
  failed commit and the flag is restored).

## Invariants
- All ports compile; the `DictateUseCase` state machine is unit-tested including its
  error paths (no model, unreachable server, cancel).
- No `android.*` imports anywhere in `core/`; the framework-free tests read both the
  sources and the compiled classes and fail if a platform or framework package appears.
- Local mode with no model → error, NOT a server attempt (`NoCloudFallthroughTest`).
- Nothing from an on-device dictation reaches a cloud formatter, whatever the
  formatting setting says; the route decides, so automatic routing that fell back to
  the phone counts as on-device.
- An adapter that throws never escapes `dictate()`, checked exceptions included; it is
  reported as a failure and the session is left in ERROR so the caller can carry on.
  An `Error` is not an adapter failure and still propagates.
- A move (`transitionTo`, `arm`, `cancel`, ...) never carries an error or a
  transcription into a state that cannot use it.
- A committed dictation, an empty one and a failed one are all saved to history.
- No core type prints dictated text. The values that hold it (`Transcription`,
  `CommitRequest`, `SttSegment`, `SttResult.Success`, `DictationSession`,
  `DictationResult.Success`, `SendResult`) print ids, states and lengths, and
  `TranscriptRedactionTest` fails on a data class that has not said whether it can hold text.
- Empty text is valid: it encrypts, syncs and decrypts like any other entry.

## Owns
Domain models, ports, use cases. No Android imports.

## Public Interface
Domain models, ports, use cases

## Depends On
- android (registered in modules.toml)

## Does Not Own
- Android platform code
- Adapters — core is pure Kotlin

## Test Locations
- Unit: `src/test/kotlin/dev/breaker/dictation/core/` — plain JUnit 4, run with
  `./gradlew :android:modules:core:test`
- Contract: `tests/contract/test_core_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_core_contract.py`
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
- No Android imports, ever — `FrameworkFreeTest` and `NoFrameworkBytecodeTest` enforce it.
- `AppSettings.formattingEnabled` is part of the settings model. A settings store
  that persists a fixed set of keys must persist it too, or it comes back as its
  default (on) after a restart.
- `DictateUseCase` has no default for `localFormatter`. The composition root passes the
  on-device formatter (a pass-through until a real one exists); it must not call out to a
  network.
