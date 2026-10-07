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
ran a full Gradle build simultaneously on the shared GitHub-hosted runner.
Measured before the fix: `module/core` commit `a231aedf` ran the `build` job
twice, one attempt failing, one passing; the same double-run pattern showed
on `module/transport` (`5ff3eef4`) and `module/audio` (`2e378830`). Two
compiler daemons and two test JVMs contending for the same runner produced
recurring "the same test fails intermittently on GitHub but never locally"
reports on those commits.

The build also downloaded its whole Maven/Gradle dependency tree from Maven
Central on every single run, with no cache step. Maven Central's own 429
guidance says aggregate traffic through a shared egress path — explicitly
including hosted CI — can trip its rate limit even when one tenant's own
traffic is reasonable, because the limiter sees the combined effect of many
unrelated jobs on the same infrastructure, not this repository alone
([central.sonatype.org/faq/429-error](https://central.sonatype.org/faq/429-error/)).

## Decision
`concurrency.group` is keyed on `github.event.pull_request.head.sha ||
github.sha` instead of `github.ref`, so the `pull_request` and `push` events
for one commit share a single run slot (`cancel-in-progress: true` already
cancels the loser rather than letting both finish). `actions/setup-java`'s
`cache: gradle` option is enabled to persist `~/.gradle/caches` (dependency
jars and build-cache entries) across runs via GitHub's own actions cache.
The Gradle invocation drops `--rerun-tasks` and `--no-build-cache`.
`--no-daemon` is kept — daemon reuse has no benefit on a runner destroyed
after the job.

Dropping `--no-build-cache` does more than stop wasting CPU: `gradle.properties`
already sets `org.gradle.caching=true`, so with the dependency cache above now
restoring `~/.gradle/caches` between runs, a module whose inputs are unchanged
from an earlier cached run can have its `test` task's output **restored from
the build cache rather than re-executed**. The `Kotlin tests ran and passed`
step still reads real JUnit XML either way, so a restored result is not a
false pass — but the `Clean build, every Kotlin test` step name no longer
means every test necessarily ran on this exact commit's runner, for a module
whose own inputs didn't change. That is an accepted trade for this ADR (see
Consequences), not an oversight.

## Reasons
- Confirmed live, not just theorized, on the very next commit after the fix
  landed: `module/core` commit `3b3150fb`'s `push` run was cancelled by its
  `pull_request` run for the same commit, instead of the two running side by
  side — the dedupe working exactly as designed.
- Maven Central's own guidance is explicit that retrying harder makes 429s
  worse, and names shared/hosted-CI egress specifically; caching and reducing
  redundant traffic is the documented fix, not retry/backoff.

One separate, still-open item this ADR does **not** explain: a transport
test (`ProbeExecutorThrowingBodyTest`, a 10-second bounded wait) failed once
on a branch with no `push` twin at all (`fix/transport-...`, a single
`pull_request` run), so duplicate-run contention cannot be the cause there.
It did not reproduce in 25 local attempts including under deliberate CPU
starvation, and passed clean on a bare re-run of the identical commit — real
runner-side variance of some other kind, cause still unconfirmed. That
investigation stays open; this ADR fixes the duplicate-run contributor it
actually found and measured, not every source of GitHub-only flakiness.

## Consequences
Rules in: every CI run for one commit shares one concurrency slot regardless
of which event triggered it; the Gradle dependency cache (and, as a result,
the build cache) is a standing part of the `build` job; a module whose
inputs are unchanged between two commits may have its test result restored
from cache rather than freshly executed, with the JUnit-XML check as the
backstop that a restored result is still a real recorded pass.

Rules out: trying to fix individual "flaky" tests one at a time as the first
response to a GitHub-only failure that doesn't reproduce locally — check for
run contention and cold/uncached state first, but don't assume this ADR's
mechanism explains every such failure; some stay genuinely unexplained until
investigated on their own. Blind retry-on-failure for the Maven Central 429
class specifically, which the operator's own policy warns against. A larger
GitHub runner tier or a self-hosted runner remain open, larger infrastructure
decisions, deliberately not folded into this ADR.
