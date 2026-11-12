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
- `WordStream` — `isRunning`, `start(onUpdate)`, `stop()`; the callback gets a
  `WordUpdate` (the words heard since the last endpoint, `final` set when the
  recogniser closed that utterance). Start while running is a no-op: the first
  callback stays and no second stream starts. Stop is safe when stopped.
  Calls arrive one at a time and in order, possibly on different threads, and none arrives
  after `stop()` returns. Each call happens-before the next, so everything one call wrote is
  seen by the next; the adapter's single-slot dispatcher gives that ordering.
  Word times are capture-relative (see `HeardWord`). An adapter in another module
  implements it, and the app wires it.
- `AuthService` — `register`, `login`, `logout`, `currentSession`
- `CryptoService` - five methods. `unwrapDek(WrappedDek, KeyEncryptionKey)` opens the data
  key with the key-encryption key the caller derived, never a password.
  `openSignInPackage(SealedSignInPackage, SignInPrivateKey)` opens the key package the
  website sealed for one sign-in and returns `AccountKeys`. `encrypt` and `decrypt` take
  the additional authenticated data as a byte array, which binds a text to its record.
  `toEncryptedText` is unchanged. A refused operation throws `CryptoRefused` and returns
  no partial result.
- `AccountKeyService` - twelve methods that make and wrap the account keys; the crypto module
  implements it, and the caller hands in the key-encryption key, as for `CryptoService`.
  `generateDataKey()` and `generateBoxKeyPair()` make a new data key and a new box key pair.
  `wrapDek(dek, kek, salt)` seals the data key under the key-encryption key with empty
  associated data. `wrapBoxPrivateKey(boxPrivateKey, dek)` and `unwrapBoxPrivateKey(wrapped,
  dek)` seal and open the box private key under the data key. `wrapPreviousDek(previousDek,
  newDek)` and `unwrapPreviousDek(wrapped, newDek)` seal and open the previous data key under
  the new one. `verifyBoxPublicKey(boxPrivateKey, publicKey)` recomputes the public key and
  compares it in constant time; it returns when they match and throws `CryptoRefused` when
  they differ. `unsealAndReencrypt(sealed, boxPrivateKey, dek, aad)` opens a `SealedAgentResult`
  with the box private key, accepts only an R1 body with source server of at most 524288
  bytes, and encrypts the same plaintext bytes again under the data key with the caller's
  associated data; it returns a `CipherText` and throws `CryptoRefused` on any failure. Each
  wrapped box key and each wrapped previous key is bound to its purpose, so
  the wrong blob in the wrong slot is refused. A refused operation returns no partial result.
  `reencrypt(cipherText, oldDek, oldAad, newDek, newAad)` opens a `CipherText` under the old data
  key and encrypts the same plaintext bytes again under the new one with one fresh nonce; it
  checks no body format, and it throws `CryptoRefused` on any failure.
  `encryptBytes(plain, dek, aad)` and `decryptBytes(cipherText, dek, aad)` seal and open opaque
  bytes under the data key with the caller's associated data, with no String and no body format
  check. `encryptBytes` returns a `CipherText` with one fresh nonce and never writes the caller's
  array; `decryptBytes` returns the one opened array, which the caller owns and wipes, and asks no
  nonce. Each refuses a plaintext or ciphertext above 524288 bytes, and throws `CryptoRefused` on any
  failure.
- `KeyDerivation` - `deriveKeys(password: CharArray, salt, kdfParams)`: derives the
  key-encryption key and the auth verifier in one call and returns both as
  `DerivedKeys`; the password travels as a CharArray that the caller wipes (the
  implementation never keeps or changes it); the crypto module implements it and holds the parameter limits
  (core carries the values and judges nothing). `CryptoService` takes the key-encryption
  key from this call and never sees the password.
- `SignInStarter` - `begin(): SignInAttempt` makes the one-time secret text and key pair in memory; the crypto module implements it, as SignInStarterImpl.
- `HandoffReceiver` - `onHandoff(code, attempt): HandoffReceipt` gets the return code and the attempt exactly once; the receiver owns the wipe of the attempt from that call on, and the holder wipes it only when the receiver throws.
- HandoffReceipt - the enum with the two receipts, TAKEN and NOT_FINISHED.
- DefaultHandoffReceiver - the default receiver (the app wires its own): it wipes the attempt, returns NOT_FINISHED and touches nothing else.
- `CredentialVault` - `saveKeys(keys, keyVersion)`, `readKeys()`, `wipeAll()`: where the account keys are kept on the phone; keys only, the session is kept by the auth client's session vault. `saveKeys` copies what it is given and replaces any earlier keys; `readKeys` returns a new `StoredAccountKeys` on every call, or null when nothing is stored or it cannot be read; `wipeAll` is safe when empty. The app implements it, as CredentialFileVault, with a sealed file.
- `PullSink` - `applyRemoteRow(row)`, `applyRemoteDelete(id, deletedAt)`, `retainOnly(keep)`: where the sync module hands what it pulled to the module that owns the local store. The row is one pulled live row as a `Transcription` (its id is the client id), and the answer is true when the row was stored, false when the store refused it because the device holds a tombstone for that id. The delete is a delete record from the pull: the id is the client id, deletedAt is the time of the delete in epoch milliseconds, and the answer is true when the device held a row to remove; a tombstone is kept either way. `retainOnly` takes the set of ids to keep after a full list (the ids of every page of that list plus the ids still waiting in the push queue), removes every other local row without leaving a tombstone, and answers how many rows it removed. History implements it and the app wires it, so sync never gets the whole `HistoryStore` and history stays the only writer of the local store. Only the sync module's PullApplier calls it, and the app builds that only when sync is on (a debuggable build).
- `RecordKeys` - `currentKeyVersion()`, `seal(clientId, body)`, `open(clientId, keyVersion, encrypted)`, `reseal(clientId, keyVersion, encrypted)`, `convertSealed(clientId, sealedBox)`: the keys behind the sync records, seen from the sync module. `currentKeyVersion()` is 0 when the phone holds no keys, is read afresh on every call and never throws. `seal` takes the R1 body bytes and returns a `SealedRecord` at the current key version, or null when it cannot seal. `open` returns the R1 body bytes, or null when it cannot open (no keys, no key of that version, a bad frame, a tag that does not match). Neither throws for those and neither says why. `reseal` takes the record as the server holds it (key version 0 means the plain padded base64 of the R1 body, which the port strict-decodes and refuses when the lenient body decoder would refuse it) and returns it sealed at the current key version, or null for every failure (no keys, no key of that version, bad text, a tag that does not match, a plaintext over 524288 bytes); the plaintext never leaves the port. `convertSealed` takes an agent result that was sealed to the owner's box key (the box key stays in the port) and returns it sealed at the current key version, or null for every failure (no keys, a bad box, the wrong key, a body that is not a valid server R1 body, a plaintext over 524288 bytes); it never throws for those and never says why; the caller owns the box bytes and wipes them after the call. The port has five members and is complete for this phase. The key, nonce and associated data stay behind the port. Core has no implementation.
- `OlderHistory` - `available()`, `older(bound)`, `delete(id)`: older rows read from the server for the history screen's Load more. `older(bound)` takes an `OlderBound` (a stored time in milliseconds and, when there is one, a change number; `render()` gives the text the server takes as older_than) and answers an `OlderResult`: a page of rows (newest first, with the count of rows left out because they could not be read, the next bound and whether more remain) or one of six answers that carry no rows: none older, signed out, not allowed (an agent token), busy (a rate limit, with the wait when the transport knows it), unavailable, bad request. The rows are shown for the session and are never saved to the phone. `delete(id)` answers true when a delete of one shown row reached the push queue.
- `SyncService` — `pushPending()`, `enqueue(Transcription)`, `enqueueDelete(String)`
- `UpdateChecker` — `check(force)`, `install(release)`, `rollback()`
- `Clock` — `nowEpochMillis()`, and `IdSource` — `newId()`, so the domain never reads
  a clock or invents an identifier itself

**Models** (`model/`):
- `Transcription(id, text, source: LOCAL|SERVER, model, durationMs, createdAt)` and
  `TranscriptionSource`; `text` may be empty, since a quiet recording transcribes to nothing
- `DictationSession(state, lastError, lastTranscription)` with `DictationState`
  (IDLE|ARMED|RECORDING|TRANSCRIBING|SENDING|ERROR), and `DictationResult` (Success|Failure)
- `AppSettings(mode: AUTO|LOCAL|SERVER, modelSize, serverUrl, apiKeyRef,
  wakeGestureMode, tilePosition, language, preloadModel, formattingEnabled,
  themeMode: SYSTEM|LIGHT|DARK, microphoneAsked)`, `SttMode`, `ThemeMode`, `TilePosition`
  `microphoneAsked` is bookkeeping: whether the system microphone prompt was shown once. It is not a user setting.
- `WakeGestureMode` (ALWAYS|KEYBOARD_ONLY|OFF) — the user wake-gesture preference stored in settings
- `SttRequest`, `SttResult`, `SttSegment`, `AudioFormat` (16 kHz, one channel), and
  `SttError(LOCAL_MODEL_MISSING, SERVER_UNREACHABLE, TIMEOUT, OTHER)`. `SttResult.Failure`
  carries `retryAfterSeconds: Int? = null` — the Retry-After seconds the server asked the
  client to honor on a 429, null when not asked; it is never inside the detail sentence.
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
- `SignInPrivateKey` and `BoxPrivateKey` - each exactly 32 bytes (X25519), handled like
  `KeyEncryptionKey`: copied when built and when read, with `wipe()` and a `toString`
  that never prints the bytes. `SealedSignInPackage` is the key package the website
  sealed for one sign-in: any non-empty byte array, handled the same way, with no fixed
  length. `AccountKeys(dek, boxPrivateKey)` is the pair a package opens to, and its
  `wipe()` wipes both
- `SealedAgentResult` - a sealed box of an agent-token result: any non-empty byte array, with no
  size limit in core. A plain class: the bytes are copied in, `copyBytes()` returns a new copy on
  every call, `size` is readable without a copy so the opener can check a size cap first, and
  `toString` prints the size only.
- `SealedRecord(keyVersion, encrypted)` - one record sealed for the server: a key version of at least 1 and a text that is not blank (both refused with an IllegalArgumentException). A plain class, not a data class: `toString` prints the key version and the length of the text, never the text.
- `StoredAccountKeys(keys, keyVersion)` - the account keys as the key store holds them, with their version (zero or more, checked before anything is copied). A plain class: it builds its own copy of both keys, `wipe()` zeroes that copy and never the caller's arrays, and `toString` prints a fixed text with no key and no version.
- `BoxKeyPair(privateKey, publicKey)` - the account's box key pair: a `BoxPrivateKey` and a
  public key of exactly 32 bytes. A plain class: the public key is copied in and
  `copyPublicKey()` returns a new copy on every call; `wipe()` zeroes the private key only;
  `toString` prints a fixed text with no key byte.
- `WrappedBoxPrivateKey` and `WrappedPreviousDek` - the box private key sealed under the data
  key, and the previous data key sealed under the new one. Each is 60 raw bytes when well
  formed, but the type only refuses an empty array; the crypto module judges the size. They are
  separate types so one cannot be put in the other's slot. `toString` prints the size only.
- `CryptoRefused` - the one plain refusal of a cryptographic operation: an
  IllegalArgumentException with a fixed message, no reason and no byte. It lives in core
  so that callers that see only core can catch it
- `KdfRefusal` — why a derivation was refused (MEMORY_BELOW_FLOOR,
  MEMORY_ABOVE_CEILING, ITERATIONS_BELOW_FLOOR, ITERATIONS_ABOVE_CEILING,
  PARALLELISM_BELOW_FLOOR, PARALLELISM_ABOVE_CEILING, OUTPUT_LENGTH_NOT_32,
  BAD_SALT_LENGTH, UNSUPPORTED_VERSION); `KdfRefused` is the
  IllegalArgumentException that carries one
- `PhraseKind`, `PhraseEvent(Wake|Send(trimBeforeMs))`, `PhraseModel`, `PhraseSample`,
  `TrainedPhraseModel`
- `HeardWord(text, startMs)` and `WordUpdate(words, final)`: a heard word refuses
  blank text and a negative `startMs`. `startMs` is milliseconds from the start of
  the ACTIVE capture, counted from the decoded sample count on the 16 kHz grid and
  rounded down, never a wall clock; the source owns that origin and resets it when
  a capture starts, and a word has no null time. `WordUpdate` keeps a copy of the
  list it is built from and is built through its companion `invoke`; an empty list
  is a valid hypothesis. Both are data classes; their `toString` prints counts,
  `final` and times, never the text.
- `SignInAttempt` - the one-time sign-in pair, held in memory only: the secret text as bytes (copied out only), the challenge, the public key (copied out only) and the X25519 private key. `wipe()` zeroes what it holds, and `toString` prints no value. A Kotlin String cannot be wiped, so the secret never leaves this type as a String; nothing persists it (no Keystore, no file, no settings, no log). The exchange request (in the auth client) makes its String at the moment of the call and nowhere else.
- `SignInLinkResult` - the outcome of building the sign-in link: a link or a refusal. Both are plain classes, not data classes, and their `toString` prints no challenge and no key.
- `SignInLinkRefusal` - the reasons a server address is refused. Brackets are refused on purpose, so an IPv6 literal address is refused.
- `HandoffReturn` - the parsed return link: a code (a plain class whose `toString` prints no code) or Ignored. Only the exact handoff shape with a 43-character code is a code; anything else is ignored.
- `AuthSession`, `UserRole`, `TokenScope`
- `SyncReport(pushed, failed, parked = 0, retryAfterSeconds = null)`: the result of one push. `retryAfterSeconds` is the server's Retry-After (whole seconds, 1..3600) when the drain stopped on a 429, else null; nothing in core waits on it. `parked` counts the rows parked IN THIS DRAIN by a permanent refusal (ADR-038); the total parked is `parkedCount()` of the sync module. The defaults keep every two-argument call valid.
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
  as a `DictationResult.Failure` (`SttError.OTHER`, the detail is the one fixed sentence
  "The speech could not be converted.", never the exception's class name or message: a
  class name means nothing to the user and a message can carry the dictated text; an
  `Error` still propagates). `stopCapture` refuses a negative `trimBeforeMs` with an
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
- `SignInHolder` - keeps one sign-in attempt in memory at a time: `begin()` wipes and drops any older attempt, then starts a new one; `abandon()` wipes the attempt; `hasAttempt()` says whether one is held; `receive(link, receiver)` reads a return link and returns a `HandoffOutcome`. The holder never stores, logs or persists the attempt, and keeps no reference after a hand-over.
- `HandoffOutcome` - the result of a return link: Ignored (nothing is touched), NoAttempt (the receiver is not called) or Handed, with the receipt.
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
  clock is folded into `DictationResult.Failure(OTHER, <fixed sentence>)`; the flag is
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
- A committed dictation, an empty one and a failed one are all saved to history; the app never sends or saves an empty take; the core saves what it is given.
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
- Word stream tests: `model/WordsTest.kt` (the two types), `port/WordStreamFakeTest.kt`
  (the port contract on a synchronous fake), `port/PortContractTest.kt` (one additive
  test that the port is writable against the domain) and `model/TranscriptRedactionTest.kt`
  (both types registered in the scan). They run with the same command as above.
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
- `WordUpdate` is built through its companion `invoke`, and its generated `copy()` is
  private, so every `WordUpdate` holds its own copy of the list.
- `HeardWord` and `WordUpdate` print counts and times only: the hand-written `toString`
  keeps dictated words out of logs, and `TranscriptRedactionTest` registers both types.
