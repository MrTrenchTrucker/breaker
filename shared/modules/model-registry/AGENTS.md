# AGENTS.md — shared/modules/model-registry/

## Purpose

Model sizes (in MiB and in bytes), release-asset URLs and ids, our own SHA-256 pin and byte size per model, the advisory upstream checksum.txt cross-check, licenses, the label, rating and offered flag of each speech model, hosted flag. The canonical list of ASR models for on-device and, as planned (ADR-016), server use; no server code reads it today:
sizes, download URLs, **SHA-256 pins** (integrity gate, T1), and **per-model
license terms** (Security Review: runtime ASR models carry their own upstream terms,
outside the audit).

**Integrity policy (Security Review mitigation 1) [2]:** treat every `.onnx` as
untrusted. Model URLs name a **release-asset id** (never the moving
asr-models ref), and every download is verified against our own recorded pin: the SHA-256 and the byte size held in its entry [2] (built, unit-tested, not tried on a phone, in the downloader). Upstream's checksum.txt is advisory: it is compared with our pins when the registry is authored and is never fetched on install (built, unit-tested, not tried on a phone). An asset id is not immutable, because upstream can re-point it; our size and SHA-256 catch that (built: hand-traced only, not run).

**On-device (sherpa-onnx compatible, S25 Ultra 16 GB):**
- `tiny`, `small` — streaming Zipformer (English); `small` is the recommended
  default for real-time dictation, `tiny` for weaker hardware
- `base`, `medium` — non-streaming Whisper (English); optional, higher
  accuracy, slower; they need non-streaming support, which is not built yet
  (see Known Gotchas); their `kind` is `offline` and their `model_type` is `whisper` in the data, and the engine still refuses them: `whisper` is not in the supported set of tools/model_types.py, so they carry no files or limits and are never offered (ADR-050 J: the app does not offer or run a type it does not support; built as data and generator rules and run as Python tests, the generated Kotlin compiles in the project's CI build), and there is no Whisper recogniser (not built)
- `parakeet` — NVIDIA Parakeet TDT 0.6B v3 int8 (CC-BY-4.0), an OFFLINE model labelled "Better", rated 2 of 3 and offered, with its own sha256, byte size and asset id (built as registry data; upstream publishes no checksum.txt line for it, so the sha256 is our own hash). Its model type, file names, extraction limits and language are registry data (built, unit-tested, not tried on a phone: run as Python tests of the generator, and the generated Kotlin compiles in the project's CI build), and stt-ondevice builds its unpack profile and offline recogniser from that entry with no model id in its code (built, unit-tested, not tried on a phone; the recogniser's native binding is compiled against the library by the project's CI build); the first-run and settings screens offer it (built, unit-tested, not tried on a phone).
- `kws-gigaspeech-3m` — a keyword-spotting model (English), kept in its own
  list (`keyword_models:`) apart from the speech models; it is a registry
  entry only: nothing downloads, unpacks or loads it yet

**Server (Whisper X container, CPU):** whatever the existing pipeline already
serves — document it here for parity.

**Format:** `models.yaml` — one entry per model: id (lowercase alphanumerics
starting with a letter, e.g. `small`; `7small` is refused because the generated
Kotlin names a `val` after it and a Kotlin identifier may not start with a digit),
family (sherpa-onnx|whisper), params, size_mb (MiB, rounded; for the `small`
entry, 365,748,162 is the byte size of release asset 191972150 from that
asset's GitHub release metadata, and 191972150 is the asset id in that entry's
own url: 348.8 MiB = 349, in decimal MB it would be 366 - the upstream
checksum.txt that sha256 is checked against is a different file, 57,685 bytes),
url, sha256, **license** (the
SPDX id when the license has one, e.g. `MIT` for Whisper weights; check sherpa-onnx
model cards), `license_name` and `license_link` (required when the license has no
SPDX id, such as a custom or non-commercial model license), `upstream_commit` (the
upstream source revision the model file was built or converted from, recorded
separately from `url`), `tamper_verified` (true only after the tamper test has been
run against this entry and watched refusing a corrupted copy), archive_name
(the upstream file name the entry's sha256 is checked against - a plain file
name, its (archive_name, sha256) pair a line of the committed fixture),
hosted, notes. Every entry also carries `size_bytes` (the archive's byte count) and `asset_id` (the release-asset id; an asset url must end with it, and the generator refuses a mismatch). A speech entry also carries `kind` (`streaming` or `offline`) and `offered` (`true` or `false`), and may carry `label` (non-empty text) and `rating` (a whole number 0 to 3); `offered: true` needs both a label and a rating. `small` is "Good", 1; `parakeet` is "Better", 2; `tiny`, `base` and `medium` are not offered and carry neither. A keyword entry carries only `size_bytes` and `asset_id`. `base` and `medium` carry kind `offline` as data, and the engine still refuses them. A speech entry also carries `model_type` and, for the engine, `files_encoder`, `files_decoder`, `files_joiner`, `files_tokens`, `limit_entry_bytes`, `limit_written_bytes`, `limit_stream_bytes`, `limit_entries`, `limit_depth` and `language` (empty means the model decides), may carry `default: true` (exactly one offered entry does) and may carry a plain-text `notice` (`parakeet` carries the attribution text of ADR-050 H, byte for byte); `base` and `medium` carry `model_type: whisper`, are not offered and have no files or limits. The generated `ModelEntry` carries `kind`, `sizeBytes`, `assetId`, `label`, `rating`, `offered`, `modelType`, `files` (`ModelFiles`), `limits` (`ModelLimits`), `language`, `isDefault` and `notice`, plus `isSupported`, and `ModelRegistry.SUPPORTED_TYPES` lists the (kind, model type) pairs of tools/model_types.py (built, unit-tested, not tried on a phone: run as Python tests of the generator, and the generated Kotlin compiles in the project's CI build); a hand-built entry without `sizeBytes` has 0, which the check refuses. The speech module's recogniser factory reads `kind`; the settings and first-run screens read `label`, `rating` and `offered` (built, unit-tested, not tried on a phone).
Required in every entry:
id, family, params, size_mb, size_bytes, asset_id, url, sha256, tamper_verified, hosted, archive_name, and, in the speech list, kind, offered and model_type,
upstream_commit (required unless the entry is not tamper verified: `parakeet` leaves it out), and either `license` or both `license_name` and `license_link`; `notes`, `label` and `rating` are
A speech entry whose (kind, model_type) pair is in tools/model_types.py (streaming with an empty model_type, offline with nemo_transducer) also needs the four files_* names, the five limit_* values and `language`; the generator refuses an offered entry outside that set, a file name that is empty or holds `/` or `..`, a stream limit below the pinned size or below the entry or written limit, a limit that is not positive, a default count other than one, a default that is not offered, and a blank notice (built and run in Python; compiled as Kotlin by the project's CI build).
optional. The keyword models are a second top-level list, `keyword_models:`
(optional), whose entries obey the same rules with these differences: a keyword entry carries no `kind`, `label`, `rating`, `offered` or engine field (`model_type`, `files_*`, `limit_*`, `language`, `default`, `notice`) (the generator refuses them), the id
may hold single hyphens between alphanumerics (the generated constant turns
them into underscores), `params` may carry a decimal point (the number is only
the one in the model's own name), and `upstream_commit` is optional (checked
when given; the keyword entry records none, because no source revision could
be tied to it truthfully). An id may not be in both lists and may not name a
constant the generated object declares (`ALL`, `KEYWORD_MODELS`). The generated
`licence` constant (ADR-016) carries the SPDX id, or
`license_name` when there is none. `models.yaml` is the hand-edited source of
record; it is not shipped to the phone and never parsed at runtime (ADR-016).

**Hosting (F30, D25):** if licensing permits, the model is also served from the
Breaker container (web-fe) with checksums, so the phone downloads from the
container instead of upstream [2]. Each entry records `hosted: true|false` and
the license check result (R26).

**Build phase:** Before Phase 2: `settings` (Phase 2) and `stt-ondevice` (Phase 3) both depend on it. Needs first: nothing beyond `shared`.

## Invariants
- Every entry has a sha256 and a byte size. The download and verify path is stt-ondevice's: its loader tests load an archive that does not match its pin and see the load refused. `tamper_verified` stays false for an entry until the tamper test has been run against that entry's real archive: the four entries that existed on 2026-11-13 (`small`, `tiny`, `base`, `medium`) are true, and `parakeet` is false because its real archive has not been tamper-tested. The opt-in `RealArchiveTamperTest` in stt-ondevice (skipped unless `BREAKER_REAL_MODELS_DIR` names a folder that holds the real archives) loops all five speech entries, so it needs all five archives; it was run on 2026-11-13 against the four then listed (under the earlier pin rule, not re-run under the size and digest rule), accepted each and refused a copy of it with one flipped byte, and it has not been run with the `parakeet` archive. The per-entry test `RegistryTamperTest` in stt-ondevice loops `ModelRegistry.ALL` and proves, against a stand-in file, that each entry refuses an archive that does not hash to its pin and an archive of another length than its pinned byte size, and accepts a stand-in that matches its own pin and size (built, unit-tested, not tried on a phone); it flips no flag. The keyword entry's `tamper_verified` is false: its real
archive has not been tamper-tested, and that per-entry test loops `ALL` (the
speech list) only.
- Every entry has a license field (R8).
- URLs pinned to specific releases (no floating "latest").
- Registry versioned; app refuses unknown model ids.
- **Every entry verifies against its own SHA-256 pin and byte size** (T21) [2] (built, unit-tested, not tried on a phone, in stt-ondevice). Upstream's checksum.txt is advisory: each entry that has a line there is compared with it at authoring time only, and `parakeet` has no line, so the cross-check skips it (ADR-050). The fetch
  route, fetch date, upstream file size and sha256, and the byte-exact
  upstream line are recorded in `fixtures/upstream-checksum-excerpt.txt`;
  each entry's own `notes` field carries the rest of its provenance. The
  keyword entry's checksum.txt (a different release) was uploaded or updated
  on 2026-11-18, years after the model asset, so its provenance is weaker than
  the speech entries'; the fixture and the entry's notes say so.
- The generator checks every entry before it writes anything, and fails on a
  missing required field (archive_name is among them), an unknown field, an
  archive_name that is not a plain file name or whose (archive_name, sha256)
  pair is not a line of the committed upstream-checksum fixture (the one exception is an archive upstream lists no line for, named in the generator's own short list, which today holds `parakeet`, and only while the fixture holds no line for it), a sha256
  that is not 64 hex
  characters, a url that is neither a release-asset id URL
  (`.../releases/assets/<numeric id>`) nor a URL naming a full 40-character commit,
  or a license with neither an SPDX id nor a `license_name` and `license_link`,
  or an id that is in both lists. Every rule above applies to a keyword entry
  too. CI runs it, so a bad entry never reaches the app.
- The generator also refuses a `size_bytes` or `asset_id` that is not a positive integer, an asset url whose id differs from `asset_id`, a `kind` that is not `streaming` or `offline`, a `rating` outside 0 to 3, a `label` that is empty or has leading or trailing spaces, an `offered: true` entry without a label or without a rating, and a speech-only field on a keyword entry (built, unit-tested, not tried on a phone).

## Owns
Model sizes (in MiB and in bytes), release-asset URLs and ids, our own SHA-256 pin and byte size per model, the advisory upstream checksum.txt cross-check, licenses, the label, rating and offered flag of each speech model, hosted flag.

## Public Interface
Generated Kotlin model registry (ADR-016)
- Kotlin constants generated from `models.yaml` by `tools/gen_model_registry.py`;
  consumers name a model by these constants, never by reading `models.yaml`.
- `ModelRegistry.ALL` and `byId` answer for the speech models only.
  `ModelRegistry.KEYWORD_MODELS`, `KWS_GIGASPEECH_3M` and `byKeywordId` answer
  for the keyword models only, so a keyword model is never offered or looked up
  as a speech model by accident. No consumer uses the keyword list yet.

## Depends On
- shared (registered in modules.toml)

## Does Not Own
- Download/verify logic (android/stt-ondevice)

## Test Locations
- Unit (Python): `tests/unit/shared/model-registry/`, created with the module's first code. Run: `python3 -m unittest discover -s tests/unit/shared/model-registry`
- Contract: `tests/contract/test_model_registry_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_model_registry_contract.py`
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

- A fifth entry, `parakeet` (Parakeet TDT 0.6B v3, CC BY 4.0, the sherpa-onnx int8 release asset of 487,211,443 bytes): built, unit-tested, not tried on a phone, as registry data with its own SHA-256 pin, byte size and asset id; its real archive has not been tamper-tested, so `tamper_verified` is false.
- Label and rating: built as the registry fields `label` and `rating`, with `offered` marking the entries a screen would offer: `small` is "Good" with 1 of 3 truck emoji, `parakeet` is "Better" with 2 of 3, and `tiny`, `base` and `medium` are not offered. The app reads them: the settings section and the first-run step show them (built, unit-tested, not tried on a phone).
- `models.yaml` holds five speech entries and one keyword entry (built). The `notice` field and the `parakeet` attribution text in it are built, unit-tested, not tried on a phone, as data (Python tests; the generated Kotlin compiles in the project's CI build). The screens, the About entry that shows the notice and the NOTICE text for the CC BY 4.0 attribution are built, unit-tested, not tried on a phone.

## Known Gotchas
- GitHub's web asset-id route returns 404, so entries name the asset id (upstream can re-point it; our size and sha256 pin catches that) and use the API asset route (host `api.github.com`), which returns the file only with `Accept: application/octet-stream` — without it the route returns JSON metadata (HTTP 200) and the sha256 pin rejects it; sending the header is the downloader's concern (stt-ondevice).
- `models.yaml` and `tools/gen_model_registry.py` are in the tree (ADR-016): the generated Kotlin is committed, and the contract test fails if it drifts from the generator's output. Nothing parses `models.yaml` at runtime (ADR-016).
- URLs name a release-asset id, which upstream can re-point; verify the entry's own SHA-256 pin and byte size [2] (ADR-050: the upstream checksum.txt is advisory; built, unit-tested, not tried on a phone).
- The generated Kotlin file is never hand-edited: edit `models.yaml`, rerun the
  generator, commit both. A contract test fails if they drift (ADR-016).
- Do not add a runtime YAML or JSON reader here or in a consumer (ADR-016).
- What we borrow from transcribe.cpp's model catalog (see "Related projects" in
  the root README): a hand-edited catalog, validated in CI, with everything else
  generated from it. Its catalog is one JSON file per model; ours is the single
  `models.yaml`. Do not copy its download path: it records no per-file checksum,
  and its download URLs follow a moving branch.
- The Gradle boundary check requires a consumer to name the
  `project(":shared:modules:model-registry")` edge, because this module now has
  code and applies an artifact plugin (`java-library`); `app`, `settings`
  and `stt-ondevice` name it.
- `base` and `medium` are non-streaming Whisper assets: optional, higher
  accuracy, slower. The app cannot run a Whisper model yet: the engine refuses `base` and `medium` (their model type `whisper` is not in the supported set, so no profile is built from them, and there is no Whisper recogniser; not built), although their `kind` is `offline`. That support
  is stt-ondevice's (Phase 3, not built). A registry entry is metadata — it
  records a downloadable, verifiable asset, not a promise to ship it.
