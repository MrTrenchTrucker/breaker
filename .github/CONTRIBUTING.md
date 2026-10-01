# Contributing to Breaker

Breaker is an open-source, privacy-first dictation system: a phone app and a
self-hosted server that turn speech into formatted text. This guide tells you
how to contribute — as a person or as an AI agent. Everything you need is in
the repo; if you follow this guide you should never have to ask us anything.

**One rule up front:** one module per pull request (PR). A **module** is one
folder in the repo with its own `AGENTS.md` card and `README.md`. The repo's
structure makes the rest of this guide mostly a matter of reading the right
file.

## 1. Quick start (5 steps)

1. **Pick a module.** Open the [issue list](https://github.com/MrTrenchTrucker/breaker/issues) and find an issue for
   an unbuilt module, marked easy, medium or hard. Comment on the issue to
   claim it. (One issue per unbuilt module; the issue links the module's card.)
2. **Read before writing.** Read `ARCHITECTURE.md` (the rules every change is
   judged against), the module's `AGENTS.md` card and `README.md`, and every
   ADR the card cites. An **ADR** (Architecture Decision Record) is a file in
   `decisions/` that records a settled decision and why.
3. **Set up the build.** JDK 17
   and the repo's Gradle wrapper (nothing to install — use `./gradlew`).
   Python 3.11 or newer for the check scripts.
4. **Build and test.** Run the exact commands in the card's "Test Locations".
   See section 4.
5. **Open a PR.** One module per PR, with the checklist in section 7. From
   your fork: see "Contributing from a fork" in `GITHUB-WORKFLOW.md`.

## 2. Pick a module

- The issue list is the source of truth for which modules are free. Our own
  team is building some modules now.
- Where things stand: **3 of the 31 build modules are built**
  (`android/modules/core`, `shared/modules/format-prompts`,
  `shared/modules/ui-tokens`). A 32nd module, `agent-skills/`, holds
  instructions for AI agents rather than code. The Android
  app shell is a stub. Every other module is a card, a README and a build
  file, waiting for code. Next in the build order is **Phase 2** (audio,
  settings, history) — see `docs/04-build-order.md` for what can be built now.
- Claim a module by commenting on its issue. One module per PR, so claim one
  at a time.

## 3. Set up

- **Dev container:** planned, not here yet. Until it lands, use the manual
  setup below.
- **Manual setup:**
  - JDK 17 (the build runs on it)
  - The repo's Gradle wrapper: `./gradlew` — Gradle 8.13 comes with the repo,
    pinned by SHA-256. Nothing to install.
  - Python 3.11+ for the check scripts.
  - Android SDK platform 35 (compileSdk and targetSdk 35, minSdk 30) — needed
    to build the Android app.
  - Kotlin 2.0.21 and AGP 8.13.0 are pinned in `gradle/libs.versions.toml` —
    don't change them in your PR.
- **Verify your setup:** `python3 tools/check_repo.py` must print
  `REPO CONSISTENT`.

**Server-side modules:** they need a speech-to-text service to test against.
`whisper-server` exposes Breaker's own job API to the phone
(`POST /v1/audio/transcriptions` to enqueue, `GET /v1/jobs/{job_id}` to poll)
and forwards each job to the admin-configured transcription service in that
service's own format (by default the existing Whisper X interface; see the
`whisper-server` card). No setup guide for such a service exists yet. Until a
setup note is published, test against a stub of the downstream service, and
say in the PR description which one you used.

## 4. Build and test

- **Build everything and run every Kotlin test:**
  `./gradlew --no-daemon build`
- **The maintainers' check** (forces a clean rebuild — run this before
  opening a PR):
  `./gradlew --no-daemon --no-build-cache --rerun-tasks build`
- **One module's Kotlin tests:** the command is in that module card's
  "Test Locations", e.g. `./gradlew :android:modules:core:test`.
- **Repo structure check:** `python3 tools/check_repo.py` — must print
  `REPO CONSISTENT`.
- **Structure tests for every module:**
  `python3 -m unittest discover -s tests/contract -t tests/contract`
  (430 tests today).
- **Python unit tests** exist for one module so far:
  `python3 -m unittest discover -s tests/unit/shared/ui-tokens`.
  Trap: running `discover` on `tests/unit` from the root finds 0 tests — use
  the exact command in the card. The automated checks run every folder
  for you.
- **Every test run must report more than 0 tests.** A mistyped path or
  pattern runs nothing and still prints `OK`, which looks like a pass.
- **Automated checks on every PR** (the `checks` workflow,
  `.github/workflows/checks.yml`): the build, every test tier, the repo
  consistency check and a secret scan. A PR that fails them doesn't merge.
  Run the commands above yourself first; it's quicker than waiting.

## 5. Working inside a module (the card)

Every module folder has an `AGENTS.md` card. It is the contract for that
module. Sections:

- **Purpose** — what the module is for.
- **Owns** — what the module is responsible for. Change only what the card
  says the module owns.
- **Does Not Own** — what belongs elsewhere. If your change touches this,
  you're in the wrong module.
- **Public Interface** — the functions and classes meant to be called from
  outside. Changing it needs a maintainer's OK first (see the checklist in
  section 7).
- **Depends On** — which other modules it may use. Use only the modules
  listed there: usually `core`'s interfaces ("ports") and, for some modules,
  a shared contract. Never import a sibling module directly.
- **Invariants** — things that must always be true.
- **Test Locations** — where this module's tests live and the exact command
  to run them.
- **Test Requirement** — every test must be proven to fail loudly (section 6).
- **Known Gotchas** — things that have bitten people before.
- **How This Module Is Built** — describes one way to build a module: an
  owner orchestrating sub-agents who write the code. Recommended for AI
  agents, available to anyone — **not required**. Write the code however you
  work best.

Read the card before writing anything. Read every ADR the card cites — a
change that goes against a settled decision must cite the ADR and argue for
changing it.

**One naming rule:** say "text commit" or "text insertion" for putting
dictated text into another app, never "injection". The root `AGENTS.md`
lists the repo's other rules for anyone working in it.

## 6. Tests that count

**Finished work only.** A PR is finished when it meets every item in the
checklist in section 7 — not when the author says it is. Build and test
locally, in your own fork or on your own machine, before you open a PR. A PR
is not a workspace: work that is incomplete, unverified, or outside the
module's card will be closed, not reviewed. (The one PR that stays open while
work continues is a module's main PR, which the maintainers keep open as the
module branch grows; see `GITHUB-WORKFLOW.md`. Every PR you open into it, or
into `main`, is finished work.)

This applies to AI-generated work exactly as it applies to human work.
Agents have a habit of publishing output that was never run. "The agent
produced it" is not a substitute for "the test failed first, then passed."
If your agent cannot show the fail-first proof and the checks passing, the
work is not finished — keep it local until it is.

- Every change in behaviour comes with a test you watched fail first: break
  the code on purpose, see the test fail for the right reason, then restore
  the code. That proof goes in the PR description.
- A docs-only PR says in the PR description what was checked instead.
- Never weaken, skip or delete a test to get a green result. A test that only
  ever passes proves nothing.
- Tests ship with the work — same PR, not later.

## 7. Opening the PR (checklist)

- [ ] Finished work only: built and tested locally before the PR was opened;
      nothing in the PR is a draft or half-done.
- [ ] One module per PR, and the issue is claimed by you (comment on it).
- [ ] Read: `ARCHITECTURE.md`, the module's card and README, every ADR the
      card cites.
- [ ] Changed only what the card says the module owns.
- [ ] No new module and no public-interface change without a maintainer's OK
      (the maintainer may ask for an ADR: see `decisions/README.md`).
- [ ] No new dependency (a library added to `gradle/libs.versions.toml`)
      without a maintainer's OK first: every new dependency gets a security
      and license review.
- [ ] Tests ship in the PR, with the fail-first proof in the description.
- [ ] The module's README is updated in the same PR. Card changes are
      proposed in the PR description, not edited.
- [ ] `python3 tools/check_repo.py` prints `REPO CONSISTENT`.
- [ ] `./gradlew --no-daemon --no-build-cache --rerun-tasks build` passes.
- [ ] PR description links the module's issue: `Closes #NNN` on the module's
      main PR, `Refs #NNN` on a sub-PR (see "Issue and PR conventions" below).
- [ ] PR description covers: what and why, which module, the test evidence,
      and what was NOT verified.
- [ ] First contribution? Add yourself to `CONTRIBUTORS.md` in this PR, in the
      format shown at the top of that file. Apart from the test paths your
      module's card names under Test Locations, it is the only file outside your
      module that your PR may touch.
- [ ] No secrets, private hostnames or personal data anywhere: code, commits
      or PR text.

`main` is protected: changes arrive only by PR, the automated checks must
pass, and a maintainer approves. Opening a PR fills in the description
template (`.github/pull_request_template.md`); the issue forms for bug reports
and design proposals are in `.github/ISSUE_TEMPLATE/`.

### Issue and PR conventions

The full playbook — branch naming, the three flows, merge settings and edge
cases — is in `GITHUB-WORKFLOW.md` next to this file.

- **Claiming:** comment on the module's issue to claim it. One module per
  person or agent at a time.
- **Linking:** put `Closes #NNN` (or `Fixes`/`Resolves`) in the PR
  description or a commit message to link a PR to its issue and close it on
  merge — one keyword per issue. Use `Refs #NNN` to reference an issue
  without closing it.
- **Sub-PRs:** a sub-module's PR targets the module's main PR branch, not
  `main`. Its description says `Refs #NNN` (the module's issue) and names the
  module's main PR (e.g. "Part of #50"). The module's main PR carries
  `Closes #NNN` and is the only PR that closes the issue.

## 8. Using AI agents

AI agents are welcome under the same rules as people. If you're an agent:

- Read the agent setup kit by rank in `agent-skills/` — the working rules
  and readback skills the maintainers use, with private names removed:
  - `agent-skills/project-leader/module-sop-project-leader/SKILL.md` — the
    project leader's module standard operating procedure
  - `agent-skills/project-leader/parrot-protocol-project-leader/SKILL.md` —
    the project leader's readback skill
  - `agent-skills/it-manager/module-sop-itm/SKILL.md` — the IT manager's
    module standard operating procedure
  - `agent-skills/it-manager/parrot-protocol-itm/SKILL.md` — the IT
    manager's readback skill
  - `agent-skills/worker/module-sop-worker/SKILL.md` — the worker's module
    standard operating procedure
  - `agent-skills/worker/parrot-protocol-worker/SKILL.md` — the worker's
    readback skill
  The module SOP skills set out how each rank runs its part of a module
  build. The parrot protocol skills set out the readback rule: restate the
  task and its limits before starting, and stop to ask when unsure.
- Before starting, restate the task and its limits in your first message.
- Stop and ask when unsure — don't guess.
- Never publish unfinished work. "The agent produced it" is not proof of
  anything; the fail-first proof and the passing checks are. Keep work
  local until it is ready to submit.
- The card's "How This Module Is Built" section describes the sub-agent
  method (an owner orchestrating workers). Recommended for agents, not
  required.

## 9. Security, conduct, help

- **No secrets.** No API keys, passwords, private hostnames, or personal data
  in code, commits, or PR text. Every PR is scanned for secrets by the
  automated checks (the `checks` workflow's secret scan); fake keys exist in tests only to prove keys never
  leak.
- **Models are untrusted.** Every downloaded `.onnx` model is pinned to an
  immutable release-asset id (never a tag or branch, which can move) and
  verified against the upstream checksum.txt before use. Never add a model
  URL that floats.
- **sherpa-onnx stays off the server's network-facing path.** Its transducer
  greedy-search decoder has an open upstream bug (GitHub issue #3983 — an
  out-of-bounds write reachable from a tampered model). That is worse on a
  server than on a phone, so no server component that accepts requests from
  the network runs sherpa-onnx. Don't undo that.
- **Security reports:** follow [`SECURITY.md`](SECURITY.md): report privately
  through GitHub's "Report a vulnerability" button, never in a public issue.
- **Conduct:** everyone follows [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)
  (the Contributor Covenant 2.1).
- **Help:** [open an issue](https://github.com/MrTrenchTrucker/breaker/issues/new/choose). The maintainers answer.

## 10. License

Everyone who contributes is credited by name in `CONTRIBUTORS.md`.

Breaker is Apache-2.0. Your contributions are under the same license — by
opening a PR you agree to that. The `NOTICE` file credits the third-party
code (the forked base app, sherpa-onnx, the speech models); don't remove or
alter it. Anything released under Apache-2.0 stays available under it.
