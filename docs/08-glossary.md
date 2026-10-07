# 08 — Glossary

Plain-language definitions of the terms this repo uses, for a new contributor
or an AI agent reading the docs for the first time. **If anything here ever
disagrees with `ARCHITECTURE.md`, a module's own `AGENTS.md` card, an ADR, or
`.github/CONTRIBUTING.md`, that linked document is right and this glossary is stale —
file an issue.** Terms are alphabetical; each entry says where it's defined or
used in the repo so you can read the source, not just this summary.

---

**Adapter** — A feature module's implementation of one of `core`'s ports —
for example `stt-ondevice` adapts the `SttEngine` port to sherpa-onnx, and
`stt-server` adapts it to polling `whisper-server`'s job API. `core` defines
the port; every other module is an adapter around it, and modules never
import each other directly (`ARCHITECTURE.md` §4 "Android App Architecture";
the port list in `android/modules/core/AGENTS.md`; root `AGENTS.md` rule 3).

**ADR (Architecture Decision Record)** — "A file in `decisions/` that
records a settled decision and why" (`.github/CONTRIBUTING.md` §1). If a change would
go against one, the PR must cite it and argue for changing it. The index, the
template and how to propose one are in `decisions/README.md`.

**Agent token** — A scoped API token an admin generates (name, optional
expiry, owner account) so an AI agent can call the transcription API
directly — no Android app needed — through the same FIFO queue as any user,
and read its own result from `GET /v1/jobs/{job_id}` like any caller. Scopes
are `transcribe` (default) or `admin` (`docs/01-requirements.md` F27, D29;
`ADR-009-roles-tokens.md`).

**Auth verifier** — The one password-derived value the server ever receives.
The client runs one Argon2id pass on the password and a random salt, then
HKDF-splits that single output into a KEK (kept client-side) and this auth
verifier (sent to the server whenever the password is set or checked:
register, login, password change, reset completion). A raw, unhashed leak
of it is a direct login credential, not just guessing-bait (`ADR-006-encryption.md`
"Decision" and "Reasons").

**Box keypair** — An X25519 keypair every account holds alongside its DEK,
generated client-side at the same first-login bootstrap: the private half is
wrapped by the account's DEK (not the KEK) and only the wrapped form reaches
the server; the public half is stored plain, since sealing to it needs no
secret. Used to seal a copy of an agent-token job's result to the owner
(`ARCHITECTURE.md` §12 "Multi-User & Auth"; `ADR-018-owner-sealed-box.md`).

**Card (`AGENTS.md`)** — Every module's own `AGENTS.md` file: "the contract
for that module," with fixed sections — Purpose, Owns, Does Not Own, Public
Interface, Depends On, Invariants, Test Locations, Test Requirement, Known
Gotchas, plus "How This Module Is Built" (a recommended method, not a
requirement) (`.github/CONTRIBUTING.md` §5; root `AGENTS.md` rule 1). Read it, and the
module's `README.md`, before writing anything in that module.

**Contract test** — A structural Python test for a module, listed per card
under "Test Locations," e.g. `tests/contract/test_core_contract.py`. Run all
of them with `python3 -m unittest discover -s tests/contract -t
tests/contract` (`.github/CONTRIBUTING.md` §4). Distinct from that module's own
**unit test** (below).

**Core** — `android/modules/core`: the pure-Kotlin domain module — models,
the ports every feature module implements, and the use cases that run a
dictation. No Android imports, no I/O, testable with plain JUnit. Every other
Android module depends on `core`; `core` depends on nothing
(`android/modules/core/AGENTS.md`; `modules.toml` `[module.android_core]`).

**DEK** — The random 256-bit key (AES-256-GCM) that actually encrypts a
user's transcriptions. Generated client-side at the account's first-login
key bootstrap, wrapped by the KEK, and never generated, derived, or seen in
unwrapped form by the server (`ADR-006-encryption.md`).

**`depends_on`** — The field in each `modules.toml` entry listing which other
module IDs a module may use. Must stay acyclic; modules never import each
other outside what this field allows — they talk through `core`'s ports or
the app's DI wiring instead (root `AGENTS.md` rule 3; `ADR-001-repo-layout.md`).

**Fail-first proof** — The evidence a test actually protects something: break
the protected behavior on purpose, watch the test fail and confirm it fails
for the right reason, then restore the code — and put that proof in the PR
description. Required for every test added or touched, "not only the ones
that seem fragile" (`.github/CONTRIBUTING.md` §6; every module card's "Test
Requirement" section, e.g. `android/modules/core/AGENTS.md`).

**Fallback** — On-device transcription (sherpa-onnx) taking over automatically
when Local Server can't be reached, so dictation never stops; never a silent
attempt at some other cloud service (`docs/01-requirements.md` F3, N8;
`ADR-002-server-primary-fallback.md`).

**Formatter** — The `core.Formatter` port, with two implementations picked by
which route a dictation took: the server route calls the existing LLM
(`/v1/chat/completions`) with a strict non-destructive prompt from
`shared/modules/format-prompts`; the local/on-device route uses a
deterministic rule-based formatter and never reaches the server. Nothing is
formatted when the user turns formatting off (`formatting_enabled`)
(`ARCHITECTURE.md` §9 "Formatting"; D7).

**Invariant** — A module-card section listing what must always hold for that
module — e.g. `core`'s "No `android.*` imports anywhere," or `server`'s
"Ciphertext only at rest." Each one should be backed by a test that was
watched failing first, per the card's Test Requirement (root `AGENTS.md`
rule 4; e.g. `android/modules/core/AGENTS.md` "Invariants").

**Job API** — The async contract `whisper-server` exposes:
`POST /v1/audio/transcriptions` always enqueues and returns
`202 { job_id, status: "queued" }`; the caller polls
`GET /v1/jobs/{job_id}` for the result. There is no synchronous variant that
returns the text in the first response (`ADR-015-transcription-queue.md`;
`server/modules/whisper-server/AGENTS.md`).

**KEK** — The subkey (Argon2id + HKDF, label `breaker-kek-v1`) that wraps the
DEK. Derived client-side from the password and never leaves the client, in
either direction (`ADR-006-encryption.md`).

**`key_version`** — An integer on every synced transcription record marking
which DEK generation encrypted it (`0` means plaintext — a row pushed before
the account had any DEK at all). Sync dedupes on the pair `(client_id,
key_version)`; the same id at a higher version is an atomic replace, at a
lower version it's refused (`ADR-006-encryption.md`; `ARCHITECTURE.md` §11
"History, Sync & Web Access").

**KWS** — The upgrade path for voice-phrase detection: a small per-user model,
fine-tuned server-side (the `training` container) from 10–20 samples the user
records in their own environment, that replaces v1's streaming-ASR phrase
matching once it's trained (`ARCHITECTURE.md` §8, §13; D8;
`ADR-004-two-phrases.md`).

**Lane** — One of the three tracks a piece of work belongs to: **Bug Hunt**
(security/QA), **Orchestration** (structure and contracts — architecture,
registry, module map, build order), or **Coding** (implementation inside one
module's own card). Every module in `MODULES.md`'s registry is tagged with
its owning lane (root `AGENTS.md` "The three lanes").

**Local Server** — The user's own, already-operational self-hosted machine:
a CPU Whisper + LLM pipeline (the existing Whisper X container, plus a
`/v1/chat/completions` endpoint) reached over ZeroTier. Breaker's own server
modules run alongside it and never modify it (`ARCHITECTURE.md` Executive
Summary; §6 "Server Transcription Service").

**Local-only (mode)** — Dictation that ran entirely on-device because the
server was unreachable at the time. It still writes to history and still
syncs to the server once connectivity returns — sync isn't gated on which
mode produced the text (`docs/01-requirements.md` F13; `ARCHITECTURE.md` §11).

**Module** — One folder in the repo tree with its own `AGENTS.md` card,
`README.md`, and entry in `modules.toml` — the unit one PR builds ("one
module per pull request" — `.github/CONTRIBUTING.md` "One rule up front";
`ADR-001-repo-layout.md`). A module can itself contain sub-modules, several
folders deep.

**`MODULE_MAP.md`** — A one-line-per-module lookup table (path + job), kept
in sync with `modules.toml` by hand whenever the registry changes — the
fastest way to find which folder owns something.

**`MODULES.md`** — The top-level module registry/index: repo layout, base +
security status, the priority model, and the full module table with each
module's path and owning lane. Read it before any single card.

**Phase (build order)** — One of the numbered stages, Phase 0 to Phase 22, in
`docs/04-build-order.md` (mirrored in `ARCHITECTURE.md` §17): each phase ends
in a testable increment, assigned to one or more lanes, with stated exit
criteria. Phase 0 (fork, security fixes, build + smoke test) and Phase 9 (E2E,
bench, security review) are release gates: modules may be built before them,
but nothing is released until both are signed off, and sync is not released
until Phase 19 (root `AGENTS.md` rule 6; `docs/04-build-order.md`, "Gates and
releases"). Phase 18 was absorbed into Phase 4 and kept only as an
empty numbered slot so later phase numbers don't shift
(`ADR-015-transcription-queue.md`).

**Port** — An interface defined in `core` (`SttEngine`, `Formatter`,
`TextCommitter`, `ConnectivityProbe`, ...) that a feature module implements
as an adapter. `core` defines the shape and never imports the platform that
implements it (`ARCHITECTURE.md` §4; the port list in
`android/modules/core/AGENTS.md`).

**Public Interface** — The card section naming the functions, classes and
types a module exposes to the rest of the repo. Changing it needs a
maintainer's OK first (`.github/CONTRIBUTING.md` §5, §7). `modules.toml` carries the
same information under its `public` field.

**Rank** — An AI agent's position in the build hierarchy: **project leader**
(owns the plan and the module structure), **IT manager** (splits one module
into slices, gates every worker's piece), or **worker** (writes one slice,
with tests, inside that module's card). Each rank has its own module-SOP and
readback skill pair (`agent-skills/README.md`).

**Readback (Parrot Protocol)** — Before starting a work order, the agent
restates in its own words what it must not do and what the finished work
will look like, and waits for an OK before building anything
(`agent-skills/README.md` "Words the skills use"; `.github/CONTRIBUTING.md` §8).

**README** — Every module's `README.md`. Where the card says what a module
may and may not do, the README says what it actually does today; it must be
updated in the same PR as any code change to that module
(`.github/CONTRIBUTING.md` §5, §7).

**Registry (`modules.toml`)** — The machine-checkable module registry: one
`[module.*]` table per module and sub-module (`path`, `owns`, `does_not_own`,
`depends_on`, `public`, `card`). The folder tree must match it exactly;
`tools/check_repo.py` verifies that and must print `REPO CONSISTENT`
(`modules.toml` header comment; `.github/CONTRIBUTING.md` §3, §7).

**Requirement ID prefixes (F, N, T, R, D)** — Five separate numbered series
used across the docs, and they do not share one counter:
- **F** = Functional requirement — `docs/01-requirements.md` "Functional".
- **N** = Non-functional requirement — same file, "Non-functional".
- **T** = Threat — `docs/03-security-threat-model.md` "Threats &
  mitigations".
- **R** = Risk — `docs/05-decisions-and-risks.md` "Risks" table.
- **D** = Decision — the short ADR-style write-ups in
  `docs/05-decisions-and-risks.md` "Decisions (ADR-style)" (mirrored in
  `ARCHITECTURE.md` §18). Several also have a fuller write-up as their own
  `decisions/ADR-NNN-*.md` file. The D numbers and the ADR numbers are two
  separate series: D7 is not ADR-007. Docs always cite an ADR by its number
  and name, never by matching it to a D number.

**Reset (account reset)** — `POST /v1/admin/reset-account`: an admin, or an
admin-scoped agent token, deletes an account's keys, its transcriptions, and
any unfetched pending-sealed rows, revokes its tokens, and issues a one-time
code for the account holder to complete on their own device
(`POST /v1/auth/complete-reset`), generating fresh keys the same way a
brand-new account does. The admin does not set the new password in the
normal flow, but the code can't itself prove who presented it first — the
repo states that honestly rather than claiming it away
(`docs/01-requirements.md` F31; `ADR-018-owner-sealed-box.md`).

**Sealed box** — `crypto_box_seal` (libsodium): used to seal a copy of an
agent-token job's plaintext result to the owner's box public key, so it can
sit in the pending-sealed store unread until the owner's own client unseals
it at next login. Anonymous (no sender secret needed) and unauthenticated by
design — it protects a stored copy from read-only exposure, not from whoever
can act as the server (`ADR-018-owner-sealed-box.md`).

**Server-primary** — The default transcription mode (`SttMode.AUTO` in
code): core's `DictateUseCase` asks the `transport` module's probe first (TCP
connect, 1.5 s timeout, cached 30 s); it transcribes and formats on Local
Server if that is reachable, on the phone if not. No silent cloud
fallthrough, ever (`ARCHITECTURE.md` §7 "Transport & Fallback", D6;
`ADR-002-server-primary-fallback.md`; `android/modules/transport/AGENTS.md`).

**sherpa-onnx** — The on-device (fallback) speech-to-text engine, vendored
from XIAOMI CORPORATION (Apache-2.0), already present in the forked base
app. Kept with three mitigations for its known transducer-decoder bug
(upstream issue #3983): every model pinned to an immutable release-asset id
and verified against upstream's checksum.txt, the upstream fix tracked, and
sherpa-onnx kept off any server-side network-exposed path
(`ARCHITECTURE.md` §5; `ADR-003-sherpa-onnx.md`).

**Slice** — "The one unit of work in your work order. One slice, one module,
one sitting" — the piece of a module one worker takes from an IT manager
(`agent-skills/worker/module-sop-worker/SKILL.md`; the rank table in
`agent-skills/README.md`).

**STT** — Speech-to-text generally; concretely, the `core.SttEngine` port,
implemented by `stt-ondevice` (sherpa-onnx, local fallback) and `stt-server`
(polls `whisper-server`'s job API, primary path)
(`android/modules/core/AGENTS.md` "Ports"; `modules.toml`).

**Text commit / text insertion** — The repo's required term for landing
dictated text into the app the user was using — an accessibility service
finds the focused editable node and inserts the text
(`AccessibilityNodeInfo.ACTION_SET_TEXT` or `ACTION_PASTE`), clipboard
otherwise (a toast on Android 12 and below, the system's own copy
confirmation on Android 13+). **Never call this "injection"** — that's a
repo-wide naming rule, not a style preference (root `AGENTS.md` rule 2;
`ADR-022-accessibility-text-insert.md`, which supersedes the IME-first
mechanism in `ADR-005-ime-commit.md`).

**Transcription service** — The downstream speech-to-text backend
`whisper-server` forwards audio to. Admin-configured in the web FE (name,
base URL, optional API key, enabled) with a connectivity test — never
hardcoded; the default configured target is the existing Whisper X
container (`docs/01-requirements.md` F26; `ADR-008-configurable-service.md`).

**Unit test** — A module's own-language test (JUnit for Kotlin, `unittest`
for the one Python module that has one so far), listed per card under "Test
Locations" next to that module's contract test — e.g.
`./gradlew :android:modules:core:test` (`.github/CONTRIBUTING.md` §4;
`android/modules/core/AGENTS.md`).

**VAD** — Voice activity detection: finding where speech starts and stops in
the captured audio, so the silence around it can be trimmed. Part of the
`audio` module's job, alongside noise suppression and WAV encoding
(`modules.toml` `[module.android_audio]`); its card says how the result
reaches core (`android/modules/audio/AGENTS.md`).

**Wake phrase / send phrase** — The two CB-slang voice triggers: **"Breaker
Breaker"** (wake — the tile appears and recording starts automatically) and
**"And I'm Gone"** (send — stop, trim the audio at the phrase's own onset so
it never appears in the transcript, then dispatch). v1 detects both with
streaming ASR + phrase matching; a trained KWS model is the upgrade path
(`docs/01-requirements.md` F4, F5, F9; D8, D9; `ADR-004-two-phrases.md`).

**`whisper-server`** — Breaker's own server module and container — **not**
the existing Whisper X container, which it forwards to and never modifies.
Exposes the job API and serializes every caller (multiple users, plus any
agent tokens) through one FIFO worker before forwarding to the
admin-configured transcription service (`ADR-015-transcription-queue.md`;
`server/modules/whisper-server/AGENTS.md`).

**Work order** — One assigned piece of work. In Breaker, that's **a GitHub
issue and the pull request that closes it** (`agent-skills/README.md` "Words
the skills use"). The rank skills describe a fuller, tracked work-order file
(status, write-scope, check-ins); Breaker keeps no such file, so follow the
issue, the module card and `.github/CONTRIBUTING.md` instead.

**ZeroTier** — The VPN the phone and Local Server communicate over. HTTPS to
the server is trusted once the phone installs the self-hosted CA certificate
the server serves (one-time, from the web FE) — no further "connection not
private" warnings on the VPN or local IP (`ARCHITECTURE.md` Executive
Summary; `docs/06-sideload-and-install.md` Step 1).
