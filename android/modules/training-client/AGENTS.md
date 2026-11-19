# AGENTS.md — android/modules/training-client/

## Purpose

Record phrase samples, upload, download trained model + checksum verify. In-app voice phrase training (F16). Record the wake phrase
("Breaker Breaker") and end phrase ("And I'm Gone") in the user's environment;
the server trains a per-user model; the phone downloads it, checks it and (later) installs it. Built and unit-tested, not tried on a phone: the recording, the sample store, the upload and download logic against a typed port that a test fake answers, and the check of the downloaded model. Not built: the server that trains, and the install of the model as a keyword model.

**Status on main:** the module's logic without a screen is built and unit-tested, and not tried on a phone: the phrase recorder (`PhraseRecorder`), the sample store with its ledger of server-confirmed ids (`SampleStore`), the typed server port (`TrainingServer`) with a scriptable fake that lives in the tests and is not shipped, the client with the verified model download (`TrainingClient`), and the wrapper that lets core's `PhraseTraining` port reach the client (`PhraseTrainingAdapter`). Not built: any screen or user-facing word, the real network client, the shared API operations, the server, the install of the downloaded model as a keyword model, and a settings control. `PhraseTraining` is a port declared in `core` (`core/port/PhraseTraining.kt`), not a type of this module. Every behaviour sentence below says in the same sentence whether it is built; a Flow step that names a screen, the network, the server or the install is the target design and is not built. Decided, not built (ADR-051 E and F): the install of the verified model as its own keyword model, and the model deletion on the phone; the phone side is built first, before the shared contract and the server (ADR-051 I).

**Build phase:** Phase 13, together with the server's `training`. Needs first: `core` (on main) and auth (Phase 11), because every training call carries the user's token.

## Owns
Record phrase samples, upload, download trained model + checksum verify.

## Public Interface

Public types, package `dev.breaker.dictation.training.client` (all built and unit-tested, not tried on a phone):
- `PhraseRecorder(source: AudioSource)`: `start(kind)` answers a `RecorderStart` (STARTED, ALREADY_RECORDING, SOURCE_FAILED) and `finish()` answers a `RecordResult` (`Accepted` with the `PhraseSample`, or `Rejected` with a `RejectReason`: TOO_SHORT, TOO_LONG, SILENT, NOT_RECORDING). `RecorderBounds` holds the bounds (0.5 s minimum, 3.0 s maximum at 16 kHz, a peak below 0.02 is silent) and `SampleCounts` the counts per phrase (10 minimum, 20 maximum, 15 default).
- `SampleStore(root, ids, wav)` and its `StoreResult` (Ok, InvalidId, NotFound, IoFailed): put, list, read, deleteOne, deleteAll, the ledger of confirmed ids, the job id, and the server-delete-pending mark.
- `TrainingServer`: six blocking operations (uploadSample, startJob, jobStatus, downloadModel, deleteMySamples, deleteMyModel) that answer a `TrainingResult` (Ok, NotSignedIn, NoServer, Refused, TooLarge, Network, Unavailable) and never throw for an expected outcome; with `JobId`, `JobStatus`, `JobFailure`, `ModelReply` and `TrainingLimits` (128 KiB upload cap, 32 MiB model cap). uploadSample is idempotent per sample id.
- `TrainingClient(store, server)`: startTraining, pollJob, fetchModel and deleteAll, answering `StartResult`, `PollResult`, `FetchResult` (with `FetchRefusal`), `DeleteResult` and `TrainingFailure`. The caller drives it; the client creates no thread, timer or sleep.
- `PhraseTrainingAdapter(client)` implements core's `PhraseTraining`: recordSample and upload throw UnsupportedOperationException with a fixed text (recording is fed frame by frame and uploading starts only through startTraining), and downloadModel returns the checked model or null.
- Internal, not public: `StoreRules`, `StoreFiles`, `SampleLedger`, `ModelVerifier`. The scriptable fake server, audio source, id source and WAV encoder live in `src/test` and are not shipped.

**Flow:**
1. Guided recording: 10 to 20 samples per phrase (mic, ambient noise included). Built and unit-tested, not tried on a phone: the recorder takes one sample per start and finish from the audio source and refuses a take that is too short, too long or silent with a typed reason; not built: any screen that guides the user. Decided, not built (ADR-051 B): the count is fixed by the build (the 10 to 20 range and the default are constants here and no screen uses them yet); each sample is 16 kHz mono and stays in app-private storage until uploaded.
2. Upload samples to the training container (user token, user-scoped). Built and unit-tested against the typed server port and its test fake, not tried on a phone: upload starts only when the caller starts training, and each sample is deleted on the phone after the server confirmed it; not built: the real network client that carries the user token, the shared API operations and the server side. Decided, not built (ADR-051 B and C): the user's start action and the signed-in check in the app; in this module a typed not-offered answer for signed out or no server is built and unit-tested, not tried on a phone.
3. Server trains a small per-user KWS model (sherpa-onnx KWS toolkit). Not built: the training is the server's job and the server is not built; the client's start, poll and fetch logic is built and unit-tested against the test fake, not tried on a phone. Decided, not built (ADR-051 D): the server trains on the CPU by default.
4. Phone downloads the model + **verifies checksum/SHA-256** (T21): built and unit-tested, not tried on a phone (the client checks size, digest and sample count, returns nothing on any mismatch, and fetches the one combined model once, with kind WAKE). It then installs
   for the `phrases` module: the install is not built (it is a later step, outside this module's built code). Decided, not built (ADR-051 E): the verified model is installed as its own keyword model, outside the shipped registry, and the default keyword model of option C is used until one is installed and verified, and again if anything fails.

**Privacy:** samples are user-scoped and deletable by the user (T15). Built and unit-tested, not tried on a phone: samples sit in an app-private store whose root the app injects (the app manifest has allowBackup=false), files are asked to be owner-only (the JDK permission calls are not verified on a phone), nothing is logged, and nothing leaves the phone until the caller starts training. Not built: the user-scoping on the server and by the token, and any screen that offers the deletion. Decided, not built (ADR-051 F): one user action deletes the samples and the model on the server and the model on the phone; the client's delete-all (local data first, then the server calls, with a pending mark kept until the server confirms) is built and unit-tested, not tried on a phone, and the user action and the model on the phone are not built.

## Invariants
- A downloaded model that fails any check is never returned by the client, so it cannot be installed (T21): built and unit-tested, not tried on a phone. The install itself, and phrase detection using the installed model (F16), are not built in this module. Decided, not built (ADR-051 E): a model that fails its checksum, or is missing, leaves the default keyword model in use.
- A tampered model download is refused (T21): built and unit-tested, not tried on a phone. A downloaded model is checked for size, digest and counts before anything is returned, in this order, and nothing is returned or stored when one check fails: the declared size is from 1 byte to the cap of 32 MiB (33,554,432 bytes); the byte count equals the declared size; the declared digest is 64 hex characters (upper case is lower-cased, anything else is refused); the SHA-256 of the bytes equals the declared digest; the declared sample count is above zero.
- Samples deletable; deletion removes them server-side: the phone side is built and unit-tested, not tried on a phone; the server side is not built (the client only asks the server port, which a test fake answers). Decided, not built (ADR-051 F): the model is deleted too, on the server and on the phone.
- A sample is deleted on the phone only after the server confirmed it (built and unit-tested, not tried on a phone): an Ok answer is written to the ledger first and the file is deleted after; a failed upload or a failed ledger write keeps the file, and an id the ledger already holds is not sent again. The minimum of 10 samples per phrase counts local and confirmed samples together, so a retry after a partial upload still works. A sample over 131,072 bytes (128 KiB) is refused on the phone before any call.
- Delete-all and an unreadable ledger fail closed (built and unit-tested, not tried on a phone): if the pending-delete mark cannot be stored, the server is NOT called and `deleteAll` answers `LocalFailed(null)`; if the ledger cannot be read (a folder, a link or any read error in its place), `startTraining` answers `StoreFailed` before any server call or upload, and the phone reports a pending server delete for it. Known small window: delete-all clears the local samples before it stores the mark, so a crash between the two leaves server copies with no mark and no local record to retry from; it is accepted.
- Upload happens only on the explicit start, `TrainingClient.startTraining` (built and unit-tested, not tried on a phone): building the client, recording, polling and fetching upload nothing, and a start retries a pending server delete before it uploads.
- Delete-all order and the pending server delete (built and unit-tested, not tried on a phone): the local samples, the ledger and the job id are deleted first, then the server samples and the server model are both asked, even if the first call fails. A failed server call returns its typed failure and leaves a server-delete-pending mark in the ledger, which the next start or delete retries; success is reported only when the server answered, never while a server delete is unconfirmed.
- The one combined model is fetched once with kind WAKE (built and unit-tested, not tried on a phone): the server returns the same combined model for both phrases, so `fetchModel` makes one `downloadModel(job, WAKE)` call, the checked model carries kind WAKE, and the adapter gives that same model for either kind. The cap is 32 MiB, about 1.6 times the largest shipped keyword model.
- The current job id is kept in the ledger, so a poll survives a restart of the app (built and unit-tested, not tried on a phone).
- Store ids are letters, digits, `-` and `_` only, 1 to 64 characters, checked before any path is built, and every path is checked to stay inside the store root (built and unit-tested, not tried on a phone); a sample is written under a temporary name and renamed, so a reader never sees a half-written file.
- Decided, not built (ADR-051 G): with no server, or while signed out, training is not offered and option C keeps working.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Training itself (server/training)
- Phrase detection (phrases)

## Test Locations
- Unit (Kotlin): `android/modules/training-client/src/test/kotlin/`, holding the tests of the built code and the scriptable fakes (server, audio source, id source, WAV encoder). Run: `./gradlew :android:modules:training-client:test`
- Contract: `tests/contract/test_training_client_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_training_client_contract.py`
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

This is how the project's own team builds modules. It is recommended for AI
agents, not required: an outside contributor may write the code themselves
(`.github/CONTRIBUTING.md`).

## Known Gotchas
- Trained models are .onnx too — checksum-verify before install (T21): the verify is built and unit-tested, not tried on a phone; the install is not built.
- The login token reaches this module as `core.AuthService`, wired by `android/app` (ADR-001); it never imports `auth-client`. That wiring is not built: this module's server port has a typed NotSignedIn answer (built) and holds no token.
