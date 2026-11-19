# AGENTS.md — android/modules/stt-ondevice/

## Purpose

sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify. Offline speech-to-text on the phone using the upstream **sherpa-onnx**
library (Apache-2.0, upstream k2-fsa/sherpa-onnx). The module compiles against our own build of it:
a speech-recognition-only package at upstream commit 11afbd00, with text-to-speech and speaker
diarization switched off, published as a release file. The components of that package and their
licences are listed in `tools/sherpa-asr/NOTICE.md`, in the recipe folder that builds the package;
the same file is copied into the package. The app supplies that file at run time; nothing of the
library is copied into this repository.

**Build phase:** Phase 3 of `docs/04-build-order.md`. Needs first: `core` (on main) and `model-registry` (built; this module names models only through the registry's entries, ADR-016).

## Owns
sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify.

## Public Interface `core.SttEngine` (local path).

**Public classes (the registry list):** `OnDeviceSttEngine` (it implements core's `SttEngine`), `ModelLoader`, `ModelInstaller`, `LocalModelStore`, `ModelFetcher`, `HttpModelFetcher`, `SherpaRecognizerFactory` and `SherpaOnnxRecognizerFactory` are the classes the app uses; the word stream is `SherpaOnnxWordStreamFactory`, which creates a `FedWordStream` (ADR-029). The module declares more public top-level types than these: the others are not part of the interface and are to be made internal (planned, not built).

**In:** 16 kHz mono PCM float32.
**Out:** `SttResult(text, segments, language)` or a failure result: `SttError.LOCAL_MODEL_MISSING` for model problems, `SttError.OTHER` for audio, engine and store problems. The engine class is `OnDeviceSttEngine`; user sentences come from `ErrorMapping`. A decode that does not finish before its deadline is an `OTHER` failure too (see Engine notes).
The public classes for the native engine are `SherpaOnnxRecognizerFactory`, a `SherpaRecognizerFactory`, and `SherpaOnnxWordStreamFactory`, which creates a `FedWordStream`. `FedWordStream` is a public interface: a core `WordStream` that also takes audio through `feed(samples: FloatArray)`.
The app passes `SherpaOnnxRecognizerFactory()` (optionally with a thread count) as the `factory`
argument of `ModelLoader`; the default there is still `UnavailableRecognizerFactory`, which refuses.

**Engine notes:**
- Local mode runs on the upstream sherpa-onnx library; none of its code is kept here. Do NOT reintroduce
  the base's cloud/Groq fallback path (Security Review fix #1).
- whisper.cpp AAR is a **fallback only** if sherpa-onnx lacks a needed feature.
- A transcript the engine prints entirely in capital letters is
  normalised at the source before it leaves the module: it is lower
  cased, and the standalone I and the contractions I'm, I'll, I've and
  I'd get their capital back; a transcript that already has a lowercase
  letter is left untouched, as is text with no letters. The rule runs on
  the one-shot result and on every word-stream update.
- `OnDeviceSttEngine` implements `core.SttEngine` over the loader port. It checks the audio rate
  first (16 kHz only), asks the loader for the model, decodes, and releases the recognizer after the
  decode (after an abandoned decode, the worker releases it when the native call returns). Every
  problem comes back as a failure result and `transcribe` never throws, including a store failure
  while loading (reported as an OTHER failure).
- Model problems map to `LOCAL_MODEL_MISSING`; audio, engine and store problems map to `OTHER`. The
  engine never returns `SERVER_UNREACHABLE` or `TIMEOUT`: it has no server path.
- A decode that does not finish before its deadline returns a failure, category `OTHER`
  (`ErrorMapping.decodeTimedOut()`), not `TIMEOUT`. While an abandoned decode is still running,
  `transcribe`, `transcribeAsync` and `preload` return `ErrorMapping.decodeBusy()` (also `OTHER`) at
  once, and `preload` loads nothing; `diagnostics` is not refused. The constructor takes an injectable `DecodeDeadline`
  and a worker dispatcher for the native call after the loader and the slot dispatcher; both have
  defaults, so a call with one or two arguments compiles unchanged. The default limit is 30 seconds
  plus three times the clip length. It is a recommended value and is NOT measured on a device.
- `transcribe` blocks (it bridges onto the dispatcher with `runBlocking`). `transcribeAsync` runs
  the decode on the dispatcher directly and must never call `transcribe`: the inner `runBlocking`
  would wait for the slot its caller holds and hang.
- A `transcribe` called from a thread already inside a decode is refused at once. The marker is a
  `ThreadLocal` reset in a `finally`; the reason a coroutine primitive does not fit is written at
  the site. The worker that runs the native call carries the same marker, so a call made from inside
  a decode is still refused at once.
- If a decode throws a cancellation, the result of `transcribeAsync` ends cancelled (the cause is
  not swallowed); the blocking `transcribe` still returns a failure.
- `close()` marks the engine closed. It does not interrupt a running decode, does not cancel an
  abandoned decode and does not cancel queued async calls: each queued call returns the engine-closed
  failure when its turn comes. The engine holds no recognizer between calls; an abandoned decode's
  worker still holds its recognizer until the native call returns (see Known Gotchas).
- When a checksum failure left the file on disk (the delete failed), the user sentence says it could
  not be deleted instead of saying it was deleted.

**Native adapter side:**
- `SherpaOnnxRecognizerFactory` is a public class here. `create(model)` decides (built, unit-tested, not tried on a phone) from the registry entry of the model id, in this order: a supported entry (its kind and model type are in the registry's supported set) of kind STREAMING
  (`StreamingProfiles` takes the language from the entry; `small` and `tiny` are English) takes the streaming path; else a supported entry of kind OFFLINE that has files and
  limits (today only `parakeet`) takes the offline path, through `SherpaOfflineRecognizer`, with a blank language so that the engine wrap
  labels the transcript with the language of the request; anything else (the `base` and `medium` Whisper entries, whose model type `whisper` is not in the supported set, and ids the registry does not know)
  is refused with a fixed message and no opener is called (built, unit-tested, not tried on a phone; the offline native binding is compiled against the library by the project's CI build
  and has never run on a device, see the binding paragraph below). On either path `create`
  then asks
  the entry for its four file names (each must be a non-empty regular file in the model folder) and its model type, which travels to the binding with the files, and only then opens the engine, so a refusal leaves
  nothing to release. Every refusal and every failure to open is a `SherpaTranscriptionException`
  with a fixed message that carries no path, file name or model id; the engine's own error travels
  as the cause. A native library that fails to link (a `LinkageError`) is reported the same way, at
  open and at decode, so the loader and the engine see an engine failure and not an `Error`. Any
  other `Error` passes through the adapter (the engine has its own arms for out of memory and stack
  overflow). The thread count is a constructor argument, at least 1, default 2.
- `SherpaOnnxWordStreamFactory` is the public factory for the word stream: `create()` returns a `FedWordStream`. Its public constructor takes the thread count (at least 1, default 2, the same default as the recognizer factory); an internal constructor takes the native opener, so a test can use a fake one. `create(model: SherpaModel)` refuses a model with no streaming profile, and a model whose files are missing or empty, or whose unpacked-file stamp is missing or does not match the files, by throwing `SherpaTranscriptionException` with the same two fixed messages as the recognizer factory; neither message carries a path, a file name or a model id. The stamp check reads plain files: it never hashes and deletes nothing. `create()` opens nothing and loads no native code: the native engine is opened by the stream's `start()`, on the stream's own slot. The module adds no thread for it; the stream runs on the single slot over the IO pool, as the `OnDeviceWordStream` bullet below says.
- `TransducerFileLocator` is now used by the keyword path only (`SherpaOnnxKeywordStreamFactory`); no speech path calls it, because the speech file names come from the registry entry (built, unit-tested, not tried on a phone). It picks the files by which names are present, not from a fixed list: the
  encoder prefers the int8 file, the decoder and the joiner prefer the plain file, and each falls
  back to the other kind. A name belongs to a role when it starts with the role word followed by a
  dash or a dot and ends with `.onnx`; of several, the first by sort order is taken, and the token
  table must be named exactly `tokens.txt`. The files are returned only when each is a regular file
  of at least one byte.
- `SherpaOnnxRecognizer` (internal) is the `SherpaRecognizer` over a native streaming recognizer. It
  talks to the engine only through the small internal seam in `NativeStreaming.kt` (`NativeStream`,
  `NativeStreamingRecognizer`, `TransducerFiles`, `NativeStreamingOpener`), so it and its tests run
  without the library. One `decode` opens a stream, feeds the clip, then a block of 10,560 zero
  samples, marks the input finished, polls until the engine has nothing left to decode, reads the
  text and always releases the stream. Before any native call it refuses a recognizer that is
  already released, a sample rate other than 16 kHz and an empty clip. The number of polls is
  bounded (the frames in the clip and the silence, 100 per second, plus 16); past the bound the
  decode fails instead of looping. The transcript is one segment that spans the clip, or no segment
  when the text is blank. `release()` frees the native recognizer once, does nothing the second
  time and never throws.
- `SherpaOfflineRecognizer` (internal) is the `SherpaRecognizer` over a native offline recognizer, through the small seam in `NativeOffline.kt`
  (built and tested with a fake engine only). One `decode` hands the whole clip to the engine in one native call: no silence is added and nothing is
  polled. Before any native call it refuses a recognizer that is already released, a sample rate other than 16 kHz and an empty clip. The transcript
  is one segment that spans the clip, or no segment when the text is blank; its language is the one the engine reports, else the profile's, and a
  blank one means the engine wrap fills in the request's language. `release()` frees the native recognizer once and never throws.
- Exactly three files name the library's classes (`com.k2fsa.sherpa.onnx`): `SherpaOnnxBinding.kt`,
  `KeywordSpotterBinding.kt` and `OfflineRecognizerBinding.kt`, all internal. The keyword spotter and the offline recogniser are further native entry points, so each has its
  own binding file; the narrow-surface property is unchanged (no fourth file of this module and no file of any
  other module names the library; a contract test fails on one). The keyword engine is written, but nothing
  downloads the keyword model yet, so the app uses it only when a keyword model is installed, the shipped one or a user one (built, unit-tested, not tried on a phone for the user one). `SherpaOnnxBinding` sets 16 kHz, 80 mel bins,
  the CPU provider, greedy search and no endpoint detection, and passes the model type of the registry entry (empty for the streaming entries, so the library
  reads it from the model files. `KeywordSpotterBinding` sets 16 kHz, 80 mel bins, the CPU provider and an empty
  model type, passes the keywords file and no boost or threshold; its library field shapes are unverified until
  a build compiles them. `OfflineRecognizerBinding` sets 16 kHz, the CPU provider, greedy search and the model type of the registry entry (`nemo_transducer` for `parakeet`, a value held in the registry and named in no main code; built, unit-tested, not tried on a phone); the offline binding is compiled against the library by the project's CI build, every offline library name in it compiles there, and it has never run on a device. None of the three loads a native library itself; the library's own classes do that on first use.
- `RefusalMapping.kt` holds `mapRefusal` (a loader refusal to a failure result). It was moved out of
  `OnDeviceSttEngine.kt` with its text unchanged except for visibility (`internal`).
- `OnDeviceWordStream` (internal) implements `FedWordStream` (the core `WordStream` port plus `feed`) over the same native seam. It
  runs on its own single slot (`singleSlot(Dispatchers.IO)`, not the batch decode slot): native calls and
  updates run one at a time, in order, and may run on different IO threads. `start` opens the recognizer
  and one stream there, `feed` takes 16 kHz mono float samples (a copy, never blocking) and `stop` ends the
  run. Each update carries the whole hypothesis of words with capture-relative start times. It stays internal:
  `SherpaOnnxWordStreamFactory` is the public way to build it, and the app is to build its stream through that factory.
- `FedWordStream.feed` (implemented by `OnDeviceWordStream`) takes 16 kHz mono float samples. It copies them and never blocks: the copy is offered to the run's queue without waiting. It may be called from any thread. Audio fed before the first `start()` call, or after `stop()`, or after the run ended by itself or by a failure, is ignored: it is not queued and not counted. Audio fed once `start()` has claimed the stream is queued for the worker. A chunk fed at the same moment as `stop()` may be queued, and is then discarded without reaching the engine. An empty array is ignored. A full queue drops the chunk and counts it (`droppedChunks` and `droppedSamples` in `OnDeviceWordStream`, reset at each `start()`).

**Keyword side (built, used by the app only when the keyword model is installed; nothing downloads that model yet):**
- `KeywordModelLoader.load()` gives `KeywordLoad.Ready(KeywordModel)` or `KeywordLoad.Refused`. It wraps `ModelLoader`, so it runs the same checks (registry lookup in the keyword list, family, install, install stamp or archive check against the registry pin (sha256 and byte size), unpacked files) and opens no engine. After the first load it relies on the install stamp, so it does not hash the archive again; the keyword chooser asks it once per take, and only when no ready user keyword model is there (built, unit-tested, not tried on a phone).
- `SherpaOnnxKeywordStreamFactory.create(model)` checks before it writes or opens anything (keyword id and profile, the four files, the install stamp, the token table against the fixed phrases), asks a `KeywordsFileWriter` to put the keywords file on disk, and returns an `OnDeviceWordStream` over `KeywordSpotterBinding`. A refusal is a `SherpaTranscriptionException` with a fixed message.
- `KeywordSpotterBinding` is the only file that names the library's keyword classes. Each hit is kept as the latest result and the spotter stream is reset once, so a hit is delivered once whether the library keeps or clears the result after a reset. The binding is not run by any unit test.
- `FixedPhrases` holds the wake and send phrases as constant token lines with no boost and no threshold (the library's defaults); `KeywordsFileText` renders and validates the file. The writer is implemented by the app (private folder, atomic replace).
- Not built: a download of the keyword model (no code calls the installer for it), waking by voice while the app is idle, training or calibration, and any measurement on a device (latency, false alarms, the default tuning on a real voice).
- Hit times after a reset are UNVERIFIED: whether the library restarts them from zero after a reset of the stream is not known and has not been run on a device. If they restart, a second hit of one stream would carry a start time that is too small and the send offset would trim too much of the dictation. The stream is created per take, so the send phrase is normally the first hit of its stream with no reset before it; a device check owes the answer and no fix is built.
- User keyword model: `UserKeywordStore` keeps one keyword model for both fixed phrases in its own app-private folder, under the user-keywords folder of the files folder (built, unit-tested, not tried on a phone); it never writes the shipped store or the model registry.
- `UserKeywordStore` takes a verified `TrainedPhraseModel` of either kind and does not read the kind (built, unit-tested, not tried on a phone); its token check uses an optional phrase list that defaults to the fixed phrases (built, unit-tested, not tried on a phone).
- `UserKeywordStore` refuses an archive over the keyword stream limit of 32 MiB, or one whose SHA-256 is not the digest the caller verified, and writes nothing for those two refusals (built, unit-tested, not tried on a phone).
- The install refuses, after unpacking and before the swap, an archive whose tokens.txt does not hold every fixed phrase token, with the typed reason TOKENS_MISSING of `UserKeywordReason` (built, unit-tested, not tried on a phone); a token table over the factory's cap, or one that cannot be read, is refused the same way. The new folders are removed and the previous model is kept. An install whose digest is already installed is not a no-op while the stored token table lacks a phrase: the archive is extracted again and checked, so it is refused with TOKENS_MISSING when its table lacks the phrase too (built, unit-tested, not tried on a phone). `UserKeywordTokens` repeats the factory's token check (the same cap constant, token reader and renderer), so if the factory's check changes, the user check must change with it; a test pins that the factory and the user check reject the same token file that lacks a phrase (built, unit-tested, not tried on a phone).
- The install unpacks the archive into a new folder through the keyword extraction profile, with the same path, entry and size rules as the shipped keyword model, then stamps it with the archive digest (built, unit-tested, not tried on a phone).
- The install swaps the new folder in by rename: the previous model is moved aside first and deleted only after the new one is in place (built, unit-tested, not tried on a phone).
- A failed move puts the previous model back; when that also fails, the previous model stays aside, nothing is installed, and the next install or remove sweeps it (built, unit-tested, not tried on a phone).
- A kill between moving the previous model aside and moving the new one in leaves no installed model: the next load answers absent, the default is used, and the next install sweeps the leftovers first (built, unit-tested, not tried on a phone).
- `remove()` deletes the installed model and every leftover of an install, and it reports true only when none is left (built, unit-tested, not tried on a phone).
- `UserKeywordLoader` judges the installed folder from its install stamp, the four names, the sizes and the modified times; once the stamp matches, it reads the token table once and checks it against the phrase list, and nothing is hashed (built, unit-tested, not tried on a phone). The loader takes an optional phrase list that defaults to the fixed phrases (built, unit-tested, not tried on a phone). A token table that misses a token is ignored with the typed reason TOKENS_MISSING, so the chooser uses the default and the stream factory is never handed the model (built, unit-tested, not tried on a phone).
- A user model that fails any check is ignored for that take and reported with a typed `UserKeywordReason`; it is never half-used (built, unit-tested, not tried on a phone).
- A user model whose files were copied or restored with new modified times is ignored until it is installed again; the typed reason lets a later settings step say so (built, unit-tested, not tried on a phone).
- A ready user model is a `KeywordModel` with the keyword model id and `KeywordSource.USER`, so the stream factory needs no change (built, unit-tested, not tried on a phone).
- `KeywordChooser` answers the user model when it is ready, else the shipped keyword model from `KeywordModelLoader`, else a refusal, and it reports the reason a user model was ignored in `KeywordChoice` (built, unit-tested, not tried on a phone).
- `KeywordModel` has a fourth field, `source`, of type `KeywordSource`; its default is `KeywordSource.DEFAULT`, so the existing call sites compile unchanged (built, unit-tested, not tried on a phone).
- The archive the install expects is not built: one bzip2 tar holding the four keyword files of the keyword profile (the int8 encoder, the plain decoder, the int8 joiner and tokens.txt), the same shape as the shipped keyword archive, with tokens.txt holding every fixed phrase token.
- The server step that makes that one archive for both phrases is not built.
- Not built: the download trigger for the user model, the settings control that returns to the shipped model, the training itself, and any user-facing words for the user model.
- The user keyword types `UserKeywordStore`, `UserKeywordLoader`, `UserKeywordLoad`, `UserInstall`, `UserKeywordReason`, `KeywordChooser`, `KeywordChoice` and `KeywordSource` are public (built, unit-tested, not tried on a phone) and are not in the registry list above.

**Download side:**
- `HttpModelFetcher` is the real `ModelFetcher`. It is built on the platform's `HttpURLConnection`,
  so the fetcher needs no library of its own (the unpack side does, see Known Gotchas).
  `DownloadLimits` holds every bound as a plain parameter, so
  a test can tighten any of them. Both constructor arguments (`limits`, `cancelled`) have defaults.
- Where it may connect: https only, on every request. The first request goes to a host in the
  first-hop set (`api.github.com`); a redirect goes to a host in the redirect set
  (`release-assets.githubusercontent.com`) and nowhere else. An address with user-info or a port
  other than 443 is refused. Redirects (301, 302, 303, 307, 308) are followed by hand, at most 5. No
  credentials and no cookies are sent: the request carries only fixed `Accept`, `Accept-Encoding`
  and `User-Agent` headers.
- How much and how long: the body is capped at the registry size plus one MiB (the registry gives
  the size to the nearest whole MiB). A declared length above
  the cap fails before one byte is read, and a body that grows past the cap is cut off. Connect
  timeout 15 s, read timeout 30 s, and one hour for the whole fetch.
- The caller may stop a fetch with a polled cancel lambda: it is asked before each request and once
  per chunk of a body. A read already blocked in the socket ends at the read timeout.
- A network problem is a failure result with a short reason that never carries an address, path or
  query. The installer sends that reason to the debug sink only; the user sees one fixed sentence.
  A problem writing the staging file (a full disk included) is thrown, as an `IOException`,
  and the partial file is deleted first. A failed free-space check is raised before any byte is
  written, as a `NoSpaceException`, which is also an `IOException`.
- The fetcher never hashes. The installer's digest check is the judge of the bytes.

**Unpack side:**
- Five files make it up. `ModelExtractor` unpacks a verified archive and is the only file in the
  module that imports the archive library. `ExtractionProfiles` holds no table of models: it reads the registry entry (built, unit-tested, not tried on a phone); for
  each supported speech entry that has files and limits (today `small`, `tiny`, `parakeet`) it takes the four files the engine opens
  (encoder, decoder, joiner, tokens) and the bounds from the entry; the registry holds no path length, so one constant, 128, stays in the module, and the keyword model keeps its own profile in code. The profile of the `parakeet` entry writes its four int8 files only (the readme and the sample recordings are read past) within 640 MiB per entry, 704 MiB written and 720 MiB of decompressed stream, from the listing of the real archive (built and pinned by a test against that listing; the extractor has not been run on the real archive). `ModelExtractor.unpackedBytesAllowed(id)` returns the written-bytes bound, and the installer uses it in its free-space check before the download (built). `EntryRules` holds
  entry's header (name, type flag, declared size). `BoundedStream` counts the decompressed bytes and
  throws past its limit instead of ending the stream early. `ExtractionOutcome` is the result:
  `Extracted`, or `Rejected` with a reason that says whether the archive, the phone or the free
  space was at fault.
- How it reads and writes: the archive is a tar inside bzip2, read through a 64 KiB buffer. Only the
  profile's flat file names are written, under the profile's own names (never a name taken from the
  archive), into a work directory in the staging directory. Each file is forced to storage before it
  is closed. Then ONE rename makes `<id>/files/`, so a partly unpacked model is never visible. Every
  other entry is read past and not written. After the last entry the stream is read to its end, so
  a missing end marker or extra bytes are noticed.
- What it refuses: an absolute path, a parent segment, a link, a non-regular or sparse entry, too
  many entries, an entry or the total over its bound (also the decompressed stream as a whole), a
  damaged stream or header, a truncated archive, a second top directory, duplicate names, a missing
  or empty needed file, and too little free space. A refusal deletes the work directory, creates no
  target and never touches the archive.
- The installer unpacks after the second check and the metadata writes, then writes the stamp, and only then reports
  `Installed`. On any refusal it deletes the whole model directory; a failed delete is reported
  through the could-not-delete sentence variant (`leftOnDisk`). A model with no profile is refused
  before anything is downloaded. An install over an existing one removes the earlier unpacked files
  before the archive is replaced; if that removal fails, the install is refused and the downloaded
  file is discarded. `ModelInstaller` takes the extractor as its new last constructor parameter,
  with the real one as the default.
- New installer refusal values: `UNSUPPORTED_MODEL`, `EXTRACT_REFUSED`, `EXTRACT_FAILED`, `NO_SPACE`.
  Three new user sentences: the archive could not be unpacked safely, the phone could not unpack it,
  and there is not enough free space to unpack it.
- Known gotcha: a disk that fills up while the model is being downloaded, after the free-space check passed (for example another app takes the space), is still filed as `STAGING_FAILED`, because telling it apart from other write errors would mean reading exception text, which is fragile.
- `NO_SPACE` also covers a download that cannot start for lack of free space: before any request, the installer checks the free space of the staging volume against the registry byte size of the archive plus the profile's cap on what the unpack may write (for `parakeet`, 487,211,443 bytes plus the profile's cap of 704 MiB, about 1.14 GiB, which is more than the 1.08 GiB the measured unpack needs), and the fetcher's own check before it writes a byte can refuse too; in both cases the user sees `ModelMessages.DOWNLOAD_NO_SPACE` ("There is not enough free space on the phone to download the model.") and nothing is left installed, and a shortfall found by the installer's check makes no request at all (built, unit-tested, not tried on a phone). The installer takes an optional `usableSpace` reader so a test does not depend on the disk of the machine it runs on.
- The loader answers not-installed, and keeps the archive, when the archive verifies but `files/` does
  not hold all four profile files, each a regular file of at least one byte. That check comes after
  the archive check, so a tampered archive is still judged and deleted first. The loader hands the
  engine factory the `files/` directory.
- After the unpack, the installer writes a stamp, the file `.unpacked-stamp` in `files/`. It records the
  archive digest and, for each of the four files, its size, modified time and SHA-256. It is written only
  after the archive verified and the unpack succeeded, so a refused install never writes one. If the
  stamp cannot be measured or written, the install is refused and the model is deleted. A torn or
  malformed stamp reads as absent. The loader (through `LocalModelStore.readStamp`) and the word stream
  factory both read it through `UnpackedStamp.read`.

**Model lifecycle:**
- Models from `shared/model-registry` (sizes + SHA-256 + per-model license terms).
- Download once → app data dir → **verify against our own registry pin (SHA-256 and
  byte size) before load** (N3, T1, T21) [2]. Upstream's checksum list is neither fetched nor consulted, and the install makes one request, for the model (built, unit-tested, not tried on a phone).
- The check: an archive is trusted only when the SHA-256 of the downloaded
  archive equals the SHA-256 in its registry entry AND its byte length equals the size in that entry (a size of zero never matches).
  The size is checked before the digest; no upstream list and no archive name is consulted (built, unit-tested, not tried on a phone).
  The archive is checked when it is
  installed (in staging, and again after the move). A load verifies it too, except when a stamp for the pinned model is present (see the load rules below).
- A file that fails the check is deleted at once, never loaded, never kept, so
  the next attempt downloads it fresh. If the delete itself fails, the refusal
  stays: the result carries `leftOnDisk` and its fixed sentence says the file
  could not be removed.
- A `.checksums` file left in a model folder by an earlier version is never read: it neither blocks nor helps a load and it stays on disk unused; the loader's refusal for unreadable stored checksums
  and the code that stored and read the list are removed (built, unit-tested, not tried on a phone), so the recognizer factory
  is called for a model with no checksum file as for any other (built, unit-tested, not tried on a phone) and an installed model
  keeps loading after an update.
- Load with a stamp: when the stamp for the pinned model is present and the four files are there, the archive is
  not hashed. The sizes and modified times of the four unpacked files are compared with the stamp. A size that
  differs refuses the load at once, deletes that file and answers not installed, before the engine is opened. When
  every size and every modified time matches, the load proceeds with no hashing. When the sizes match but a modified
  time differs, the loader hashes the four files (not the archive) against the recorded digests: when all of them
  match, the load proceeds and the stored times are refreshed on a best-effort basis; when one differs, that file is
  deleted and the load answers not installed.
- Load without a usable stamp (missing, unreadable, malformed, or for another pin): the archive is verified once, as
  before, the unpacked-file check follows, and then the stamp is written from the files as they are. That write is
  best effort: a failure only writes a debug line and never fails the load. The stamp is never written before the
  archive has verified.
- An `OutOfMemoryError` while the engine is created is caught and refused with the existing engine sentence
  (`ModelMessages.ENGINE_COULD_NOT_START`). The model stays installed, because nothing about it was judged wrong.
  Other `Error`s still reach the caller.
- What the user sees: one fixed plain sentence per kind of problem
  (`ModelMessages`). Paths, exception text and digests go to a debug sink the
  caller supplies (`ModelDebugSink`; the default drops them). A load or install
  refusal never carries a path or an exception class name.
- `preload(modelId)` loads the model once and releases the recognizer before it returns, so a
  settings screen can report a bad model early. It holds nothing, and each `transcribe` checks the model and
  starts its own recognizer again, so preload does not shorten the next start. Load at startup (or
  lazy per setting) remains the app's wiring decision.

**Security Review's sherpa-onnx audit [2]:** safe to use with three mitigations — clean
of malware/backdoors/phone-home; the Kotlin bindings of the copy vendored in the base repo match upstream
signatures exactly. **Known bug:** issue #3983 — OOB write in the offline
transducer greedy-search decoder, reachable via a tampered .onnx (vocab_size
parsed with no upper bound). Mitigations adopted here: our own SHA-256, byte size and release-asset id pinned per model in the registry, verified before anything is unpacked (built, unit-tested, not tried on a phone); upstream's checksum list is advisory only, checked at authoring time and never fetched on install (built, unit-tested, not tried on a phone); the release-asset id is not immutable, because upstream can re-point it, and our pin catches that (built: a re-pointed asset fails the size or digest check, hand-traced only, not run) (closes the tampered-model delivery
vector); track #3983 and adopt the upstream decoder fix when it lands; revisit
(beam-search / non-transducer models) only if the fix doesn't land in a
reasonable window [2]. Whether our package (upstream commit 11afbd00) contains that fix has not been
checked. This audit covered the copy vendored in the base repo, which this module no longer uses (it
compiles against our speech-recognition-only package, see Known Gotchas); no audit of that package is
recorded here.

**Threading:** inference blocks, so the engine runs one decode at a time on a single-slot coroutine
dispatcher, never the UI thread. The dispatcher is a single-slot view over the shared IO pool (built
by `singleSlot`). One slot is not one fixed thread: successive decodes may run on different IO
threads, so the reentrancy marker is reset after every decode.

The native call itself runs on an IO worker (the worker dispatcher, `Dispatchers.IO` by default)
while the slot thread parks on a first-wins `CompletableFuture`. The worker's result or the deadline
completes the future, whichever comes first. The deadline ends the wait, not the native call: when
it wins, the slot thread returns the timeout failure and the slot is free again, while the abandoned
worker stays inside the native call and releases its recognizer when that call returns. This is an
argued exception to the rule to use coroutines for every concurrent thing (root
`AGENTS.md` section 6). A suspension would hand the single slot to the next queued decode, so two
native decodes could overlap. A nested coroutine bridge on a caller's own loop could run the next
queued body inside the wait. A native call cannot be cancelled, so the only lever is to stop waiting
for it. A JDK future parks the thread and pumps nothing. The reason is also written at the park and
at the worker dispatch in `DecodeBound.kt`.

An interrupt of the thread that waits ends the wait like a deadline: the call returns the timeout
failure, the decode counts as abandoned, and the thread's interrupt flag is put back. A test covers
it.

- Thread affinity: the engine creates a recognizer on the slot thread, decodes with it on a bound
  worker thread and releases it on the slot thread, or on the worker when the decode was abandoned:
  one recognizer, two threads, one at a time. It keeps none between calls, so it does not matter
  which IO thread runs the next decode. What was read, at upstream release 1.13.8: the Kotlin files
  `OnlineRecognizer.kt` and `OnlineStream.kt` and the JNI files `online-recognizer.cc`,
  `online-stream.cc` and `common.h`. The Kotlin classes hold the native pointer in one plain field
  and use no thread-local value, lock, handler or thread identity. The JNI files keep no
  thread-local value, no lock, no stored environment pointer and no class, method or field id
  between calls; the only `static` in them is one helper function (a text search for the usual
  names, run with a control that shows the search finds JNI text). Limits: the C++ sources behind
  those JNI calls (recognizer, stream, feature and session code) and the ONNX Runtime inside the
  release file were NOT read here, so whether a recognizer created on one thread may be used from
  another is not confirmed; upstream gives no written guarantee; and the claim has not been tried
  on a device. If it ever needs one thread, the engine must switch to a single fixed thread for
  create, decode and release.

## Invariants
- Offline dictation E2E: tap → speak → send → text (F1).
- Tamper test: corrupt model file → load refused and the file deleted, so a retry
  downloads it fresh (T1, T21).
- Stamp: a load that finds the stamp for the pinned model refuses an unpacked file whose size changed, or whose
  content no longer matches the recorded digest, before the engine is opened, and deletes that file. It does not
  hash the archive. Tested by: `ModelLoaderStampTest` (the loader refusals and the no-hash path) and `UnpackedStampCheckTest` (the check rules).
- The word stream factory refuses a model whose stamp is missing or does not match the files, before it opens
  anything. Tested by: `SherpaOnnxWordStreamFactoryStampTest`.
- Start latency target: under 1 s with preload (N1). Not met by design today (each start builds its own recognizer; preload keeps none); not measured on a device. See Known Gotchas.
- **No model installed + local mode → clear "No model installed" error** (fix #1).
- One slot runs the recognizer, the stream and every update, the final one included, in order. Each start delivers at most one final (`finalDone`), only at stop, with the whole hypothesis even when unchanged; none on the deadline path, after a stop from inside an update, after an abandoned open, after a native failure, or after the update callback throws. Tested by: `OnDeviceWordStreamTest`, `OnDeviceWordStreamStopTest`, `OnDeviceWordStreamDeadlineTest`, `OnDeviceWordStreamRaceTest`, `OnDeviceWordStreamBoundsTest`, `OnDeviceWordStreamReleaseTest`, `OnDeviceWordStreamNoFinalTest`
- Stop from another thread, never from the stream's own slot, waits, with no bound, for the update already running and for the worker to reach the tail flush; then the tail flush (tail padding zeros, input end, bounded drain) is bounded by the flush deadline. A stop while the native open is still running returns at once and abandons the run: the worker releases the recogniser when the open returns and delivers nothing. Chunks still queued at stop are discarded and never reach the recognizer; no update is delivered after stop returns. NOT HANDLED yet: if the thread that calls stop is interrupted while it waits, stop returns early and the running update or tail flush may still finish after it returned. Tested by: `OnDeviceWordStreamStopTest`, `OnDeviceWordStreamDeadlineTest`
- Stop from inside an update returns at once: no tail flush, no final, and that update is the last one this run delivers; a stop from inside an update acts on the run that made it. Tested by: `OnDeviceWordStreamStopTest`, `OnDeviceWordStreamRaceTest`
- The tail flush has a deadline (`flushDeadline`, default 2 seconds because stop() may block the main thread (Android's not-responding limit is 5 s for input), NOT MEASURED on a device (the device check measures the real tail decode time)). Past it no final is delivered, `flushTimeouts` counts it and stop returns; the deadline does not bound the wait for a running update, and a native open is not waited for at all (see the abandoned-open rule above). Tested by: `OnDeviceWordStreamStopTest`, `OnDeviceWordStreamDeadlineTest`
- Feed never blocks: a chunk that does not fit the queue is dropped and counted, and the call returns at once; feed before start and after stop is ignored and not counted. Tested by: `OnDeviceWordStreamTest`, `OnDeviceWordStreamStopTest`, `FedWordStreamFeedTest`
- A word starts at the piece that opens it, at that piece's time in 16 kHz samples divided by 16 with the fraction dropped (0.12 s gives 120 ms); the origin is the first sample fed after start, or the first sample accepted when the queue overflowed (see the overflow gotcha). Tested by: `WordMergeTest`, `OnDeviceWordStreamTest`
- The word stream never opens or holds the microphone; it only hears the audio
  the capture gives it. When the microphone yields to another app it gets no
  more audio, and the app stops it as at any capture end (at most one final
  update, then none) (F37, ADR-022) (built; tested by OnDeviceWordStreamStopTest and the app's taken tests).

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_model_registry (registered in modules.toml)

## Does Not Own
- Server transcription (stt-server)
- Model catalog (shared/model-registry)

## Test Locations
- Unit (Kotlin): `android/modules/stt-ondevice/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:stt-ondevice:test`
- Registry pins: `RegistryTamperTest` loops `ModelRegistry.ALL` (five speech entries) and checks, per entry, that `ModelIntegrity.verify` refuses an archive that does not hash to its pin and an archive whose length is not the pinned byte size, and accepts a stand-in that matches its own pin and size; it works on a stand-in file, so no `tamper_verified` flag flips from it (built, unit-tested, not tried on a phone). The opt-in `RealArchiveTamperTest` (skipped unless `BREAKER_REAL_MODELS_DIR` names a folder that holds the real archives) loops all five speech entries, so it needs all five archives; it was run on 2026-11-13 against the four archives then listed (under the earlier pin rule, not re-run under the size and digest rule), accepted each real archive and refused a copy of it with one flipped byte, which is why `tamper_verified` is true on `small`, `tiny`, `base` and `medium` in `shared/modules/model-registry/models.yaml`; it has not been run with the `parakeet` archive, so that entry is false.
- The fetcher tests use a scripted opener and no network. One class, `JdkHttpOpenerLoopbackTest`,
  tests the real opener against a plain server on the loopback address only. Exactly one of its
  tests (the read timeout) waits on a real timer, by design: the timer is the behaviour under test.
- `DownloadPolicyContractTest` reads the real model registry against the default `DownloadLimits`:
  every registry address must be https on a first-hop host, and every entry must fit under the
  default size cap.
- `DownloadLimitsSchemeTest` covers `isHttpsScheme` and the scheme guard in
  `DownloadLimits.isAllowed`: the long-s look-alike `http\u017F` is refused, ASCII `https` in any
  letter case is accepted, and `http`, `httpss` and `ftp` are refused.
- The unpack tests are `EntryRulesTest`, `BoundedStreamTest`, `ExtractionProfilesTest` (pins each
  profile against the listing of the real archive), `ModelExtractorTest`, `ExtractionProfileParakeetTest` (pins the parakeet profile against its real listing and runs the extractor on a parakeet-shaped archive),
  `ModelExtractorRefusalTest`, `ModelExtractorBoundsTest`, `ModelExtractorCrashTest`,
  `ModelExtractorByIdTest`, `ModelExtractorSeamTest`, `ModelExtractorNamesTest`,
  `LocalModelStoreUnpackTest`, `ModelInstallerUnpackTest`, `ModelInstallerUnpackReinstallTest`,
  `ModelLoaderUnpackTest` and `ModelMessagesUnpackTextTest`. They use no network and no real
  archive.
- `TarFixtures` writes the test archives in memory (hand-written tar headers inside bzip2, damaged
  ones included); it is the only test file that imports the archive library.
  `ModelInstallerUnpackFixture`, in `ModelInstallerUnpackReinstallTest.kt`, is the shared fixture of
  the two installer unpack test classes.
- The adapter tests are `SherpaOnnxRecognizerTest` and `SherpaOnnxRecognizerFailureTest` (the adapter
  over `FakeNative.kt`, a scripted native engine), `SherpaOnnxRecognizerFactoryTest`,
  `TransducerFileLocatorTest`, `StreamingProfilesConsistencyTest` (the streaming ids equal the unpack
  ids, and the locator picks the files the unpack writes), `RegistryDrivenSpeechModelTest` (a made-up offline entry that no code names installs, unpacks, loads and opens, and entries that are unsupported or incomplete are refused; built, unit-tested, not tried on a phone), `SherpaOnnxRecognizerWiringTest` (a real
  loader and engine over the real recognizer factory, with a fake opener) and `SherpaOnnxRealBindingTest` (the
  public recognizer factory on a plain JVM: a failure result, not an exception). The preload change has
  `OnDeviceSttEnginePreloadBusyTest`, `SlotOrderRecordTest` and the other `OnDeviceSttEngine*Test` classes (no native engine), and the four-names check has `LocalModelStoreFourNamesTest`
  and `ModelLoaderFourNamesTest`. None of them runs the native engine or a real model.
- `SherpaOnnxWordStreamFactoryTest` tests the word-stream factory with a fake opener and no native library. It feeds the stream it gets from the factory through the `FedWordStream` type that `create()` returns. It proves that a model with no streaming profile, and a model directory with missing or empty files, each fail with `SherpaTranscriptionException` and the fixed text; that the fixed text carries no path, file name or model id; that `create()` makes no call to the opener; that a thread count below 1 is refused; that a started stream opens the engine exactly once, with the four files the registry entry names and the given thread count, and delivers its final words with start times in milliseconds; and that two `create()` calls give two separate streams, where starting one leaves the other stopped.
- The stamp tests are `UnpackedStampTest`, `UnpackedStampCheckTest`, `LocalModelStoreStampTest`, `ModelInstallerStampTest`, `ModelLoaderStampTest`, `ModelLoaderStampMissingTest`, `ModelLoaderOutOfMemoryTest` and `SherpaOnnxWordStreamFactoryStampTest`. They use the existing fakes, no network and no native library.
- `FedWordStreamFeedTest` (a new test file of this change) tests `feed` through the `FedWordStream` type that the factory returns. It proves: a chunk fed before start is dropped, and the engine takes only the chunk fed after start; a chunk fed after stop has returned is ignored and not counted; a chunk fed to a full queue is dropped and counted with its samples; feed returns at once on a full queue and counts each of five chunks; an empty array is ignored and is not counted even when the queue is full.
- `OnDeviceWordStreamNoFinalTest` pins each of the five no-final paths by name (deadline, stop from inside an update, abandoned open, native failure, a callback that throws) and the rule of at most one final per start, only at stop. No production line changed with it.
- The archive-name tests are `ModelIntegrityArchiveNameTest`, `ModelLoaderArchiveNameTest`, `ModelInstallerArchiveNameTest` and `ModelRegistryArchiveNamesTest`; the first three pin that neither the archive name nor a stored list changes the verdict (the old per-name rule no longer exists), the last that every registry entry has a name.
- The pin tests are `ModelInstallerPinTest` (the install makes one request, for the model, and trusts only the entry's sha256 and byte size) and `ModelLoaderPinTest` (a load ignores a `.checksums` file that is good, garbage or absent). The offline tests are `SherpaOfflineRecognizerTest`, `SherpaOnnxRecognizerFactoryKindTest` (the choice by model id), `StreamingProfilesParakeetTest` and `TransducerFileLocatorParakeetTest`; they use fakes, no native library and no real model.
- The download no-space tests are `HttpModelFetcherNoSpaceTest`, `ModelInstallerNoSpaceTest`, `ModelInstallerPreDownloadSpaceTest` (the installer's own check before any request) and `ModelNoSpaceSymbolsTest`. They use a scripted opener, no network and no real archive.
- Contract: `tests/contract/test_stt_ondevice_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_stt_ondevice_contract.py`
- Contract (decode bound pins): `tests/contract/stt_ondevice_bound_pins.py`, collected and run by the contract test above.
  It includes the pin that `preload` holds the busy guard.
- Contract (unpack pins): `tests/contract/stt_ondevice_extract_pins.py`, imported and run by the contract test above.
  It also pins the catalog version of the archive library to the release the unpack was written
  against, so a version change is a deliberate edit of that pin.
- Contract (release file pins): `tests/contract/stt_ondevice_aar_pins.py` holds the tests and
  `tests/contract/stt_ondevice_aar_checks.py` the rules they call; the contract test above imports
  and runs them. They pin the pin file, the settings and build wiring, the compile-only
  declaration, and that only the three binding files name the library and no main source loads a native library; the scope rule and the keyword and offline binding pins are in `tests/contract/stt_ondevice_aar_binding.py` and `tests/contract/stt_ondevice_aar_binding_pins.py`, which the same contract test runs. Both the streaming and the offline binding pin the model type as read from the entry (`modelType = files.modelType`), and the pin still fires when the type is hardwired to an empty string, to `zipformer` or to `nemo_transducer` (python, hand-traced for the mutants; the three binding pin tests are not run against compiled Kotlin). The rule that no speech model id appears in the app's main Kotlin (ADR-050 J, models are data) is gated by `tests/unit/contract/test_no_speech_model_id_in_main.py`, which lists its allowlist (the generated registry file and one literal in core's `AppSettings`) and fires on a planted id (python, run; the Kotlin it scans compiles in the project's CI build).
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

## Built and not built (ADR-050)

Decided in ADR-050. Both of its build changes are in the code: this module and the registry entry are built, unit-tested, not tried on a phone, and the first-run, Settings and About screens for the second model are built, unit-tested, not tried on a phone.

- A second model, Parakeet TDT 0.6B v3 (registry id `parakeet`, the sherpa-onnx int8 offline archive of 487,211,443 bytes): its registry entry, its unpack profile and the offline recognizer behind the existing recognizer interface are built, unit-tested, not tried on a phone. The offline native binding is compiled against the library by the project's CI build, every offline library name in it compiles there, and nothing has been decoded with the real model (not verified).
- A kind switch chosen by the registry entry: built in `SherpaOnnxRecognizerFactory` as a choice by the entry's kind and model type, with no model id in main code (ADR-050 J, models are data), so a model that uses a supported kind and model type is a registry entry and its pin with no app code change; an entry outside the supported set is refused (built, unit-tested, not tried on a phone).
- The extraction profile for Parakeet, with limits taken from its real archive listing, is now read from its registry entry rather than a table in code: built, unit-tested, not tried on a phone, and pinned by a test against that listing; the `small` limits do not fit that archive, and the extractor has not been run on the real archive.
- Download limits follow the registry size, as they did before (built, unchanged: the body cap is the registry size in MiB plus one MiB, 466 MiB for `parakeet`).
- Our own pin (SHA-256, byte size and release-asset id) per model is the verify rule: built, unit-tested, not tried on a phone, in the installer and the loader; the upstream checksum list is never fetched and the refusal for a pin missing from upstream's list no longer exists (built, unit-tested, not tried on a phone).
- The take shape does not change: one decode after stop, through `OnDeviceSttEngine` (built, unchanged).
- Built, unit-tested, not tried on a phone: the first-run second button, the settings section, the ratings display, the About notice entry and the NOTICE text, which belong to the `android/ui` and settings modules (the second build change); a model is downloaded from the first-run and settings screens (built, unit-tested, not tried on a phone). Not measured: the memory the Parakeet model needs on a real phone (the check is pending).

## Known Gotchas
- Every .onnx is untrusted - verify the archive against our own pin (SHA-256 and byte size) before load [2]; the upstream checksum.txt is not consulted (built, unit-tested, not tried on a phone; ADR-050); track #3983 [2].
- The Gradle edge to `:shared:modules:model-registry` is required and present:
  the registry publishes an artifact (its build file applies `java-library`),
  this module's build file declares the edge, and its `modules.toml` entry
  lists `shared_model_registry`. The registry's types `ModelEntry` and
  `ModelFamily` appear in the public signatures of `ModelFetcher`,
  `ModelInstaller` and `ModelLoader` here, so a module that calls them needs
  the same edge.
- The check does not use the archive name. `ModelEntry` still carries `archiveName`, generated from `archive_name` in
  the registry, but `ModelIntegrity.verify` takes only the file, the pinned digest and the pinned size.
  The archive name is not an input of the check; a test in this module
  asserts that the same bytes verify under any file name (built, unit-tested, not tried on a phone).
  A test in this module also asserts that every entry in the registry has a non-blank archiveName.
- A crash between moving the archive into place and writing the verified
  marker can leave an archive without the marker. The loader does not read the marker, so this does not stop a load (built, unit-tested, not tried on a phone). The
  window is the span in `ModelInstaller` from the move to that write,
  and it includes the second check of the archive. A crash in it leaves the archive and no unpacked files, and the loader then keeps the
  archive and answers not installed on every load (the unpacked-files check comes after the archive check);
  installing the model again heals it (built, unit-tested, not tried on a phone).
  The window now also runs from the unpack to the stamp write: a crash there leaves the
  archive, the unpacked files and no stamp, and the loader then verifies the archive once and writes the stamp.
  A torn stamp reads as absent, because its final `end` line is missing.
- The start target (N1) is not met by the engine as it stands: every call creates a recognizer, and preload keeps neither. Model sizes in the registry range from 122 MB
  to 1818 MB. This has not been measured on a device.
- Engine behaviour not changed yet: the decode catch policy names two `Error` types and not others; a recognizer release
  that throws replaces the result; the engine and the loader keep two sets of user sentences that
  have drifted apart; a model the loader could not start is shown as "could not transcribe"; `loadedModelId` in the
  diagnostics is not cleared after a refusal; diagnostics on a closed engine reads on the caller's
  thread; one salvaged test duplicates another.
- The download side (`HttpModelFetcher`), the unpack side (`ModelExtractor`) and the native adapter
  (`SherpaOnnxRecognizerFactory`) exist. The default factory of `ModelLoader` is still
  `UnavailableRecognizerFactory`, which refuses to create a recognizer. The app constructs
  `ModelInstaller` in `wiring/ModelSupport.kt` and `ModelLoader` in `BreakerCompositionRoot.kt`. The engine takes the loader through `ModelLoaderPort`, and the
  app's wiring supplies it later, so the module cannot transcribe real audio until the app builds the
  loader with a `SherpaOnnxRecognizerFactory` and adds the release file (next bullet). The adapter, the
  release-file build route and their tests compile, and the tests run.
- The engine library is our own package of sherpa-onnx: the speech-recognition-only build at upstream
  commit 11afbd00, with text-to-speech and speaker diarization off, published as the release file
  `sherpa-onnx-v1.13.8-asr-only.aar` (an Android archive holding the classes and the native
  libraries). This module uses it to compile and nothing else: the build file declares it
  `compileOnly`, so it is not packaged, and the module's tests do not rely on it being on their
  classpath. The app must add the same release file at run time; that brings the classes and the
  native libraries, and the app chooses the ABIs (this package holds arm64-v8a and x86_64; the
  upstream k2-fsa file holds arm64-v8a, armeabi-v7a, x86 and x86_64; the app's base filter is
  arm64-v8a only). The upstream k2-fsa release file may be used only as a test control: it is not
  the pinned file and is never part of the shipped build.
- The build downloads the release file (about 23 MB) on a first build or an IDE sync, from the
  address in `sherpa-onnx-aar.properties`, through one exclusive repository in
  `settings.gradle.kts`. The task `verifySherpaAar` checks its SHA-256 against the pin and fails
  closed: a missing key, a digest that is not 64 lowercase hex digits, a version that differs from
  `gradle/libs.versions.toml`, anything but exactly one regular file named for the version, or a
  digest mismatch stops the build. It runs before this module's own build tasks only, and a
  command line can skip it with `-x`. When the app adds the release file it must add its own
  `dependsOn` on that task; that wiring is not in this module. The pin file is the only
  place the build of the library changes (its version must still equal the catalog
  version); which build the project ships is decided outside this module. At run time the module's
  network use is unchanged: it still reaches the network only to download a pinned model.
- What the build has shown: the adapter and the build route compile, the release file resolves and
  its SHA-256 is checked by the build, and the module tests passed (684 of 684) on a single core and
  in parallel when that was last measured; the count with the word-stream factory tests has not been measured here. Not verified: the minimum SDK of the release file. None of this says the library
  loads or runs on a device (see the items below).
- `numThreads` defaults to 2 (`SherpaOnnxRecognizerFactory.DEFAULT_NUM_THREADS`) and the silence fed
  after the clip is 10,560 samples, 0.66 s (`SherpaOnnxRecognizer.TAIL_PADDING_SAMPLES`). Both are
  device placeholders and are NOT measured: whether that silence finishes the last word, or is too much,
  and which thread count suits a phone, are unknown. The 80 mel bins in the binding equal the
  library's own default and have not been checked against either model. The refusals of a rate
  other than 16 kHz and of an empty clip are this adapter's own rule, not the library's.
- Owed on a device before anyone says on-device transcription works: a first load and a decode of a
  known clip for BOTH models, `tiny` and `small` (the model metadata of neither was read here, so
  that each really is a streaming transducer is judged from its file names only); the memory one
  recognizer needs and the start latency, neither of which is measured; the size the native
  libraries add to the app (the file list of this package was not read here; the upstream k2-fsa
  file, a different build, has for arm64-v8a `libonnxruntime.so` at 22,249,560 bytes and
  `libsherpa-onnx-jni.so` at 4,771,760 bytes); the casing, punctuation and
  error rate of the text (English only); a check that the native libraries are aligned for 16 KB
  pages (not checked); and, if the app ever turns on code shrinking (no such setting was found in the
  app build file), keep rules for `com.k2fsa.sherpa.onnx`, because the JNI code looks fields up by
  name and the upstream k2-fsa file ships an empty `proguard.txt` (the one in this package was
  not read).
- The library is reported to end the whole process, not to throw, when a model file is missing, a
  line of the token table does not parse, or the model metadata is missing. That comes from the
  design notes: the C++ sources were not read here. The locator and the four-names check keep a
  missing or empty file, or a directory in its place, from reaching the library, but they cannot
  catch a damaged token table, and nothing in-process can recover from that exit.
- This package is built with text-to-speech and speaker diarization off. The upstream k2-fsa release
  file also contains text-to-speech classes (the `OfflineTts` family); this module uses none of
  them, and whether this package still holds any such class was not read here. The components of
  this package and their licences are listed in `tools/sherpa-asr/NOTICE.md`, which the recipe also
  copies into the package; this module does not change the repository NOTICE. As that file states
  them: sherpa-onnx Apache-2.0; onnxruntime (prebuilt, csukuangfj/onnxruntime-libs v1.28.2) MIT plus
  its ThirdPartyNotices; kaldi-native-fbank Apache-2.0; kissfft BSD-3-Clause; kaldi-decoder
  Apache-2.0; kaldifst v1.8.0 Apache-2.0; openfst Apache-2.0; simple-sentencepiece Apache-2.0;
  nlohmann/json MIT. The list was read from that file; the package itself was not opened here. This
  card makes no licence statement beyond the one in Purpose and this list.
- The extractor writes at most the profile's written-bytes bound and needs free space equal to that
  bound before it starts (80 MiB for `tiny`, 160 MiB for `small`). It compares the bound with the
  free space of the staging directory; that is a bound, not a measure of what the files need. A
  refusal for space deletes the model, so the next try downloads the archive again.
- A crash between the second check and the rename leaves the verified archive and no `files/`. The
  store still counts the model as installed (the archive is there), but the loader answers not
  installed and keeps the archive; installing again heals it. A work directory left in the staging
  directory by the crash stays until the next install of that model, which removes it first.
- `isExtracted` is true only when the model has an unpack profile, `files/` is a directory, and each
  of the profile's four names is a regular file of at least one byte in it; other files there do
  not matter. A model with no profile is never extracted. A removal of the earlier unpacked files
  that fails part-way is refused by the installer, and the old archive and a partly removed
  `files/` stay; when fewer than the four names remain, the loader now answers not-installed and
  keeps the archive. The check looks at names, file kind and size above zero only: it does not
  hash the four files (see the next bullet), and the locator checks the same four again before the
  engine opens them.
- The loader hashes the archive only on a load with no usable stamp. With a stamp it hashes the four unpacked
  files, and only when a size matches and a modified time differs. A size that changed is refused at once, so
  the invariant "corrupt model file: load refused and the file deleted" holds for size changes and for content
  changes that the hash sees. The quick check does not see a file damaged in place with the same size and the
  same modified time (see the next three bullets).
- Storage rot: a file damaged in place with the same size and the same modified time is not detected by the quick
  check. The loader does not hash the four files while the sizes and modified times match, so that damage is found
  only by installing the model again, or by the native library failing on it, which can end the process.
- A restore or copy that resets modified times changes no size, so the loader hashes the four unpacked files once
  and refreshes the stamp. That costs one hash of the four files, not of the archive.
- The word stream factory never hashes. After a restore that resets modified times, it refuses the model until the
  loader has run once: the loader hashes the four files and refreshes the stamp, and the next open then succeeds.
- The first load after an upgrade has no stamp: the archive is verified once, and the stamp is then written from the
  unpacked files as they are. The unpacked files are trusted once, as they stand on that load; every later load uses
  the stamp.
- The unpack side adds a library to the module: the archive library (`commons-compress`,
  Apache-2.0) at the version in `gradle/libs.versions.toml`, which carries the dated source line.
  Gradle also resolves three libraries it declares: `commons-codec`, `commons-io` and
  `commons-lang3` (Apache-2.0); the catalog names their versions in comment lines and has no
  entries for them. Main code imports the archive library in `ModelExtractor.kt` only.
- The profile bounds come from the entry listing of each real archive (names and sizes) plus
  headroom, and a test pins the listing figures. The extractor itself has not been run on a real
  archive: its tests use small archives they write themselves. Details a listing does not show
  (extended headers, for example) have not been seen.
- Existing tests that install a model were re-pointed to serve a real archive
  (`TarFixtures.tinyArchive()`), and the loader tests seed a `files/` directory
  (`Fixtures.seedExtracted`). The installer tests use the real extractor by default, so they need
  free disk space in the temp folder equal to the tiny model's written-bytes bound (80 MiB); on a
  full disk they fail with a no-space refusal, not a hang.
- There is no resume: an interrupted download starts again from zero. A partial file named
  `<id>.download` can stay in the staging directory after a process death; the installer sweeps every
  such stale partial before the fetch and counts the bytes it actually frees toward the free-space
  check, refusing NO_SPACE if they do not cover the shortfall (built, unit-tested, not tried on a phone).
  No checksum list is staged any more (built, unit-tested, not tried on a phone).
- The download limits and the two host sets are recommended values and are NOT measured on a
  device. The redirect host was read from the live release when the fetcher was written. If the
  release host changes, a download fails closed (the "could not be downloaded" sentence) until the
  redirect set is updated.
- The channel is the platform's trust store, with no certificate pinning. A certificate authority
  the user installed on the phone can intercept the download, but it cannot defeat the digest
  check, which is the integrity control.
- The host rate limits unauthenticated API access (the limit itself was not measured). A 403 or 429
  shows as "could not be downloaded". The install makes one request, for the model, so a
  network failure shows the model sentence (built, unit-tested, not tried on a phone).
- The metered-data and Wi-Fi-only policy, the `INTERNET` permission, running the download off the UI
  thread and allowing one install at a time belong to the app wiring, not to this module.
  `install()` now also unpacks inside the same blocking call, so the whole call must stay off the UI
  thread; how long the unpack takes on a phone has not been measured.
- Not exercised yet: the real TLS handshake, Android's own `HttpURLConnection` behaviour (the tests
  run on the JDK's) and a full-size download on mobile data. A device check is owed before anyone
  says downloads work.
- The decode deadline does not cover loading the model: the registry lookup, hashing the archive or the unpacked files, and
  creating the recognizer are not bounded, so a load that never returns still holds the slot and
  every call queued behind it waits.
- An abandoned decode (one that passed its deadline) can leak one worker thread and one recognizer,
  up to the model's memory, until the native call returns. At most one at a time: `transcribe`,
  `transcribeAsync` and `preload` return the busy failure while it runs, so no second recognizer is
  created next to the stuck one. If the native call never returns, the thread and the recognizer
  stay until the process ends. `close()` does not cancel it.
- A slow phone or a long clip can trigger a false timeout: the engine reports a failure while the
  native call keeps using CPU and battery until it ends.
- The limit (30 seconds plus three times the clip length) is a recommended value, not a
  measurement. It has not been tried on a device.
- Five source files under `src/main` are over the soft line cap of 300 lines and under the hard cap of 500:
  `OnDeviceWordStream.kt` at 450 lines, `ModelInstaller.kt` at 355 lines, `ModelLoader.kt` at 329 lines,
  `LocalModelStore.kt` at 331 lines and `OnDeviceSttEngine.kt` at 302 lines. None is split further for now.
- The test `test_build_file_adds_exactly_one_external_dependency` (in the unpack pins) is false by
  its own name, because the build file now adds two coordinates. It is to be renamed in a later
  change.
- The thread-affinity reads above were made on upstream release 1.13.8. They were not repeated on
  the source at commit 11afbd00 that this package is built from.
- The word marker (U+2581, written as an escape in source) and the per-token start times are read from the class files of the published sherpa-onnx package; the fields exist and the code compiles against them. Only their values and units on a device are unverified; check them on a device run.
- After an overflow drop the word times are early by the dropped audio. That is how the skew shows; it is not corrected.
- Device-only and not verified: whether the offset lands on the word "and" on a real capture, the start latency (the model load at start), the CPU use and the flush deadline value.
- The port has no error channel: an Exception from a native call, or one from the update callback, ends the word stream quietly, with nothing thrown and no final update, and `failures` counts it. A link failure (a LinkageError) from the native open ends the run the same way and is counted. Any other Error (an AssertionError or an OutOfMemoryError, for example, or a LinkageError from a later native call) is not caught: it leaves the worker once the native objects are released, and it reaches the uncaught-exception handler of the scope. A release that throws an Exception is counted in `failures` and swallowed; an Error from a release propagates only after both releases have run (if both throw an Error, the recogniser's replaces the stream's). Coroutine cancellation is rethrown and not counted.
- `onUpdate` must never wait on the thread that calls `stop`: the final update is delivered while that caller is blocked in `stop`, so the app posts each update to its own queue and never blocks.
- `ModelFetcher.fetchModel` has a three-argument form with a progress callback (bytes received, total from the final reply or -1 when unknown). The two-argument form stays abstract, so a fetcher that only knows it keeps working; the installer guards the callback.
- Nothing in production can cancel a download; the fetcher and the installer carry a polled cancel signal (built, unit-tested, not tried on a phone); the app does not wire it to a control or to the downloader yet. The fetcher's `cancelled` function is polled, but the app builds the fetcher with the default `{ false }`; a cancel would end the fetch as a Failed("cancelled") result.
- NOT REPRODUCED (a defect report): a phone test of APK 8 found the installed model gone after an update; an in-place 6 to 8 update keeps it in the emulator, and APKs 1-5 had different signing keys. The app keeps the model app-private in `filesDir/models` (`LocalModelStore`, built in `BreakerCompositionRoot`), which an update keeps; the loader deletes a model whose archive or unpacked-file check fails, so a change to the pins or the check list is one lead. Required: an update keeps the model.
