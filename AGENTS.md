# AGENTS.md — Repo-Level Instructions

This file tells any agent — at any level, in any later session — how to work in
this repo without being told by a person first.

## The three lanes

| Lane | Owner | Entry points |
|------|-------|--------------|
| **Bug Hunt** | Security/QA | `docs/03-security-threat-model.md`, Phase 0 + Phase 9 gates, `tools/check_repo.py` |
| **Orchestration** | Structure/contracts | `ARCHITECTURE.md`, `modules.toml`, `MODULE_MAP.md`, `shared/modules/*`, `docs/04-build-order.md` |
| **Coding** | Implementation | The module's own `AGENTS.md` + `docs/01-requirements.md` (F-numbers) |

## The rules that always hold

1. **Read first.** Before drafting or changing anything, read the module's
   `AGENTS.md` and the registry entry in `modules.toml`. New work must not
   silently contradict existing docs.
2. **Terminology:** we say *text commit* / *text insertion* — never
   "injection."
3. **No cross-module imports.** Modules talk through `core` ports or the app's
   dependency wiring in `android/app/`. `depends_on` in `modules.toml` must stay
   acyclic.
4. **Every module card carries its Test Requirement.** Anything built against a
   module must be proven to fail loudly: break the protected behavior on
   purpose, confirm the test fails and says why, then restore. A test that only
   ever passes proves nothing.
5. **Registry and tree must agree.** Every entry in `modules.toml` has a real
   folder with `AGENTS.md` + `README.md`; every parent README names its
   sub-modules. Run `tools/check_repo.py` after any structural change.
6. **Security gates are load-bearing.** The forked base app is not on `main`
   yet and has not passed its build and smoke test [1][2]. Modules may be
   built, reviewed and merged before the gates are signed off; the gates
   decide what reaches users. Nothing is released (no published APK or server
   image) until Phase 0 (build + smoke test) and Phase 9 (E2E + bench +
   security review) are signed off, and sync is not released until Phase 19
   (encryption by default). Gate status and evidence: `docs/04-build-order.md`,
   "Gates and releases".
7. **Models are untrusted.** Every `.onnx` is pinned to immutable release-asset ids and verified against upstream checksum.txt before use [2].
8. **No plaintext transcriptions in logs, ever** (F32). Events only, per-user.

## Definition of done (repo-wide)

- `modules.toml` matches the folder tree; `tools/check_repo.py` passes.
- Every touched module's card is accurate (Owns / Does Not Own / Public
  Interface / Depends On / Invariants / Test Locations / Test Requirement /
  Known Gotchas).
- Terminology clean; no cross-module imports outside `core` ports.
- Security Review's fixes [1] and the sherpa-onnx mitigations [2] are preserved.

## Build order

See `docs/04-build-order.md` — 22 phases, 0 (build gate) through 22 (UI/UX).

## 6. Standing orders

These hold in every module, every session, every lane.

- Build with `./gradlew` only. Never run a system `gradle`.
- Before you add or bump any version, run `sh tools/latest.sh` and paste its line. Do not write a version from memory. Your memory is a snapshot from before the newest release; the registry is not.
  - A library the script does not cover: `curl -s https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml` (AndroidX: `https://dl.google.com/dl/android/maven2/<group path>/<artifact>/maven-metadata.xml`). Take the highest `<version>` with no `alpha`, `beta`, `rc`. Then add that library to `tools/latest.sh` in the same PR, so the next worker does not have to know this.
- Every version in `gradle/libs.versions.toml` has `# read YYYY-MM-DD from <url>` beside it. A pin with no date and URL is a pin from memory; the reviewer sends it back.
- Use `kotlinx.coroutines` for every concurrent thing. Do not use `Thread`, `synchronized`, `CountDownLatch`, or `Thread.sleep`. The `java.util.concurrent.atomic` types (`AtomicBoolean`, `AtomicInteger`, `AtomicLong`, `AtomicReference`) are fine for one shared value read and written from more than one thread or coroutine, such as a flag, a counter or a compare-and-set hand-off (the kotlinx.coroutines guide recommends them for shared mutable state); never use one as a lock or to wait (no spin or busy loops).
- A module may keep a raw `Thread`, a lock, or `Thread.sleep` where a coroutines primitive cannot reproduce something the module genuinely needs: a dedicated OS thread's priority, a timed or reentrant lock that must not throw where the design records a failure and returns, or a cancellation path that depends on being interrupt-driven rather than exception-driven. State the specific reason next to the site; "this is simpler" and "this is how it was written" are not reasons. A module does not get this by default: it argues each site, as the audio module's card does.
- A test waits on a signal. A test never waits on a clock.
- Every test, and the code it tests, must pass on a weak machine (one core, one thread, little memory, like a busy hosted CI runner) and also when the tests run in parallel on many cores. So: a timeout is only a generous safety net, never the thing being measured; no test depends on the number of cores, on thread scheduling order, or on which other test classes ran before it; any shared state between test classes is reset, and the reset is proven; production code never assumes a second core is free. A test that passes on one of those machines and fails on the other has found a defect, not a flake. Reviewers run each module's tests both ways: once limited to one core (`--max-workers=1` in a container with one CPU and about 2 GB of memory) and once with the normal parallel settings.
- One module per work order. One work order per PR.
- When a build error names a library, read the first error line before you change anything. The first line is the cause.

## 7. How the maintainers' team builds a module

The maintainers' team works this way on every module and sub-module (ADR-021).
Outside contributors may work however they like; for them this is
recommended, not required (`.github/CONTRIBUTING.md`).

- Read the root `ARCHITECTURE.md`, the parent area's card, the module's
  `AGENTS.md` and `README.md`, and every ADR they cite, before planning.
- Look over the job and read the work order first, then write one plan file
  per sub-agent: what to build, what to read, the tests that must fail first,
  and a checklist of "done". The sub-agent reads it first and ticks each item
  as it finishes it; the owner checks the result against it.
- Sub-agents write the code. The owner plans and verifies, sends wrong work
  back down, and makes no edit larger than one line itself.
- A whole-module owner plans at the module level; its sub-agents work at the
  sub-module level. Sub-agents run on Claude Sonnet (an agent on another
  harness uses its configured sub-agent model).
- Reviews judge the code: correct, tested work is not rejected for how it was
  written; the review records it and reminds the owner of the method.
