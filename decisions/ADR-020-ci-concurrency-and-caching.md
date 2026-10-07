# ADR-020: CI runs are deduplicated per commit and cache Gradle dependencies

**Status:** accepted
**Date:** 2026-10-06
**Approved by:** project owner

## Context
GitHub Actions' `checks.yml` fires on both `pull_request` and `push` to
`module/**`. A single commit pushed to a module branch triggered both events,
and the workflow's `concurrency.group` was keyed on `github.ref`
(`refs/pull/N/merge` for the `pull_request` event, `refs/heads/module/x` for
the `push` event) — two different refs for the same commit, so both triggers
ran a full Gradle build simultaneously on the shared 2-vCPU runner. This
produced recurring, misleading "the same test fails intermittently on GitHub
but never locally" reports: the failures were two compiler daemons and two
test JVMs genuinely starving each other for the runner's one physical core,
not a defect in the failing test.

The build also downloaded its whole Maven/Gradle dependency tree from Maven
Central on every single run, with no cache step. Maven Central's published
rate-limit policy groups all of GitHub Actions' hosted-runner traffic as one
shared Azure egress pool, not per repository, so a burst of unrelated CI runs
elsewhere on GitHub's infrastructure can trip 429s against a small private
repo that never used much bandwidth on its own.

## Decision
`concurrency.group` is keyed on `github.event.pull_request.head.sha ||
github.sha` instead of `github.ref`, so the `pull_request` and `push` events
for one commit share a single run slot (`cancel-in-progress: true` already
cancels the loser rather than letting both finish). `actions/setup-java`'s
`cache: gradle` option is enabled to persist `~/.gradle/caches` (dependency
jars and build-cache entries) across runs via GitHub's own actions cache.
The Gradle invocation drops `--rerun-tasks` and `--no-build-cache`: on a
fresh `actions/checkout` there is no prior build output to skip regardless,
so `--rerun-tasks` only forced extra recompilation and added CPU contention
with no correctness benefit; `--no-build-cache` is pointless now that the
cache above gives the build cache something to persist. `--no-daemon` is
kept — daemon reuse has no benefit on a runner destroyed after the job.

## Reasons
- Confirmed live, not theorized: on the fix's own merge, the `push`-triggered
  run for that commit was cancelled by the `pull_request`-triggered run for
  the same commit, exactly as designed, instead of the two contending.
- A separate, unrelated-looking CI failure (a transport test's 10-second
  bounded wait) did not reproduce in 25 local attempts, including under
  deliberate CPU starvation, and passed clean on a bare re-run of the
  identical commit — consistent with runner-side contention/variance rather
  than a code defect, and consistent with this ADR's diagnosis.
- Maven Central's own guidance is explicit that retrying harder makes 429s
  worse; caching and reducing redundant traffic is the documented fix.

## Consequences
Rules in: every CI run for one commit shares one concurrency slot regardless
of which event triggered it; the Gradle dependency cache is a standing part
of the `build` job.

Rules out: trying to fix individual "flaky" tests one at a time as the first
response to a GitHub-only failure that doesn't reproduce locally — check for
run contention and cold/uncached state first. Blind retry-on-failure for the
Maven Central 429 class specifically, which the operator's own policy warns
against. A larger GitHub runner tier or a self-hosted runner remain open,
larger infrastructure decisions, deliberately not folded into this ADR.
