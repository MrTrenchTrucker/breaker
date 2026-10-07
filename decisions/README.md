# Decisions

This folder holds Breaker's Architecture Decision Records (ADRs) — one file
per settled decision, numbered in the order it was accepted.

## What an ADR is

An ADR is a short, dated record of one decision: the situation that forced
it, what was decided, why, and what it commits the project to (and rules
out). It isn't a design doc or a how-to. Once it's accepted, it's a rule the
rest of the codebase is judged against — until it's amended or superseded by
a later one.

`ADR-TEMPLATE.md` in this folder is a copy-ready, commented starting point —
copy it when you write a new one.

## When you need one

Most changes don't need an ADR — they just follow the module's card and the
ADRs it already cites.

- **Anything that contradicts `ARCHITECTURE.md` or an existing ADR** always
  needs one.
- **A new module or boundary, a public-interface change, or a new
  dependency** (a library added to `gradle/libs.versions.toml`) needs a
  maintainer's OK before you build it, as the contributing guide's PR
  checklist says. The maintainer may ask for an ADR to record that OK, and
  for a new module or boundary usually will, so the next person doesn't have
  to ask again. Ask in the issue first (step 1 below).

## How to propose one

1. **Open an issue first**, describing the decision you think is needed and
   why. This is where it gets discussed before anyone builds against it.
2. **Open a PR** adding `decisions/ADR-NNN-<slug>.md`, using the next free
   number (see the index below) and `Status: proposed`. Start from
   `ADR-TEMPLATE.md`.
3. **A maintainer approves it**, the same as any other PR into `main`. Before
   it merges, the same PR sets its Status to `accepted` and its Date to the
   merge day, and adds its row to the index below. Nothing is pushed to
   `main` directly; a later change is its own PR (amend or supersede, see
   `ADR-TEMPLATE.md`).

**Going against an existing ADR:** if your PR proposes something a settled
ADR already ruled out, it must **cite that ADR by number and argue for
changing it** in the PR description — not just do something different and
let the diff speak for itself. `ADR-TEMPLATE.md` has the two ways an
existing ADR can then change: amended in place, or superseded by a new one.

## Index

| # | Title | Status |
|---|-------|--------|
| [ADR-001](ADR-001-repo-layout.md) | Modular monorepo layout (android / server / shared) | accepted |
| [ADR-002](ADR-002-server-primary-fallback.md) | Server-primary transcription with automatic local fallback | accepted, amended 2026-09-30 |
| [ADR-003](ADR-003-sherpa-onnx.md) | On-device engine = sherpa-onnx (pinned + verified) | accepted |
| [ADR-004](ADR-004-two-phrases.md) | Voice control = two phrases ("Breaker Breaker" / "And I'm Gone") | accepted |
| [ADR-005](ADR-005-ime-commit.md) | Text commit = IME-first with clipboard fallback | accepted |
| [ADR-006](ADR-006-encryption.md) | Encryption by default — per-user DEK, password-wrapped | accepted, amended 2026-09-30 and 2026-10-01 |
| [ADR-007](ADR-007-fifo-queue.md) | FIFO transcription queue | accepted |
| [ADR-008](ADR-008-configurable-service.md) | Admin-configurable transcription service | accepted |
| [ADR-009](ADR-009-roles-tokens.md) | Roles + agent tokens (first-account-is-admin) | accepted |
| [ADR-010](ADR-010-retention.md) | Retention policy — 3-month TTL, audio deletes on completion | accepted |
| [ADR-011](ADR-011-updates.md) | Updates — daily check + signed APK + rollback | accepted |
| [ADR-012](ADR-012-trucking-ui.md) | Trucking UI + CB mic motif (supersedes CB Radio palette) | accepted |
| [ADR-013](ADR-013-model-hosting.md) | Model hosting in the Breaker container + checksum verification | accepted |
| [ADR-014](ADR-014-multi-user-isolation.md) | Multi-user isolation, server-enforced | accepted |
| [ADR-015](ADR-015-transcription-queue.md) | Async transcription job queue, hosted in Breaker's own whisper-server (not the Whisper X container) | accepted |
| [ADR-016](ADR-016-model-registry-codegen.md) | Model registry compiled to Kotlin; no YAML parsing on the phone | accepted |
| [ADR-017](ADR-017-server-language.md) | Breaker's server services are written in Kotlin on the JVM | accepted |
| [ADR-018](ADR-018-owner-sealed-box.md) | Owner sealed box for agent-token job results; account reset | accepted |
| [ADR-019](ADR-019-agent-skills-module.md) | Agent skills ship in the repo as their own module | accepted |
| [ADR-020](ADR-020-ci-concurrency-and-caching.md) | CI runs are deduplicated per commit and cache Gradle dependencies | accepted |

Next free number: **ADR-021**.
