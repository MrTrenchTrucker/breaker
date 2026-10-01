# Breaker — GitHub Issues & PR Workflow (Pattern A: integration branch)

This is the operational playbook for how issues, branches and pull requests
fit together. It is the full version of the "Issue and PR conventions"
section in `CONTRIBUTING.md`. Read `CONTRIBUTING.md` first; this file is the
mechanics.

## The model in one paragraph

Every unbuilt module gets exactly one GitHub issue (the claim ticket), one long-lived
branch (`module/<name>`), and one **main PR** (the integration PR that tracks
that branch against `main`). All work for the module lands on the module
branch. The main PR is the only PR that closes the module's issue. Sub-PRs
(outside contributors) and direct pushes (internal agents) both feed the
module branch; the main PR shows the whole module diff and merges into `main`
once the module is done.

## Why this shape

- GitHub has no "merge into a PR" button — a PR is a branch compared against
  another branch. So "sub-modules commit into their module's main PR"
  translates to: everything commits to the module branch, and the main PR
  tracks it.
- One module = one branch = one PR = one issue. Every link is unambiguous,
  and the issue timeline shows every piece of work that touched it.

## Naming conventions

| Thing | Convention | Example |
|-------|-----------|---------|
| Module branch | `module/<name>` | `module/audio` |
| Sub-PR branch (contributor) | `module/<name>/<sub>` | `module/audio/vad` |
| Issue title | `module: <name>` | `module: audio` |
| Main PR title | `module: <name>` | `module: audio` |
| Sub-PR title | `module: <name> — <sub>` | `module: audio — VAD` |

## The three flows

### Flow 1 — Internal agents (the maintainers' agents)

Agents push commits directly to `module/<name>`. The main PR updates
automatically; no sub-PR is needed. Commit message:

    audio: add the energy-based VAD (Refs #12)

### Flow 2 — Outside contributor, sub-module work

1. The contributor comments on the module's issue, naming the sub-module
   they are taking, so two people (or agents) don't take the same piece.
2. They open a PR **targeting `module/<name>`, not `main`**.
3. The PR description carries `Refs #NNN` (module issue), `Part of #MMM`
   (module main PR), and the standard checklist.
4. The maintainer merges the sub-PR into `module/<name>`.
5. The main PR's diff grows; the issue timeline shows the cross-reference.

### Flow 3 — Outside contributor, whole module

A contributor who wants to build an entire module works on a branch and opens
the module's main PR themselves once the whole module is finished
(`CONTRIBUTING.md` section 6, finished work only), with `Closes #NNN` in the
description. The maintainer reviews; when the checks pass, it merges into
`main`.

## Contributing from a fork, step by step

Outside contributors can't push to this repository; only the maintainers and
their agents can. You work in your own copy (a **fork**) and open a pull
request from it. This is how flows 2 and 3 above work on GitHub.

1. **Claim the module.** Comment on its issue (the claim format is under "The
   formats" below).
2. **Fork.** On the repository's GitHub page, click **Fork**, and leave
   **Copy the `main` branch only** ticked. You get your own copy at
   `github.com/<you>/breaker`. (A fork that copies every branch already holds
   `module/<name>`, and git then refuses to push `module/<name>/<sub>` next to
   it.)
3. **Clone your fork and add this repository as `upstream`:**

       git clone https://github.com/<you>/breaker.git
       cd breaker
       git remote add upstream https://github.com/MrTrenchTrucker/breaker.git
       git fetch upstream

4. **Start from the right branch.**
   - Sub-module work (flow 2) starts from the module branch:
     `git switch -c module/<name>/<sub> upstream/module/<name>`
   - A whole module (flow 3) starts from `main`:
     `git switch -c module/<name> upstream/main`
5. **Build and test locally until the work is finished** (`CONTRIBUTING.md`
   sections 4, 6 and 7). Nothing gets opened before that.
6. **Push to your fork:** `git push origin <your branch>`.
7. **Open the pull request** on GitHub ("Compare & pull request"):
   - base repository: this one; base branch: `module/<name>` for sub-module
     work, `main` for a whole module;
   - head: your fork and your branch;
   - the description template fills in; put `Refs #NNN` and `Part of #MMM`
     for sub-module work, `Closes #NNN` for a whole module;
   - leave **Allow edits by maintainers** ticked, so a maintainer can push a
     small fix to your branch instead of sending it back.
8. **Checks and review.** The automated checks run on your PR (on a first
   contribution a maintainer may have to approve them before they start). A
   maintainer reviews it and squash-merges it. A sub-module PR lands on
   `module/<name>`, and the module's main PR shows it straight away.
9. **Stay up to date** while it is open:
   `git fetch upstream && git rebase upstream/module/<name>` (or
   `upstream/main` for a whole module), then `git push --force-with-lease`.

The maintainers' own agents skip steps 2, 3 and 6: they have write access and
push to `module/<name>` directly (flow 1).

## The formats

**Issue claim comment:**

    I'll take this module. Module: android/modules/audio
    Plan: [2-3 lines]
    ETA: [date]

**Module main PR description** (the only PR that closes the issue):

    Module: android/modules/audio
    Closes #12
    What and why: ...
    Test evidence: [fail-first proof]
    NOT verified: ...

**Sub-PR description** (targets the module branch):

    Module: android/modules/audio — sub-module: VAD
    Refs #12
    Part of #50 (module main PR)
    What and why: ...
    Test evidence: ...
    NOT verified: ...

## Linking rules (GitHub-native)

- `Closes #12` / `Fixes #12` / `Resolves #12` in the PR description or any
  commit message links the PR to the issue and **auto-closes it on merge**.
  One keyword per issue — `Closes #12, #13` does not work.
- `Refs #12` links without closing. Sub-PRs use this so the module's issue
  stays open until the main PR lands.
- Any `#12` mention creates a cross-reference on the issue timeline.

## Branch protection and merge settings

- **`main`:** protected. Changes arrive only by PR, required checks must
  pass, a maintainer approves, and the branch must be up to date with `main`
  before it merges, so what lands is exactly what the checks ran on.
- **`module/*` branches:** no branch protection, so the maintainers' own
  agents can push to them directly. The checks still run on every push and on
  every sub-PR. A maintainer merges an outside contributor's sub-PR only once
  its checks pass (only people with write access can merge into a module
  branch), and the main PR into `main` must pass every check.
- **Merge method:** squash for sub-PRs (keeps the module branch clean);
  squash for the main PR into `main` (one commit per module on main).
- **Cleanup:** delete the module branch after its main PR merges.

## Definition of done for a module's main PR

- [ ] All sub-module work is on the branch; sub-PRs merged.
- [ ] `python3 tools/check_repo.py` prints `REPO CONSISTENT`.
- [ ] `./gradlew --no-daemon --no-build-cache --rerun-tasks build` passes.
- [ ] Fail-first test evidence in the PR description.
- [ ] Module README updated; card changes proposed in the description.
- [ ] No secrets, private hostnames or personal data anywhere.
- [ ] Security gates preserved: the four fixes to the forked base app stay
      intact (no silent cloud fallthrough; the base app's own updater stays
      deleted — Breaker's `android/modules/updater` replaces it, pointed at
      our server; package ID changed; Gradle distribution SHA-256 pinned);
      model downloads pinned to immutable release-asset ids and verified
      against upstream checksum.txt; sherpa-onnx kept off the server's
      network-facing path. See the Security posture section of `README.md`.
- [ ] Maintainer approves; merged into `main`; issue auto-closes via
      `Closes #NNN`.

## Edge cases

- **Module branch needs main's updates:** rebase `module/<name>` on `main`
  (or merge main into it) before the main PR merges; resolve conflicts on
  the branch.
- **Sub-PR conflicts:** resolve on the sub-PR branch, or close and re-open
  against the updated module branch.
- **Module abandoned:** the issue stays open; a new contributor claims it by
  commenting; the old branch can be deleted and re-created.
- **A change touches two modules:** it doesn't — split it. One module per PR
  is a hard rule.
